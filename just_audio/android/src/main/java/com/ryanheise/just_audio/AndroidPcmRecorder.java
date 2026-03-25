package com.ryanheise.just_audio;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import androidx.media3.common.C;
import io.flutter.plugin.common.MethodChannel.Result;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * TeeAudioProcessor에서 넘어오는 16-bit PCM을 {@link MediaCodec} AAC 인코더로 넣고, raw AAC에
 * ADTS 헤더를 붙여 스트림으로 저장합니다. (예전 MediaCodec + ADTS 방식과 동일한 계열)
 *
 * <p>
 * 파일 확장자는 Flutter에서 넘기는 {@code path} 그대로 사용합니다. 비트스트림은 AAC(ADTS)이며
 * MP3가 아닙니다.
 *
 * <p>
 * 오디오 스레드에서는 PCM만 복사하고, 인코딩·쓰기는 단일 스레드 실행기에서 수행합니다.
 */
final class AndroidPcmRecorder {
    private static final String TAG = "AndroidPcmRecorder";
    private static final String AAC_MIME = "audio/mp4a-latm";
    /** CBR에 가깝게; 예전 AudioRecorder와 동일 계열 */
    private static final int AAC_BIT_RATE = 96000;
    private static final int DEQUEUE_TIMEOUT_US = 10_000;

    private final Handler mainHandler;
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor(
            r -> {
                Thread t = new Thread(r, "just_audio-pcm-aac");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    private final Object lock = new Object();

    private volatile boolean recordingDesired;
    private String outputPath;
    private BufferedOutputStream encodedOut;
    private MediaCodec aacEncoder;
    /** Microseconds presentation time for queued PCM. */
    private long presentationTimeUs;
    /** Incomplete PCM frame bytes (16-bit interleaved). */
    private byte[] pcmCarry = new byte[0];

    private int lastSampleRateHz;
    private int lastChannelCount;
    private @C.PcmEncoding int lastEncoding;

    AndroidPcmRecorder(Handler mainHandler) {
        this.mainHandler = mainHandler;
    }

    void onFlush(int sampleRateHz, int channelCount, @C.PcmEncoding int encoding) {
        synchronized (lock) {
            lastSampleRateHz = sampleRateHz;
            lastChannelCount = channelCount;
            lastEncoding = encoding;
            if (recordingDesired && encodedOut == null && outputPath != null) {
                tryOpenFileForRecordingLocked();
            }
        }
    }

    void onPcmBuffer(ByteBuffer buffer) {
        if (!recordingDesired) {
            return;
        }
        synchronized (lock) {
            if (encodedOut == null) {
                return;
            }
        }
        int len = buffer.remaining();
        if (len == 0) {
            return;
        }
        byte[] copy = new byte[len];
        ByteBuffer dup = buffer.duplicate();
        dup.get(copy);
        writeExecutor.execute(() -> appendPcm(copy));
    }

    void startRecording(String path, Result result) {
        synchronized (lock) {
            if (recordingDesired) {
                result.error("ALREADY_RECORDING", "Call stopRecord before startRecord again.", null);
                return;
            }
            if (path == null || path.isEmpty()) {
                result.error("BAD_ARGUMENT", "path is required", null);
                return;
            }
            if (lastSampleRateHz > 0 && lastEncoding != C.ENCODING_PCM_16BIT) {
                result.error(
                        "UNSUPPORTED_FORMAT",
                        "Recording supports 16-bit PCM only (e.g. disable float output / check offload).",
                        null);
                return;
            }
            recordingDesired = true;
            outputPath = path;
            pcmCarry = new byte[0];
            presentationTimeUs = 0;
            if (lastSampleRateHz > 0 && lastEncoding == C.ENCODING_PCM_16BIT) {
                tryOpenFileForRecordingLocked();
            }
            result.success(new HashMap<String, Object>());
        }
    }

    void stopRecording(Result result) {
        writeExecutor.execute(
                () -> {
                    String pathOut = null;
                    boolean fileWasOpened = false;
                    IOException ioError = null;
                    synchronized (lock) {
                        recordingDesired = false;
                        pathOut = outputPath;
                        outputPath = null;
                        fileWasOpened = encodedOut != null;
                        try {
                            if (aacEncoder != null && encodedOut != null) {
                                encodeRemainingPcmLocked();
                                feedPendingPcmLocked();
                                signalEndOfStreamAndDrainLocked();
                            }
                        } catch (Throwable t) {
                            ioError = t instanceof IOException
                                    ? (IOException) t
                                    : new IOException(t.getMessage(), t);
                            Log.e(TAG, "finalize aac", t);
                        } finally {
                            closeEncoderLocked();
                        }
                    }
                    final IOException err = ioError;
                    final String out = pathOut;
                    final boolean opened = fileWasOpened;
                    mainHandler.post(
                            () -> {
                                if (err != null) {
                                    result.error("IO_ERROR", err.getMessage(), null);
                                    return;
                                }
                                if (out != null && !opened) {
                                    result.error(
                                            "NOT_STARTED",
                                            "No PCM was written yet. Start playback after startRecord so audio format is known.",
                                            null);
                                    return;
                                }
                                Map<String, Object> m = new HashMap<>();
                                m.put("path", out);
                                result.success(m);
                            });
                });
    }

    void abort() {
        writeExecutor.execute(
                () -> {
                    synchronized (lock) {
                        recordingDesired = false;
                        String path = outputPath;
                        outputPath = null;
                        closeEncoderLocked();
                        if (path != null) {
                            try {
                                File f = new File(path);
                                if (f.exists() && !f.delete()) {
                                    Log.w(TAG, "Could not delete partial recording: " + path);
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "delete partial", e);
                            }
                        }
                    }
                });
        writeExecutor.shutdown();
        try {
            if (!writeExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                writeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            writeExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void tryOpenFileForRecordingLocked() {
        if (encodedOut != null || outputPath == null) {
            return;
        }
        if (lastSampleRateHz <= 0 || lastChannelCount <= 0) {
            return;
        }
        if (lastEncoding != C.ENCODING_PCM_16BIT) {
            Log.e(TAG, "Recording requires 16-bit PCM.");
            recordingDesired = false;
            outputPath = null;
            return;
        }
        try {
            File f = new File(outputPath);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Cannot create directory: " + parent);
            }
            encodedOut = new BufferedOutputStream(new FileOutputStream(f), 16384);
            pcmCarry = new byte[0];
            presentationTimeUs = 0;
            aacEncoder = createAndStartAacEncoder();
        } catch (Throwable t) {
            Log.e(TAG, "open encoder", t);
            closeEncoderLocked();
            outputPath = null;
            recordingDesired = false;
        }
    }

    private MediaCodec createAndStartAacEncoder() throws IOException {
        MediaCodec codec;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            codec = MediaCodec.createEncoderByType(AAC_MIME);
        } else {
            codec = MediaCodec.createByCodecName("OMX.google.aac.encoder");
        }
        MediaFormat format = MediaFormat.createAudioFormat(AAC_MIME, lastSampleRateHz, lastChannelCount);
        format.setString(MediaFormat.KEY_MIME, AAC_MIME);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            format.setInteger(
                    MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        }
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, lastChannelCount);
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, lastSampleRateHz);
        format.setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        return codec;
    }

    private void appendPcm(byte[] data) {
        synchronized (lock) {
            if (!recordingDesired || aacEncoder == null || encodedOut == null) {
                return;
            }
            try {
                pcmCarry = concat(pcmCarry, data);
                feedPendingPcmLocked();
            } catch (IOException e) {
                Log.e(TAG, "append pcm", e);
                recordingDesired = false;
                closeEncoderLocked();
            }
        }
    }

    /**
     * PCM 바이트를 인코더 입력 버퍼에 맞게 나누어 넣고, 나온 AAC 출력을 ADTS로 감싸 기록합니다.
     */
    private void feedPendingPcmLocked() throws IOException {
        if (aacEncoder == null || encodedOut == null) {
            return;
        }
        int frameBytes = lastChannelCount * 2;
        if (frameBytes <= 0) {
            return;
        }
        byte[] data = pcmCarry;
        int offset = 0;
        while (offset < data.length) {
            int inIndex = aacEncoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
            if (inIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                drainEncoderOutputLocked();
                continue;
            }
            if (inIndex < 0) {
                drainEncoderOutputLocked();
                continue;
            }
            ByteBuffer inBuf = aacEncoder.getInputBuffer(inIndex);
            if (inBuf == null) {
                break;
            }
            inBuf.clear();
            int space = inBuf.remaining();
            int chunk = Math.min(space, data.length - offset);
            inBuf.put(data, offset, chunk);
            long samplesThisChunk = chunk / (long) frameBytes;
            long pts = presentationTimeUs;
            presentationTimeUs += samplesThisChunk * 1_000_000L / lastSampleRateHz;
            aacEncoder.queueInputBuffer(inIndex, 0, chunk, pts, 0);
            offset += chunk;
            drainEncoderOutputLocked();
        }
        pcmCarry = offset >= data.length ? new byte[0] : Arrays.copyOfRange(data, offset, data.length);
    }

    private void drainEncoderOutputLocked() throws IOException {
        if (aacEncoder == null || encodedOut == null) {
            return;
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        for (;;) {
            int outIndex = aacEncoder.dequeueOutputBuffer(info, 0);
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            }
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue;
            }
            if (outIndex < 0) {
                break;
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                aacEncoder.releaseOutputBuffer(outIndex, false);
                continue;
            }
            ByteBuffer outBuf = aacEncoder.getOutputBuffer(outIndex);
            int outBits = info.size;
            if (outBuf != null && outBits > 0) {
                outBuf.position(info.offset);
                outBuf.limit(info.offset + outBits);
                int packetLen = outBits + 7;
                byte[] outData = new byte[packetLen];
                addAdtsHeader(outData, packetLen, lastSampleRateHz, lastChannelCount);
                outBuf.get(outData, 7, outBits);
                encodedOut.write(outData);
            }
            aacEncoder.releaseOutputBuffer(outIndex, false);
        }
    }

    private void encodeRemainingPcmLocked() throws IOException {
        if (aacEncoder == null || encodedOut == null) {
            return;
        }
        int frameBytes = lastChannelCount * 2;
        if (frameBytes <= 0) {
            pcmCarry = new byte[0];
            return;
        }
        if (pcmCarry.length == 0) {
            return;
        }
        int rem = pcmCarry.length % frameBytes;
        int pad = rem == 0 ? 0 : frameBytes - rem;
        pcmCarry = Arrays.copyOf(pcmCarry, pcmCarry.length + pad);
    }

    private void signalEndOfStreamAndDrainLocked() throws IOException {
        if (aacEncoder == null || encodedOut == null) {
            return;
        }
        int inIndex = aacEncoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
        if (inIndex >= 0) {
            aacEncoder.queueInputBuffer(
                    inIndex,
                    0,
                    0,
                    presentationTimeUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean outputEos = false;
        while (!outputEos) {
            int outIndex = aacEncoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                continue;
            }
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue;
            }
            if (outIndex < 0) {
                continue;
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                aacEncoder.releaseOutputBuffer(outIndex, false);
                continue;
            }
            ByteBuffer outBuf = aacEncoder.getOutputBuffer(outIndex);
            int outBits = info.size;
            if (outBuf != null && outBits > 0) {
                outBuf.position(info.offset);
                outBuf.limit(info.offset + outBits);
                int packetLen = outBits + 7;
                byte[] outData = new byte[packetLen];
                addAdtsHeader(outData, packetLen, lastSampleRateHz, lastChannelCount);
                outBuf.get(outData, 7, outBits);
                encodedOut.write(outData);
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                outputEos = true;
            }
            aacEncoder.releaseOutputBuffer(outIndex, false);
        }
    }

    /**
     * ADTS 헤더 (7바이트). MediaCodec이보내는 raw AAC 프레임 앞에 붙입니다.
     *
     * <p>
     * 레이아웃은 기존 {@code AudioRecorder#addADTStoPacket}과 동일 계열입니다.
     */
    private static void addAdtsHeader(byte[] packet, int packetLen, int sampleRateHz, int channelCount) {
        int profile = 2;
        int freqIdx = adtsSampleRateIndex(sampleRateHz);
        int chanCfg = Math.min(channelCount, 7);

        packet[0] = (byte) 0xFF;
        packet[1] = (byte) 0xF9;
        packet[2] = (byte) (((profile - 1) << 6) + (freqIdx << 2) + (chanCfg >> 2));
        packet[3] = (byte) (((chanCfg & 3) << 6) + (packetLen >> 11));
        packet[4] = (byte) ((packetLen & 0x7FF) >> 3);
        packet[5] = (byte) (((packetLen & 7) << 5) + 0x1F);
        packet[6] = (byte) 0xFC;
    }

    /** ISO/IEC 14496-3 samplingFrequencyIndex (일부 샘플레이트만 명시, 나머지는 가장 가까운 값). */
    private static int adtsSampleRateIndex(int sampleRateHz) {
        if (sampleRateHz >= 96000) {
            return 0;
        }
        if (sampleRateHz >= 88200) {
            return 1;
        }
        if (sampleRateHz >= 64000) {
            return 2;
        }
        if (sampleRateHz >= 48000) {
            return 3;
        }
        if (sampleRateHz >= 44100) {
            return 4;
        }
        if (sampleRateHz >= 32000) {
            return 5;
        }
        if (sampleRateHz >= 24000) {
            return 6;
        }
        if (sampleRateHz >= 22050) {
            return 7;
        }
        if (sampleRateHz >= 16000) {
            return 8;
        }
        if (sampleRateHz >= 12000) {
            return 9;
        }
        if (sampleRateHz >= 11025) {
            return 10;
        }
        return 11;
    }

    private void closeEncoderLocked() {
        if (aacEncoder != null) {
            try {
                aacEncoder.stop();
            } catch (Exception e) {
                Log.w(TAG, "codec stop", e);
            }
            try {
                aacEncoder.release();
            } catch (Exception e) {
                Log.w(TAG, "codec release", e);
            }
            aacEncoder = null;
        }
        if (encodedOut != null) {
            try {
                encodedOut.flush();
                encodedOut.close();
            } catch (IOException e) {
                Log.w(TAG, "out close", e);
            }
            encodedOut = null;
        }
        pcmCarry = new byte[0];
        presentationTimeUs = 0;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        if (a.length == 0) {
            return b;
        }
        if (b.length == 0) {
            return a;
        }
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}

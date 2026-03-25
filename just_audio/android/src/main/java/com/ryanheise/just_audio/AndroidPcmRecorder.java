package com.ryanheise.just_audio;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import androidx.media3.common.C;
import io.flutter.plugin.common.MethodChannel.Result;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * TeeAudioProcessor에서 넘어오는 16-bit PCM을 {@link MediaCodec} AAC 인코더로 넣고, raw AAC에
 * ADTS 헤더를 붙여 스트림으로 저장합니다.
 *
 * <p>
 * Android 10(Q) 이상: 주 볼륨({@link MediaStore#VOLUME_EXTERNAL_PRIMARY}) 공용 Music
 * 아래
 * {@code rainbow_records}에 {@link MediaStore.Audio.Media}로 등록·기록합니다
 * ({@code IS_PENDING}
 * 후 완료 시 해제). 완료 후
 * {@link ContentResolver#notifyChange(Uri, android.database.ContentObserver)}
 * 로 오디오 앱 인덱싱을 돕습니다. 실제 위치는 API 28 이하의 {@code …/Music/rainbow_records/} 파일 경로와
 * 같은 공용 트리입니다. Flutter에는 {@code content://} URI 문자열을 돌려줍니다.
 *
 * <p>
 * 그 이하: 동일하게 공용 {@code Music/rainbow_records}에 파일로 저장합니다. 주(Primary) 외부 저장소
 * 경로는 보통 {@code /storage/emulated/0/Music/rainbow_records/} 형태이며, 파일 관리자의 「내장
 * 메모리 &gt; Music &gt; rainbow_records」와 같은 위치입니다. 저장 완료 시
 * {@link MediaScannerConnection#scanFile(Context, String[], String[], MediaScannerConnection.OnScanCompletedListener)}
 * ({@code audio/mpeg})로 스캔을 요청합니다. 절대 경로를 돌려줍니다. 저장소 권한은 호스트 앱에서
 * 처리합니다.
 *
 * <p>
 * 비트스트림은 AAC(ADTS)이며, 표시용으로 {@code .mp3} / {@code audio/mp3}를 사용합니다.
 *
 * <p>
 * 오디오 스레드에서는 PCM만 복사하고, 인코딩·쓰기는 단일 스레드 실행기에서 수행합니다.
 */
final class AndroidPcmRecorder {
    private static final String TAG = "AndroidPcmRecorder";
    /** {@code adb logcat Record:* *:S} 등으로 저장 경로만 필터할 때 사용 */
    private static final String RECORD_LOG_TAG = "Record";
    private static final String AAC_MIME = "audio/mp4a-latm";
    /** CBR에 가깝게; 예전 AudioRecorder와 동일 계열 */
    private static final int AAC_BIT_RATE = 96000;
    private static final int DEQUEUE_TIMEOUT_US = 10_000;

    private static final String RECORDS_FOLDER = "rainbow_records";

    /**
     * MediaStore {@link MediaStore.MediaColumns#RELATIVE_PATH}용. 플랫폼 문서는 {@code /}
     * 구분을
     * 사용할 것을 권장합니다 ({@code Music/rainbow_records}).
     */
    private static final String RELATIVE_PATH_MUSIC_RAINBOW = Environment.DIRECTORY_MUSIC + "/" + RECORDS_FOLDER;

    /** 미디어 스캔·오디오 앱 인덱싱용 (제안: {@code audio/mpeg}). */
    private static final String SCAN_MIME_AUDIO_MPEG = "audio/mpeg";

    private final Context appContext;
    private final Handler mainHandler;
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor(
            r -> {
                Thread t = new Thread(r, "just_audio-pcm-aac");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    private final Object lock = new Object();

    private volatile boolean recordingDesired;
    /** Base name from Flutter (extension optional). */
    private String recordingFileName;
    /** Absolute path (legacy) or {@code content://} URI string (Q+). */
    private String resultPathOrUri;
    /** Non-null while using MediaStore output on Q+. */
    private Uri mediaStoreUri;
    private BufferedOutputStream encodedOut;
    private MediaCodec aacEncoder;
    /** Microseconds presentation time for queued PCM. */
    private long presentationTimeUs;
    /** Incomplete PCM frame bytes (16-bit interleaved). */
    private byte[] pcmCarry = new byte[0];

    private int lastSampleRateHz;
    private int lastChannelCount;
    private @C.PcmEncoding int lastEncoding;

    AndroidPcmRecorder(Context context, Handler mainHandler) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = mainHandler;
    }

    void onFlush(int sampleRateHz, int channelCount, @C.PcmEncoding int encoding) {
        synchronized (lock) {
            lastSampleRateHz = sampleRateHz;
            lastChannelCount = channelCount;
            lastEncoding = encoding;
            if (recordingDesired && encodedOut == null && recordingFileName != null) {
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

    void startRecording(String fileName, Result result) {
        synchronized (lock) {
            if (recordingDesired) {
                result.error("ALREADY_RECORDING", "Call stopRecord before startRecord again.", null);
                return;
            }
            if (fileName == null || fileName.trim().isEmpty()) {
                result.error("BAD_ARGUMENT", "fileName is required", null);
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
            recordingFileName = fileName.trim();
            resultPathOrUri = null;
            mediaStoreUri = null;
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
                    Uri storeUri = null;
                    boolean fileWasOpened = false;
                    IOException ioError = null;
                    synchronized (lock) {
                        recordingDesired = false;
                        pathOut = resultPathOrUri;
                        storeUri = mediaStoreUri;
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
                        if (ioError != null) {
                            if (storeUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                try {
                                    appContext.getContentResolver().delete(storeUri, null, null);
                                } catch (Exception e) {
                                    Log.w(TAG, "delete failed MediaStore row", e);
                                }
                            } else if (pathOut != null && fileWasOpened) {
                                try {
                                    File f = new File(pathOut);
                                    if (f.exists() && !f.delete()) {
                                        Log.w(TAG, "Could not delete partial recording: " + pathOut);
                                    }
                                } catch (Exception e) {
                                    Log.w(TAG, "delete partial file", e);
                                }
                            }
                        } else if (storeUri != null
                                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                                && fileWasOpened) {
                            try {
                                ContentValues done = new ContentValues();
                                done.put(MediaStore.Audio.Media.IS_PENDING, 0);
                                appContext.getContentResolver().update(storeUri, done, null, null);
                                appContext.getContentResolver().notifyChange(storeUri, null);
                            } catch (Exception e) {
                                Log.w(TAG, "clear IS_PENDING / notifyChange", e);
                            }
                        }
                        recordingFileName = null;
                        resultPathOrUri = null;
                        mediaStoreUri = null;
                    }
                    if (ioError == null
                            && fileWasOpened
                            && pathOut != null
                            && !pathOut.startsWith("content:")) {
                        MediaScannerConnection.scanFile(
                                appContext,
                                new String[] { pathOut },
                                new String[] { SCAN_MIME_AUDIO_MPEG },
                                (path, uri) -> Log.i(
                                        RECORD_LOG_TAG,
                                        "MediaScanner scanFile: path="
                                                + path
                                                + " uri="
                                                + uri));
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
                                if (!opened) {
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
                    Uri delUri;
                    String delLegacyPath;
                    synchronized (lock) {
                        recordingDesired = false;
                        delUri = mediaStoreUri;
                        delLegacyPath = null;
                        if (delUri == null && resultPathOrUri != null) {
                            String p = resultPathOrUri;
                            if (!p.startsWith("content:")) {
                                delLegacyPath = p;
                            }
                        }
                        recordingFileName = null;
                        resultPathOrUri = null;
                        mediaStoreUri = null;
                        closeEncoderLocked();
                    }
                    if (delUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            appContext.getContentResolver().delete(delUri, null, null);
                        } catch (Exception e) {
                            Log.w(TAG, "abort delete MediaStore", e);
                        }
                    } else if (delLegacyPath != null) {
                        try {
                            File f = new File(delLegacyPath);
                            if (f.exists() && !f.delete()) {
                                Log.w(TAG, "abort could not delete: " + delLegacyPath);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "abort delete file", e);
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
        if (encodedOut != null || recordingFileName == null || recordingFileName.isEmpty()) {
            return;
        }
        if (lastSampleRateHz <= 0 || lastChannelCount <= 0) {
            return;
        }
        if (lastEncoding != C.ENCODING_PCM_16BIT) {
            Log.e(TAG, "Recording requires 16-bit PCM.");
            recordingDesired = false;
            recordingFileName = null;
            return;
        }
        Uri insertedUri = null;
        String openedLegacyPath = null;
        try {
            openRecordingOutputLocked();
            insertedUri = mediaStoreUri;
            if (mediaStoreUri == null && resultPathOrUri != null) {
                openedLegacyPath = resultPathOrUri;
            }
            pcmCarry = new byte[0];
            presentationTimeUs = 0;
            aacEncoder = createAndStartAacEncoder();
        } catch (Throwable t) {
            Log.e(TAG, "open encoder", t);
            closeEncoderLocked();
            if (insertedUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    appContext.getContentResolver().delete(insertedUri, null, null);
                } catch (Exception e) {
                    Log.w(TAG, "delete after open failure", e);
                }
            } else if (openedLegacyPath != null) {
                try {
                    File f = new File(openedLegacyPath);
                    if (f.exists() && !f.delete()) {
                        Log.w(TAG, "Could not delete partial file: " + openedLegacyPath);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "delete partial file", e);
                }
            }
            mediaStoreUri = null;
            resultPathOrUri = null;
            recordingDesired = false;
            recordingFileName = null;
        }
    }

    private void openRecordingOutputLocked() throws IOException {
        String display = displayNameWithMp3Extension(recordingFileName);
        ContentResolver cr = appContext.getContentResolver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Audio.Media.DISPLAY_NAME, display);
            values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp3");
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_PATH_MUSIC_RAINBOW);
            values.put(MediaStore.Audio.Media.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
            if (uri == null) {
                throw new IOException("MediaStore insert failed");
            }
            OutputStream os = cr.openOutputStream(uri, "w");
            if (os == null) {
                cr.delete(uri, null, null);
                throw new IOException("openOutputStream failed");
            }
            encodedOut = new BufferedOutputStream(os, 16384);
            mediaStoreUri = uri;
            resultPathOrUri = uri.toString();
            Log.i(
                    RECORD_LOG_TAG,
                    "공용 Music/rainbow_records (primary, MediaStore) uri="
                            + resultPathOrUri
                            + " | logical="
                            + RELATIVE_PATH_MUSIC_RAINBOW
                            + "/"
                            + display);
        } else {
            File dir = getPublicMusicRainbowRecordsDir();
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("Cannot create directory: " + dir.getAbsolutePath());
            }
            File outFile = new File(dir, display);
            encodedOut = new BufferedOutputStream(new FileOutputStream(outFile), 16384);
            mediaStoreUri = null;
            resultPathOrUri = outFile.getAbsolutePath();
            Log.i(
                    RECORD_LOG_TAG,
                    "공용 Music/rainbow_records (primary, 파일) path=" + resultPathOrUri);
        }
    }

    /**
     * 주 외부 저장소의 공용 {@link Environment#DIRECTORY_MUSIC} 아래 {@link #RECORDS_FOLDER}.
     * (일반적으로 파일 관리자의 내장 메모리 &gt; Music &gt; rainbow_records 와 동일.)
     */
    private static File getPublicMusicRainbowRecordsDir() {
        File musicRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        return new File(musicRoot, RECORDS_FOLDER);
    }

    private static String displayNameWithMp3Extension(String base) {
        String b = sanitizeFileName(base);
        if (b.toLowerCase(Locale.US).endsWith(".mp3")) {
            return b;
        }
        return b + ".mp3";
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/]", "_").trim();
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

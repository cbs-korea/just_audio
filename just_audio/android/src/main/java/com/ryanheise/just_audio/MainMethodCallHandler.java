package com.ryanheise.just_audio;

import android.content.Context;
import androidx.annotation.NonNull;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

public class MainMethodCallHandler implements MethodCallHandler {

    private final Context applicationContext;
    private final BinaryMessenger messenger;

    private final Map<String, AudioPlayer> players = new HashMap<>();

    public MainMethodCallHandler(Context applicationContext,
            BinaryMessenger messenger) {
        this.applicationContext = applicationContext;
        this.messenger = messenger;
    }

    @Override
    public void onMethodCall(MethodCall call, @NonNull Result result) {
        switch (call.method) {
            case "init": {
                String id = call.argument("id");
                if (players.containsKey(id)) {
                    result.error("Platform player " + id + " already exists", null, null);
                    break;
                }
                List<Object> rawAudioEffects = call.argument("androidAudioEffects");
                players.put(
                        id,
                        new AudioPlayer(
                                applicationContext,
                                messenger,
                                id,
                                call.argument("audioLoadConfiguration"),
                                rawAudioEffects,
                                call.argument("androidAudioOffloadPreferences"),
                                call.argument("androidOffloadSchedulingEnabled"),
                                call.argument("useLazyPreparation")));
                result.success(null);
                break;
            }
            case "disposePlayer": {
                String id = call.argument("id");
                AudioPlayer player = players.get(id);
                if (player != null) {
                    player.dispose();
                    players.remove(id);
                }
                result.success(new HashMap<String, Object>());
                break;
            }
            case "disposeAllPlayers": {
                dispose();
                result.success(new HashMap<String, Object>());
                break;
            }
            case "startRecord": {
                AudioPlayer player = resolvePlayerForPcmRecord(call.argument("id"), result);
                if (player != null) {
                    player.startPcmRecording((String) call.argument("fileName"), result);
                }
                break;
            }
            case "stopRecord": {
                AudioPlayer player = resolvePlayerForPcmRecord(call.argument("id"), result);
                if (player != null) {
                    player.stopPcmRecording(result);
                }
                break;
            }
            default:
                result.notImplemented();
                break;
        }
    }

    /**
     * If [id] is non-null, that player is used. If null and exactly one player
     * exists, use it
     * (typical single-player app). Otherwise reports an error.
     */
    private AudioPlayer resolvePlayerForPcmRecord(String id, Result result) {
        if (id != null && !id.isEmpty()) {
            AudioPlayer player = players.get(id);
            if (player == null) {
                result.error("NO_PLAYER", "No player with id: " + id, null);
                return null;
            }
            return player;
        }
        if (players.size() == 1) {
            return players.values().iterator().next();
        }
        if (players.isEmpty()) {
            result.error("NO_PLAYER", "No player; ensure AudioPlayer is created first", null);
            return null;
        }
        result.error("NO_PLAYER", "Multiple players: pass id in startRecord/stopRecord arguments", null);
        return null;
    }

    void dispose() {
        for (AudioPlayer player : new ArrayList<AudioPlayer>(players.values())) {
            player.dispose();
        }
        players.clear();
    }
}

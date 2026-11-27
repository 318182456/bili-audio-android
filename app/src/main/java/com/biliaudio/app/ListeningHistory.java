package com.biliaudio.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

final class ListeningHistory {
    private final SharedPreferences data;

    ListeningHistory(Context context) {
        data = context.getSharedPreferences("listening_history", Context.MODE_PRIVATE);
    }

    boolean autoCleanup() {
        return data.getBoolean("auto_cleanup", false);
    }

    void setAutoCleanup(boolean enabled) {
        data.edit().putBoolean("auto_cleanup", enabled).apply();
    }

    int heardThrough(AudioLibrary.Part part, AudioLibrary.Segment segment) {
        return data.getInt(key(part, segment) + "/position", segment.start());
    }

    void save(AudioLibrary.Part part, AudioLibrary.Segment segment, int heardThrough, boolean finished) {
        String key = key(part, segment);
        SharedPreferences.Editor edit = data.edit().putInt(key + "/position", heardThrough);
        if (finished) {
            edit.putBoolean(key + "/finished", true);
        }
        edit.apply();
    }

    boolean allFinished(AudioLibrary.Part part, List<AudioLibrary.Segment> segments) {
        return !segments.isEmpty() && unfinished(part, segments).isEmpty();
    }

    List<AudioLibrary.Segment> unfinished(AudioLibrary.Part part, List<AudioLibrary.Segment> segments) {
        List<AudioLibrary.Segment> remaining = new ArrayList<>();
        for (AudioLibrary.Segment segment : segments) {
            if (!data.getBoolean(key(part, segment) + "/finished", false)) {
                remaining.add(segment);
            }
        }
        return remaining;
    }

    Set<String> partsWithFinishedSegments() {
        Set<String> result = new HashSet<>();
        for (var entry : data.getAll().entrySet()) {
            String name = entry.getKey();
            if (name.endsWith("/finished") && Boolean.TRUE.equals(entry.getValue())) {
                int end = name.indexOf('/', name.indexOf('/') + 1);
                if (end > 0) {
                    result.add(name.substring(0, end));
                }
            }
        }
        return result;
    }

    void finishPart(AudioLibrary.Part part, List<AudioLibrary.Segment> segments) {
        SharedPreferences.Editor edit = data.edit();
        for (AudioLibrary.Segment segment : segments) {
            String key = key(part, segment);
            edit.putInt(key + "/position", segment.end()).putBoolean(key + "/finished", true);
        }
        edit.apply();
    }

    void queueCleanup(AudioLibrary.Part part) {
        data.edit().putBoolean(pendingKey(part), true).apply();
    }

    boolean cleanupPending(AudioLibrary.Part part) {
        return data.getBoolean(pendingKey(part), false);
    }

    void clearCleanup(AudioLibrary.Part part) {
        data.edit().remove(pendingKey(part)).apply();
    }

    private static String key(AudioLibrary.Part part, AudioLibrary.Segment segment) {
        return part.bvid() + "/" + part.cid() + "/" + segment.start() + "-" + segment.end();
    }

    private static String pendingKey(AudioLibrary.Part part) {
        return "cleanup/" + part.bvid() + "/" + part.cid();
    }
}

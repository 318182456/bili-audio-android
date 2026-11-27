package com.biliaudio.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class EpisodeNameSync extends JobService {
    private static final int JOB_ID = 617;
    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final long RETRY = TimeUnit.MINUTES.toMillis(30);
    private Thread worker;

    private record Progress(String source, String generation, int page, String first, String previous,
                            long nextRun, String message) {
        JSONObject encode() throws JSONException {
            return new JSONObject().put("source", source).put("generation", generation).put("page", page)
                    .put("first", first).put("previous", previous).put("next", nextRun).put("message", message);
        }

        static Progress decode(String raw) throws JSONException {
            JSONObject value = new JSONObject(raw);
            return new Progress(value.getString("source"), value.getString("generation"), value.getInt("page"),
                    value.optString("first"), value.optString("previous"), value.optLong("next"),
                    value.optString("message"));
        }
    }

    static synchronized boolean subscribe(Context context, String bvid, String source) throws JSONException {
        Progress previous = progress(context, bvid);
        Progress next = previous != null && previous.source().equals(source)
                ? new Progress(source, UUID.randomUUID().toString(), previous.page(), previous.first(),
                        previous.previous(), 0, "等待同步第 " + previous.page() + " 页")
                : new Progress(source, UUID.randomUUID().toString(), 1, "", "", 0, "等待同步第 1 页");
        save(context, bvid, next);
        return schedule(context, true);
    }

    static synchronized String source(Context context, String bvid) {
        Progress progress = progress(context, bvid);
        return progress == null ? "" : progress.source();
    }

    static synchronized String status(Context context, String bvid) {
        Progress progress = progress(context, bvid);
        return progress == null ? "" : progress.message();
    }

    static synchronized void deleteBook(Context context, AudioLibrary library, String bvid) throws IOException {
        library.removeBook(bvid);
        SharedPreferences data = preferences(context);
        data.edit().remove(bvid).apply();
        if (data.getAll().isEmpty()) {
            context.getSystemService(JobScheduler.class).cancel(JOB_ID);
        }
    }

    static synchronized void afterLogin(Context context, CatalogLoginActivity.Provider provider) {
        boolean changed = false;
        for (String bvid : preferences(context).getAll().keySet()) {
            Progress task = progress(context, bvid);
            if (task != null && provider.allows(android.net.Uri.parse(task.source()))) {
                try {
                    save(context, bvid, new Progress(task.source(), UUID.randomUUID().toString(), task.page(),
                            task.first(), task.previous(), 0, "等待同步第 " + task.page() + " 页"));
                    changed = true;
                } catch (JSONException ignored) { }
            }
        }
        if (changed) {
            schedule(context, true);
        }
    }

    static synchronized void restore(Context context, AudioLibrary library) {
        for (AudioLibrary.Title book : library.titles()) {
            String existing = library.episodeNames(book.bvid()).source();
            if (!existing.isEmpty() && progress(context, book.bvid()) == null) {
                try {
                    String source = EpisodeNames.source(existing);
                    save(context, book.bvid(), new Progress(source, UUID.randomUUID().toString(), 1, "", "", 0,
                            "等待同步第 1 页"));
                } catch (IOException | JSONException ignored) { }
            }
        }
        schedule(context, false);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("episode_name_sync", MODE_PRIVATE);
    }

    private static Progress progress(Context context, String bvid) {
        String raw = preferences(context).getString(bvid, null);
        try {
            return raw == null ? null : Progress.decode(raw);
        } catch (JSONException ignored) {
            return null;
        }
    }

    private static void save(Context context, String bvid, Progress progress) throws JSONException {
        preferences(context).edit().putString(bvid, progress.encode().toString()).apply();
    }

    private static boolean schedule(Context context, boolean replace) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (!replace && scheduler.getPendingJob(JOB_ID) != null) {
            return true;
        }
        long due = Long.MAX_VALUE;
        for (String book : preferences(context).getAll().keySet()) {
            Progress task = progress(context, book);
            if (task != null) {
                due = Math.min(due, task.nextRun());
            }
        }
        if (due == Long.MAX_VALUE) {
            return true;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, EpisodeNameSync.class))
                .setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(Math.max(0, due - System.currentTimeMillis())).build();
        return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
    }

    @Override public boolean onStartJob(JobParameters params) {
        worker = new Thread(() -> {
            try {
                runBatch();
            } finally {
                Thread finished = Thread.currentThread();
                new android.os.Handler(getMainLooper()).post(() -> {
                    if (worker == finished) {
                        worker = null;
                        jobFinished(params, false);
                        synchronized (EpisodeNameSync.class) {
                            schedule(this, true);
                        }
                    }
                });
            }
        }, "episode-name-sync");
        worker.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        return true;
    }

    private void runBatch() {
        AudioLibrary library = new AudioLibrary(this);
        long deadline = SystemClock.elapsedRealtime() + 40_000;
        for (String bvid : preferences(this).getAll().keySet()) {
            Progress task = progress(this, bvid);
            for (int pages = 0; pages < 6 && task != null && task.nextRun() <= System.currentTimeMillis(); pages++) {
                if (Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() > deadline) {
                    return;
                }
                try {
                    EpisodeNames.DirectoryPage page = EpisodeNames.page(this, task.source(), task.page());
                    task = storePage(library, bvid, task, page);
                    Thread.sleep(350);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException | JSONException | RuntimeException error) {
                    synchronized (EpisodeNameSync.class) {
                        if (current(this, bvid, task)) {
                            String message = error.getMessage();
                            try {
                                save(this, bvid, new Progress(task.source(), task.generation(), task.page(),
                                        task.first(), task.previous(), System.currentTimeMillis() + RETRY,
                                        "第 " + task.page() + " 页待重试 · " + (message != null && message.length() < 80
                                                && !message.contains("://") ? message : "暂时无法读取目录")));
                            } catch (JSONException ignored) { }
                        }
                    }
                    break;
                }
            }
        }
    }

    private static boolean current(Context context, String bvid, Progress task) {
        Progress active = progress(context, bvid);
        return !Thread.currentThread().isInterrupted() && active != null && active.equals(task);
    }

    private Progress storePage(AudioLibrary library, String bvid, Progress task, EpisodeNames.DirectoryPage page)
            throws IOException, JSONException {
        synchronized (EpisodeNameSync.class) {
            if (!current(this, bvid, task)) {
                return null;
            }
            if (task.page() > 1 && !page.titles().isEmpty()
                    && (page.marker().equals(task.first()) || page.marker().equals(task.previous()))) {
                throw new IOException("平台重复返回同一页，已保留当前进度。");
            }
            EpisodeNames.Catalog old = library.episodeNames(bvid);
            Map<Integer, String> prior = old.source().equals(task.source()) ? old.names() : Collections.emptyMap();
            EpisodeNames.Catalog merged = EpisodeNames.mergePage(task.source(), prior, page.titles());
            if (!merged.names().isEmpty() && !merged.equals(old)) {
                library.saveEpisodeNames(bvid, merged);
            }
            boolean done = page.titles().isEmpty() || (page.totalPages() > 0 && task.page() >= page.totalPages());
            String message = (done ? "已同步全部 " : "已同步 ") + task.page() + " 页 · " + merged.names().size() + " 个集名";
            Progress next = new Progress(task.source(), task.generation(), done ? 1 : task.page() + 1,
                    done ? "" : task.page() == 1 ? page.marker() : task.first(), done ? "" : page.marker(),
                    done ? System.currentTimeMillis() + DAY : 0, message);
            save(this, bvid, next);
            return next;
        }
    }
}

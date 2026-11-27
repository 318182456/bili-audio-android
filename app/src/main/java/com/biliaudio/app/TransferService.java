package com.biliaudio.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.DocumentsContract;

import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TransferService extends Service {
    static final String DOWNLOAD = "download";
    static final String SUBTITLE = "subtitle";
    static final String TRANSCRIBE = "transcribe";
    static final String COMPACT = "compact";
    static final String CANCEL_COMPACT = "cancel_compact";
    static final String EXPORT = "export";
    static final String PAUSE = "pause";
    static final String DELETE = "delete";
    static final String EXTRA_BVID = "bvid";
    static final String EXTRA_PARTS = "parts";
    static final String EXTRA_SUBTITLE = "with_subtitle";
    static final String EXTRA_COMPACT = "compact_audio";
    private static final String CHANNEL = "audio_tasks";
    private static final int NOTIFICATION = 701;
    private static final int MAX_DOWNLOADS = 3;
    private static final ConcurrentHashMap<String, Status> statuses = new ConcurrentHashMap<>();
    private static final Set<String> deletions = ConcurrentHashMap.newKeySet();

    record Status(String text, boolean running) {
        boolean unfinished() {
            return running || text.startsWith("已暂停") || text.startsWith("任务失败");
        }
    }
    private record Job(String action, String bvid, long cid, boolean withSubtitle,
                       AudioFiles.Compression compression, Uri folder) {
        String key() {
            return bvid + "/" + cid;
        }

        boolean compact() { return compression != null && !compression.cancelled(); }
    }

    private final ArrayDeque<Job> downloads = new ArrayDeque<>();
    private final ArrayDeque<Job> localTasks = new ArrayDeque<>();
    private final Map<Job, Thread> inFlight = new HashMap<>();
    private final Set<Job> pausing = new HashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BilibiliClient client = new BilibiliClient();
    private AudioLibrary library;
    private NotificationManager notifications;
    private Job localJob;
    private volatile Job compressingJob;
    private Thread localWorker;
    private boolean processingLocally;
    private int foregroundType;
    private int lastStartId;
    private volatile boolean destroyed;

    static Status status(AudioLibrary.Part part) {
        return statuses.get(key(part.bvid(), part.cid()));
    }

    private static String key(String bvid, long cid) {
        return bvid + "/" + cid;
    }

    static boolean isDeleting(AudioLibrary.Part part) {
        return deletions.contains(key(part.bvid(), part.cid()));
    }

    static boolean compressionPending(Context context, AudioLibrary.Part part) {
        return context.getSharedPreferences("compression_jobs", MODE_PRIVATE)
                .getBoolean(key(part.bvid(), part.cid()), false);
    }

    private void setCompressionPending(String key, boolean pending) {
        var edit = getSharedPreferences("compression_jobs", MODE_PRIVATE).edit();
        if (pending) {
            edit.putBoolean(key, true);
        } else {
            edit.remove(key);
        }
        edit.apply();
    }

    static void clearInactiveStatus(AudioLibrary.Part part) {
        statuses.computeIfPresent(part.bvid() + "/" + part.cid(),
                (key, value) -> value.running() ? value : null);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        library = new AudioLibrary(this);
        notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(
                new NotificationChannel(CHANNEL, "音频与字幕任务", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        long[] parts = intent.getLongArrayExtra(EXTRA_PARTS);
        String bvid = intent.getStringExtra(EXTRA_BVID);
        if (CANCEL_COMPACT.equals(action) && bvid != null && parts != null) {
            for (long cid : parts) {
                cancelCompression(key(bvid, cid));
            }
            schedule();
            return START_NOT_STICKY;
        }
        if (PAUSE.equals(action)) {
            Set<String> targets = new HashSet<>();
            if (parts != null && bvid != null) {
                for (long cid : parts) {
                    targets.add(key(bvid, cid));
                }
            }
            pauseQueue(targets);
            return START_NOT_STICKY;
        }
        if (parts == null || bvid == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        for (long cid : parts) {
            boolean compact = COMPACT.equals(action) || intent.getBooleanExtra(EXTRA_COMPACT, false);
            Job job = new Job(action, bvid, cid, intent.getBooleanExtra(EXTRA_SUBTITLE, false),
                    compact ? new AudioFiles.Compression() : null, intent.getData());
            Status state = statuses.get(job.key());
            if (state == null || !state.running()) {
                boolean audio = DOWNLOAD.equals(action);
                if (DELETE.equals(action)) {
                    deletions.add(job.key());
                    PlaybackService.closeForDeletion(bvid, cid);
                }
                (audio ? downloads : localTasks).add(job);
                if (compact) {
                    setCompressionPending(job.key(), true);
                }
                String queued = audio ? "等待下载" : DELETE.equals(action) ? "等待删除" : "等待处理";
                statuses.put(job.key(), new Status(queued, true));
            }
        }
        schedule();
        return START_NOT_STICKY;
    }

    private void schedule() {
        while (inFlight.size() < MAX_DOWNLOADS && !downloads.isEmpty()) {
            Job job = downloads.removeFirst();
            Thread worker = new Thread(() -> run(job, true), "bilibili-audio-download");
            inFlight.put(job, worker);
            statuses.put(job.key(), new Status("准备下载", true));
            worker.start();
        }
        if (localJob == null && !localTasks.isEmpty()) {
            Job job = localTasks.removeFirst();
            localJob = job;
            localWorker = new Thread(() -> run(job, false), "bilibili-media-task");
            statuses.put(job.key(), new Status("准备处理", true));
            localWorker.start();
        }
        refreshForeground();
    }

    private void run(Job job, boolean downloading) {
        String outcome;
        AudioLibrary.Part handled = null;
        boolean queueLocal = false;
        boolean deleted = false;
        try {
            AudioLibrary.Title title = library.title(job.bvid());
            AudioLibrary.Part part = null;
            for (AudioLibrary.Part candidate : title.parts()) {
                if (candidate.cid() == job.cid()) {
                    part = candidate;
                    break;
                }
            }
            if (part == null) {
                throw new IOException("未找到这个分 P。");
            }
            handled = part;
            File audio = library.audio(part);
            if (downloading) {
                client.downloadAudio(part, audio, percent -> update(job, "下载音频", percent));
                BilibiliClient.checkInterrupted();
                applyKnownCuts(part);
                queueLocal = job.withSubtitle() || job.compact();
                outcome = "音频已下载";
            } else if (DELETE.equals(job.action())) {
                BilibiliClient.checkInterrupted();
                update(job, "删除本地文件", 0);
                library.deleteMedia(part);
                new ListeningHistory(this).clearCleanup(part);
                deleted = true;
                outcome = "已删除";
            } else if (EXPORT.equals(job.action())) {
                export(job, part, audio);
                outcome = "已导出到所选目录";
            } else {
                BilibiliClient.checkInterrupted();
                if (!audio.isFile()) {
                    throw new IOException("请先下载音频。");
                }
                if (COMPACT.equals(job.action())) {
                    outcome = compactAudio(job, part, audio);
                } else if (TRANSCRIBE.equals(job.action())) {
                    saveOfflineSubtitles(job, part, audio, EpisodeCuts.verifiedChapters(part));
                    outcome = "音频和字幕已就绪";
                } else {
                    boolean subtitles = SUBTITLE.equals(job.action()) || job.withSubtitle();
                    if (subtitles) {
                        updateSubtitles(job, part, audio);
                    }
                    String audioStatus = job.compact() ? compactAudio(job, part, audio) : "音频已下载";
                    outcome = subtitles && !job.compact() ? "音频和字幕已就绪" : audioStatus;
                }
            }
        } catch (IOException | JSONException | RuntimeException | LinkageError error) {
            Status current = statuses.get(job.key());
            String phase = current == null ? "处理任务" : current.text().split(" · ", 2)[0];
            outcome = Thread.currentThread().isInterrupted() ? "已暂停，重新开始可继续"
                    : "任务失败 · " + phase + " · " + message(error);
            if (outcome.startsWith("任务失败")) {
                android.util.Log.w("AudioTransfer", "P" + (handled == null ? "?" : handled.page()) + " " + outcome);
            }
        }
        String result = outcome;
        AudioLibrary.Part completedPart = handled;
        boolean followUp = queueLocal;
        boolean removed = deleted;
        main.post(() -> {
            Thread completedWorker;
            if (downloading) {
                completedWorker = inFlight.remove(job);
            } else {
                completedWorker = localWorker;
                localJob = null;
                localWorker = null;
                processingLocally = false;
            }
            boolean paused = pausing.remove(job) || destroyed
                    || (completedWorker != null && completedWorker.isInterrupted());
            if (DELETE.equals(job.action())) {
                deletions.remove(job.key());
            }
            if (removed) {
                statuses.remove(job.key());
                setCompressionPending(job.key(), false);
            } else if (followUp && !paused && (job.withSubtitle() || job.compact())) {
                localTasks.add(job);
                String pending = job.withSubtitle() ? "等待字幕" : "等待压缩";
                statuses.put(job.key(), new Status("音频已下载 · " + pending, true));
            } else {
                boolean cancelled = job.compression() != null && job.compression().cancelled();
                String status = cancelled && (!downloading || !result.startsWith("任务失败"))
                        && (paused || COMPACT.equals(job.action()) || !job.withSubtitle())
                        ? "已取消压缩" : paused ? "已暂停，重新开始可继续" : result;
                statuses.put(job.key(), new Status(status, false));
                if (completedPart != null) {
                    PlaybackService.cleanFinishedMedia(this, completedPart);
                }
            }
            if (!destroyed) {
                schedule();
            }
        });
    }

    private void applyKnownCuts(AudioLibrary.Part part) throws IOException, JSONException {
        library.resolveCuts(part);
    }

    private void updateSubtitles(Job job, AudioLibrary.Part part, File audio)
            throws IOException, JSONException {
        applyKnownCuts(part);
        if (!library.analysis(part).cues().isEmpty()) {
            return;
        }
        update(job, "获取 B 站字幕", 0);
        BilibiliClient.SubtitleData subtitle;
        try {
            subtitle = client.subtitles(part);
        } catch (IOException | JSONException unavailable) {
            subtitle = new BilibiliClient.SubtitleData("", Collections.emptyList(),
                    Collections.emptyList());
        }
        List<Integer> chapters = subtitle.chapters();
        if (!chapters.isEmpty()) {
            library.updateAnalysis(part, old ->
                    EpisodeCuts.analyze(part, old.source(), old.cues(), chapters, old));
        }
        List<AudioLibrary.Cue> cues = subtitle.cues();
        if (cues.isEmpty()) {
            saveOfflineSubtitles(job, part, audio, chapters);
            return;
        }
        BilibiliClient.checkInterrupted();
        String source = subtitle.source();
        library.updateAnalysis(part, old -> EpisodeCuts.analyze(part, source, cues, chapters, old));
        LocalTranscription.clearCheckpoint(audio);
    }

    private void saveOfflineSubtitles(Job job, AudioLibrary.Part part, File audio, List<Integer> chapters)
            throws IOException, JSONException {
        if (!LocalTranscription.hasModel(this)) {
            update(job, "下载转写模型", 0);
        }
        List<AudioLibrary.Cue> cues = LocalTranscription.transcribe(this, audio, part.duration(),
                percent -> update(job, "下载转写模型", percent), () -> startProcessing(job, "本机转写"),
                percent -> update(job, "本机转写", percent));
        if (cues.isEmpty()) {
            throw new IOException("模型未识别到语音。");
        }
        BilibiliClient.checkInterrupted();
        library.updateAnalysis(part, old -> {
            AudioLibrary.Analysis updated = EpisodeCuts.analyze(part, "本机转写", cues, chapters, old);
            if (!updated.confirmed() && updated.starts().size() < old.starts().size()) {
                return new AudioLibrary.Analysis(updated.source(), updated.cues(), old.starts(), false);
            }
            return updated;
        });
        LocalTranscription.clearCheckpoint(audio);
    }

    private String compactAudio(Job job, AudioLibrary.Part part, File audio) throws IOException {
        compressingJob = job;
        try {
            if (!job.compact()) {
                return "已取消压缩";
            }
            startProcessing(job, "压缩音频");
            long saved = AudioFiles.compact(audio, part.duration(), percent -> update(job, "压缩音频", percent),
                    job.compression());
            setCompressionPending(job.key(), false);
            return saved == 0 ? "音频已是较小体积" : "已压缩 · 省下 "
                    + android.text.format.Formatter.formatShortFileSize(this, saved);
        } finally {
            compressingJob = null;
        }
    }

    private void cancelCompression(String key) {
        boolean found = false;
        for (Job job : downloads) {
            if (job.key().equals(key) && job.compression() != null) {
                found |= job.compression().cancel();
            }
        }
        for (Job job : inFlight.keySet()) {
            if (job.key().equals(key) && job.compression() != null) {
                found |= job.compression().cancel();
            }
        }
        for (var iterator = localTasks.iterator(); iterator.hasNext();) {
            Job job = iterator.next();
            if (job.key().equals(key) && job.compression() != null && job.compression().cancel()) {
                found = true;
                if (!job.withSubtitle()) {
                    iterator.remove();
                    statuses.put(key, new Status("已取消压缩", false));
                }
            }
        }
        if (localJob != null && localJob.key().equals(key) && localJob.compression() != null) {
            if (!localJob.compression().cancel()) {
                return;
            }
            found = true;
            if (compressingJob == localJob && localWorker != null) {
                localWorker.interrupt();
            }
        }
        setCompressionPending(key, false);
        Status status = statuses.get(key);
        if (!found || status == null || !status.running()) {
            statuses.put(key, new Status("已取消压缩", false));
        }
    }

    private void startProcessing(Job job, String stage) {
        update(job, stage, 0);
        if (Build.VERSION.SDK_INT >= 35) {
            main.post(() -> {
                if (!destroyed && localJob == job) {
                    processingLocally = true;
                    refreshForeground();
                }
            });
        }
    }

    private void export(Job job, AudioLibrary.Part part, File audio) throws IOException, JSONException {
        if (job.folder() == null || !audio.isFile()) {
            throw new IOException("请先下载音轨并选择导出目录。");
        }
        Uri folder = DocumentsContract.buildDocumentUriUsingTree(job.folder(),
                DocumentsContract.getTreeDocumentId(job.folder()));
        AudioLibrary.Analysis analysis = library.analysis(part);
        List<AudioLibrary.Segment> segments = AudioLibrary.segments(part, analysis);
        if (part.count() > 1 && !analysis.confirmed()) {
            throw new IOException("请先确认分集切点。");
        }
        File temp = File.createTempFile("episode-", ".m4a", getCacheDir());
        try {
            for (int index = 0; index < segments.size(); index++) {
                AudioLibrary.Segment segment = segments.get(index);
                BilibiliClient.checkInterrupted();
                update(job, "导出第 " + segment.number() + " 集", index * 100 / segments.size());
                long actualStart = AudioFiles.export(audio, temp, segment);
                String filename = part.bvid() + " 第 "
                        + String.format(java.util.Locale.US, "%04d", segment.number()) + " 集";
                Uri audioUri = create(folder, "audio/mp4", filename + ".m4a");
                Uri subtitleUri = null;
                try {
                    try (FileInputStream input = new FileInputStream(temp);
                         OutputStream output = openOutput(audioUri)) {
                        byte[] buffer = new byte[65536];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            BilibiliClient.checkInterrupted();
                            output.write(buffer, 0, count);
                        }
                    }
                    String srt = AudioFiles.srt(analysis.cues(), actualStart, segment.end());
                    if (!srt.isEmpty()) {
                        subtitleUri = create(folder, "application/x-subrip", filename + ".srt");
                        try (OutputStream output = openOutput(subtitleUri)) {
                            output.write(srt.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                } catch (IOException | RuntimeException error) {
                    deleteDocument(audioUri);
                    if (subtitleUri != null) {
                        deleteDocument(subtitleUri);
                    }
                    throw error;
                }
            }
        } finally {
            temp.delete();
        }
    }

    private Uri create(Uri folder, String type, String name) throws IOException {
        Uri output = DocumentsContract.createDocument(getContentResolver(), folder, type, name);
        if (output == null) {
            throw new IOException("无法在所选目录创建文件。");
        }
        return output;
    }

    private OutputStream openOutput(Uri output) throws IOException {
        OutputStream stream = getContentResolver().openOutputStream(output, "w");
        if (stream == null) {
            throw new IOException("无法写入导出文件。");
        }
        return stream;
    }

    private void deleteDocument(Uri uri) {
        try {
            DocumentsContract.deleteDocument(getContentResolver(), uri);
        } catch (IOException | RuntimeException ignored) {
            // A provider may limit deletion even when it allows writing.
        }
    }

    private void update(Job job, String stage, int percent) {
        if (destroyed || Thread.currentThread().isInterrupted()) {
            return;
        }
        String text = stage + " · " + percent + "%";
        statuses.put(job.key(), new Status(text, true));
        if (percent % 10 == 0 || percent == 99) {
            main.post(() -> {
                if (!destroyed && inFlight.isEmpty() && job == localJob) {
                    notifications.notify(NOTIFICATION, notification(text, percent));
                }
            });
        }
    }

    private void refreshForeground() {
        if (inFlight.isEmpty() && localJob == null) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundType = 0;
            stopSelfResult(lastStartId);
            return;
        }
        int type = 0;
        if (!inFlight.isEmpty() || !processingLocally) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        }
        if (processingLocally) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING;
        }
        String text;
        if (inFlight.isEmpty()) {
            text = statuses.get(localJob.key()).text();
        } else {
            text = "正在下载 " + inFlight.size() + " 项";
            if (!downloads.isEmpty()) {
                text += " · 等待 " + downloads.size() + " 项";
            }
            if (localJob != null) {
                text += " · 同时处理字幕或导出";
            }
        }
        Notification notification = notification(text, 0);
        if (foregroundType == type) {
            notifications.notify(NOTIFICATION, notification);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION, notification, type);
        } else {
            startForeground(NOTIFICATION, notification);
        }
        foregroundType = type;
    }

    private Notification notification(String text, int progress) {
        int options = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 41, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), options);
        PendingIntent pause = PendingIntent.getService(this, 42,
                new Intent(this, TransferService.class).setAction(PAUSE), options);
        return new Notification.Builder(this, CHANNEL).setContentTitle("哔哩听单")
                .setContentText(text).setSmallIcon(android.R.drawable.stat_sys_download)
                .setOnlyAlertOnce(true).setOngoing(true).setContentIntent(open)
                .setProgress(100, progress, progress == 0)
                .addAction(new Notification.Action.Builder(null, "暂停", pause).build())
                .build();
    }

    private void pauseQueue(Set<String> targets) {
        downloads.removeIf(job -> pauseWaiting(job, targets));
        localTasks.removeIf(job -> pauseWaiting(job, targets));
        inFlight.forEach((job, worker) -> {
            if (targets.isEmpty() || targets.contains(job.key())) {
                pausing.add(job);
                worker.interrupt();
            }
        });
        if (localWorker != null && (targets.isEmpty() || targets.contains(localJob.key()))) {
            pausing.add(localJob);
            localWorker.interrupt();
        }
        if (!destroyed) {
            refreshForeground();
        }
    }

    private boolean pauseWaiting(Job job, Set<String> targets) {
        if (!targets.isEmpty() && !targets.contains(job.key())) {
            return false;
        }
        if (DELETE.equals(job.action())) {
            deletions.remove(job.key());
        }
        statuses.put(job.key(), new Status("已暂停", false));
        return true;
    }

    @Override
    public void onTimeout(int startId, int foregroundServiceType) {
        pauseQueue(Collections.emptySet());
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        pauseQueue(Collections.emptySet());
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.length() > 150 || message.contains("://")) {
            return "请稍后重试";
        }
        return message;
    }
}

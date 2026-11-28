package com.biliaudio.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public final class PlaybackService extends Service {
    static final String PLAY = "play_local";
    static final String TOGGLE = "toggle_local";
    static final String NEXT = "next_local";
    static final String PREVIOUS = "previous_local";
    static final String EXTRA_PAGE = "cid";
    static final String EXTRA_INDEX = "episode_index";
    static final String EXTRA_PREVIEW = "preview_position";
    private static final String CHANNEL = "local_playback";
    private static final int NOTIFICATION = 702;
    private static final int SEEK_NONE = -1;
    private static final int LISTENING_GAP_MS = 600;
    private static PlaybackService active;

    final class LocalBinder extends Binder {
        PlaybackService service() {
            return PlaybackService.this;
        }
    }

    record LastPlayed(String bvid, long cid, int position) {}

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService captionWorker = Executors.newSingleThreadExecutor();
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            pause(true);
        }
    };

    private AudioLibrary library;
    private ListeningHistory history;
    private SharedPreferences preferences;
    private MediaSession session;
    private MediaMetadata metadata;
    private NotificationManager notifications;
    private AudioManager audioManager;
    private AudioFocusRequest focus;
    private MediaPlayer player;
    private AudioLibrary.Title title;
    private Bitmap cover;
    private AudioLibrary.Part part;
    private AudioLibrary.Analysis analysis;
    private long analysisVersion;
    private CaptionTrack captionTrack;
    private Future<?> captionWork;
    private long captionGeneration;
    private List<AudioLibrary.Segment> segments;
    private int index;
    private int pendingSeek = SEEK_NONE;
    private int ticks;
    private int heardThrough;
    private int lastObservation = SEEK_NONE;
    private boolean prepared;
    private boolean wantsPlay;
    private boolean hasFocus;
    private boolean resumeOnFocus;
    private boolean mix;
    private boolean preview;
    private float volume;
    private float speed;
    private String error = "";

    @Override
    public void onCreate() {
        super.onCreate();
        library = new AudioLibrary(this);
        history = new ListeningHistory(this);
        active = this;
        preferences = getSharedPreferences("playback", MODE_PRIVATE);
        mix = preferences.getBoolean("mix", false);
        volume = preferences.getFloat("volume", 1f);
        speed = preferences.getFloat("speed", 1f);
        audioManager = getSystemService(AudioManager.class);
        notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL, "本地播放", NotificationManager.IMPORTANCE_LOW));
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(this::focusChanged, main).build();
        session = new MediaSession(this, "BiliAudioPlayback");
        session.setSessionActivity(openApp());
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(true); }
            @Override public void onStop() { pause(true); }
            @Override public void onSkipToNext() { skip(1); }
            @Override public void onSkipToPrevious() { skip(-1); }
            @Override public void onSeekTo(long position) { seek((int) Math.min(Integer.MAX_VALUE, position)); }
        });
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(noisy, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (PLAY.equals(action)) {
            startForeground(NOTIFICATION, notification());
            try {
                AudioLibrary.Title requested = library.title(intent.getStringExtra(TransferService.EXTRA_BVID));
                long cid = intent.getLongExtra(EXTRA_PAGE, 0);
                for (AudioLibrary.Part target : requested.parts()) {
                    if (target.cid() == cid) {
                        int previewPosition = intent.getIntExtra(EXTRA_PREVIEW, SEEK_NONE);
                        open(requested, target, intent.getIntExtra(EXTRA_INDEX, -1), previewPosition);
                        return START_NOT_STICKY;
                    }
                }
                throw new IOException("没有找到这个分 P。");
            } catch (IOException | JSONException | RuntimeException exception) {
                fail("无法打开音频。");
            }
        } else if (TOGGLE.equals(action)) {
            toggle();
        } else if (NEXT.equals(action)) {
            skip(1);
        } else if (PREVIOUS.equals(action)) {
            skip(-1);
        }
        return START_NOT_STICKY;
    }

    private void open(AudioLibrary.Title requested, AudioLibrary.Part target, int requestedIndex,
                      int previewPosition) throws IOException, JSONException {
        if (TransferService.isDeleting(target)) {
            throw new IOException("正在删除这个分 P。");
        }
        boolean previewing = previewPosition >= 0;
        AudioLibrary.Analysis loaded = previewing ? library.analysis(target) : library.resolveCuts(target);
        List<AudioLibrary.Segment> local = AudioLibrary.segments(target, loaded);
        int position = previewing ? previewPosition
                : preferences.getInt(positionKey(target.bvid(), target.cid()), 0);
        if (previewing) {
            local = java.util.Collections.singletonList(new AudioLibrary.Segment(
                    0, previewPosition, Math.min(target.duration(), previewPosition + 10000)));
            requestedIndex = 0;
        }
        int selected = requestedIndex < 0 ? indexAt(local, position) : requestedIndex;
        if (selected >= local.size()) {
            selected = local.size() - 1;
        }
        AudioLibrary.Part previous = part;
        saveProgress();
        releasePlayer();
        if (!Objects.equals(artwork(), requested.cover())) {
            cover = null;
        }
        title = requested;
        part = target;
        analysis = loaded;
        analysisVersion = library.analysisVersion(target);
        segments = local;
        index = selected;
        preview = previewing;
        heardThrough = history.heardThrough(part, segment());
        lastObservation = SEEK_NONE;
        if (previous != null && !previous.equals(target)) {
            cleanFinishedMedia(this, previous);
        }
        if (requestedIndex >= 0 || position < segment().start() || position >= segment().end()) {
            position = segment().start();
        }
        error = "";
        pendingSeek = position;
        wantsPlay = true;
        player = new MediaPlayer();
        player.setAudioAttributes(attributes);
        player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        player.setVolume(volume, volume);
        player.setDataSource(library.audio(part).getPath());
        player.setOnPreparedListener(this::onPrepared);
        player.setOnSeekCompleteListener(this::onSeekComplete);
        player.setOnCompletionListener(value -> {
            if (player == value && prepared) {
                advance();
            }
        });
        player.setOnErrorListener((value, what, extra) -> {
            if (player == value) {
                fail("播放音轨时发生错误。");
            }
            return true;
        });
        session.setActive(true);
        refresh(true);
        if (cover == null) {
            String address = artwork();
            CoverImages.load(this, address, image -> {
                if (active == this && player != null && address.equals(artwork()) && cover != image) {
                    cover = image;
                    refresh(true);
                }
            });
        }
        if (!preview) {
            captionTrack();
        }
        player.prepareAsync();
    }

    private void onPrepared(MediaPlayer ready) {
        if (player != ready) {
            return;
        }
        prepared = true;
        if (pendingSeek > 0) {
            ready.seekTo(pendingSeek, MediaPlayer.SEEK_CLOSEST);
        } else {
            pendingSeek = SEEK_NONE;
            if (wantsPlay) {
                resume();
            }
        }
    }

    private void onSeekComplete(MediaPlayer ready) {
        if (player == ready) {
            pendingSeek = SEEK_NONE;
            if (wantsPlay && !playing()) {
                resume();
            } else if (wantsPlay) {
                main.removeCallbacks(tick);
                main.post(tick);
            } else {
                refresh(false);
            }
        }
    }

    void resume() {
        if (player == null) {
            return;
        }
        wantsPlay = true;
        if (!prepared || pendingSeek >= 0) {
            return;
        }
        startForeground(NOTIFICATION, notification());
        if (!mix && !hasFocus) {
            hasFocus = audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!hasFocus) {
                error = "其他应用正在使用音频焦点。";
                pause(true);
                return;
            }
        }
        error = "";
        lastObservation = player.getCurrentPosition();
        player.start();
        applySpeed();
        main.removeCallbacks(tick);
        main.post(tick);
        refresh(true);
    }

    void toggle() {
        if (wantsPlay) {
            pause(true);
        } else {
            resume();
        }
    }

    void closeIfPlaying(AudioLibrary.Part item) {
        if (item.equals(part)) {
            pause(true);
            releasePlayer();
            part = null;
            title = null;
            cover = null;
            analysis = null;
            segments = null;
            session.setActive(false);
            notifications.cancel(NOTIFICATION);
            stopSelf();
        }
    }

    static void closeForDeletion(String bvid, long cid) {
        if (active != null && active.part != null && active.part.bvid().equals(bvid)
                && active.part.cid() == cid) {
            active.closeIfPlaying(active.part);
        }
    }

    static void closeForBookDeletion(String bvid) {
        if (active != null && active.part != null && active.part.bvid().equals(bvid)) {
            active.closeIfPlaying(active.part);
        }
    }

    private void pause(boolean abandonFocus) {
        wantsPlay = false;
        if (playing()) {
            player.pause();
        }
        main.removeCallbacks(tick);
        if (abandonFocus) {
            resumeOnFocus = false;
            dropFocus();
            stopForeground(STOP_FOREGROUND_DETACH);
        }
        saveProgress();
        refresh(part != null);
    }

    private void dropFocus() {
        if (hasFocus) {
            hasFocus = false;
            audioManager.abandonAudioFocusRequest(focus);
        }
    }

    private void focusChanged(int change) {
        if (mix || !hasFocus) {
            return;
        }
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (resumeOnFocus) {
                resumeOnFocus = false;
                resume();
            }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            pause(true);
        } else {
            resumeOnFocus = wantsPlay || resumeOnFocus;
            pause(false);
        }
    }

    void seek(int relativeMillis) {
        if (!prepared) {
            return;
        }
        int target = segment().start() + Math.max(0, Math.min(duration() - 1, relativeMillis));
        lastObservation = SEEK_NONE;
        pendingSeek = target;
        player.seekTo(target, MediaPlayer.SEEK_CLOSEST);
        refresh(false);
    }

    void skip(int direction) {
        if (part == null) {
            return;
        }
        int wanted = index + direction;
        try {
            if (wanted >= 0 && wanted < segments.size()) {
                open(title, part, wanted, SEEK_NONE);
                return;
            }
            if (preview) {
                return;
            }
            int current = title.parts().indexOf(part);
            int adjacent = current + direction;
            if (adjacent >= 0 && adjacent < title.parts().size()) {
                AudioLibrary.Part next = title.parts().get(adjacent);
                if (library.audio(next).isFile() && !TransferService.isDeleting(next)) {
                    int episode = direction > 0 ? 0 : next.count() - 1;
                    open(title, next, episode, SEEK_NONE);
                    return;
                }
            }
            error = direction > 0 ? "没有已下载的下一集。" : "没有已下载的上一集。";
        } catch (IOException | JSONException | RuntimeException exception) {
            fail("无法打开这一集。");
        }
    }

    private void advance() {
        if (!wantsPlay) {
            return;
        }
        if (preview) {
            pause(true);
            seek(0);
            return;
        }
        observeListening();
        int end = Math.min(segment().end(), player.getDuration());
        boolean finished = end - heardThrough <= LISTENING_GAP_MS;
        history.save(part, segment(), heardThrough, finished);
        if (finished && history.autoCleanup() && history.allFinished(part, segments)) {
            history.queueCleanup(part);
        }
        int previousIndex = index;
        AudioLibrary.Part previousPart = part;
        skip(1);
        if (part == previousPart && index == previousIndex && player != null) {
            pause(true);
            if (history.autoCleanup() && history.cleanupPending(previousPart)) {
                closeIfPlaying(previousPart);
                cleanFinishedMedia(this, previousPart);
            } else {
                seek(0);
                saveProgress();
            }
        }
    }

    static void cleanFinishedMedia(Context context, AudioLibrary.Part part) {
        ListeningHistory history = new ListeningHistory(context);
        if (!history.cleanupPending(part)) {
            return;
        }
        if (!history.autoCleanup()) {
            history.clearCleanup(part);
            return;
        }
        if (active != null && part.equals(active.part) && active.player != null) {
            return;
        }
        TransferService.Status transfer = TransferService.status(part);
        if (transfer != null && transfer.running()) {
            return;
        }
        AudioLibrary library = new AudioLibrary(context);
        try {
            if (history.allFinished(part, AudioLibrary.segments(part, library.analysis(part)))) {
                library.deleteMedia(part);
                TransferService.clearInactiveStatus(part);
            }
            history.clearCleanup(part);
        } catch (IOException | JSONException ignored) {
            // Keep the pending cleanup for another attempt.
        }
    }

    void setMix(boolean value) {
        mix = value;
        preferences.edit().putBoolean("mix", value).apply();
        if (mix) {
            resumeOnFocus = false;
            dropFocus();
        } else if (playing()) {
            resume();
        }
    }

    void setVolume(float value) {
        volume = value;
        preferences.edit().putFloat("volume", value).apply();
        if (player != null) {
            player.setVolume(value, value);
        }
    }

    void setSpeed(float value) {
        speed = value;
        preferences.edit().putFloat("speed", value).apply();
        if (playing()) {
            applySpeed();
        }
        refresh(false);
    }

    private void applySpeed() {
        try {
            player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed));
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            error = "当前设备无法使用这个倍速。";
        }
    }

    boolean mix() { return mix; }
    float volume() { return volume; }
    float speed() { return speed; }
    String error() { return error; }
    AudioLibrary.Part part() { return part; }
    String title() { return title == null ? "" : library.displayName(title); }
    String artwork() { return title == null ? "" : title.cover(); }

    void bookRenamed(String bvid) {
        if (part != null && part.bvid().equals(bvid)) {
            refresh(true);
        }
    }
    int episodeNumber() { return part == null ? 0 : segment().number(); }
    boolean playing() { return prepared && player != null && player.isPlaying(); }
    boolean loading() { return player != null && !prepared; }
    int position() { return part == null ? 0 : Math.max(0, absolutePosition() - segment().start()); }
    int sourcePosition() { return absolutePosition(); }

    record CaptionTrack(AudioLibrary.Part part, int start, int end, long version,
                        List<AudioLibrary.Cue> cues, boolean aligning) {}

    CaptionTrack captionTrack() {
        if (part == null) {
            return null;
        }
        reloadCaptions();
        AudioLibrary.Segment clip = segment();
        if (captionTrack == null || !captionTrack.part().equals(part)
                || captionTrack.start() != clip.start() || captionTrack.end() != clip.end()
                || captionTrack.version() != analysisVersion) {
            if (captionWork != null) {
                captionWork.cancel(true);
                captionWork = null;
            }
            List<AudioLibrary.Cue> captions = new ArrayList<>();
            if (analysis != null) {
                for (AudioLibrary.Cue cue : analysis.cues()) {
                    if (cue.end() > clip.start() && cue.start() < clip.end()) {
                        captions.add(cue);
                    }
                }
            }
            long generation = ++captionGeneration;
            boolean aligning = !captions.isEmpty() && player != null;
            List<AudioLibrary.Cue> initialCues = aligning ? Collections.emptyList() : CaptionText.format(captions);
            CaptionTrack initial = new CaptionTrack(part, clip.start(), clip.end(), analysisVersion,
                    initialCues, aligning);
            captionTrack = initial;
            if (aligning) {
                File audio = library.audio(part);
                captionWork = captionWorker.submit(() -> {
                    Consumer<List<AudioLibrary.Cue>> publish = formatted -> main.post(() -> {
                        if (captionGeneration == generation && captionTrack != null) {
                            captionTrack = new CaptionTrack(initial.part(), initial.start(), initial.end(),
                                    initial.version(), formatted, false);
                        }
                    });
                    try {
                        publish.accept(AudioFiles.formatCaptions(audio, captions, publish));
                    } catch (IOException ignored) {
                        // A cancelled scan belongs to an episode that is no longer being opened.
                    }
                });
            }
        }
        return captionTrack;
    }

    int duration() {
        if (part == null) {
            return 0;
        }
        int end = prepared ? Math.min(segment().end(), player.getDuration()) : segment().end();
        return Math.max(0, end - segment().start());
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!playing()) {
                return;
            }
            observeListening();
            if (pendingSeek < 0 && absolutePosition() >= segment().end()) {
                advance();
                return;
            }
            if (++ticks % 30 == 0) {
                captionTrack();
                saveProgress();
                refresh(false);
            } else if (ticks % 7 == 0 && metadata != null
                    && !notificationCaption().equals(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))) {
                refresh(false);
            }
            main.postDelayed(this, 100);
        }
    };

    private void observeListening() {
        if (preview || !prepared || pendingSeek >= 0) {
            return;
        }
        int position = Math.min(segment().end(), player.getCurrentPosition());
        if (lastObservation >= 0 && lastObservation <= heardThrough + LISTENING_GAP_MS
                && position >= lastObservation) {
            heardThrough = Math.max(heardThrough, position);
        }
        lastObservation = position;
    }

    private void reloadCaptions() {
        long version = library.analysisVersion(part);
        if (version != analysisVersion) {
            try {
                analysis = library.analysis(part);
                analysisVersion = version;
            } catch (IOException | JSONException ignored) {
                // Playback continues with the last readable subtitles.
            }
        }
    }

    private AudioLibrary.Segment segment() { return segments.get(index); }
    private int absolutePosition() {
        return pendingSeek >= 0 ? pendingSeek : prepared ? player.getCurrentPosition() : 0;
    }

    private void saveProgress() {
        if (part != null && prepared && !preview) {
            preferences.edit().putString("last_bvid", part.bvid()).putLong("last_cid", part.cid())
                    .putInt(positionKey(part.bvid(), part.cid()), absolutePosition()).apply();
            history.save(part, segment(), heardThrough, false);
        }
    }

    static LastPlayed last(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("playback", MODE_PRIVATE);
        String bvid = prefs.getString("last_bvid", null);
        long cid = prefs.getLong("last_cid", 0);
        return bvid == null ? null : new LastPlayed(bvid, cid, prefs.getInt(positionKey(bvid, cid), 0));
    }

    private static String positionKey(String bvid, long cid) {
        return bvid + "." + cid;
    }

    private static int indexAt(List<AudioLibrary.Segment> segments, int position) {
        for (int at = 0; at < segments.size(); at++) {
            if (position < segments.get(at).end()) {
                return at;
            }
        }
        return 0;
    }

    private String notificationTitle() {
        if (part == null) {
            return getString(R.string.app_name);
        }
        String book = title();
        int number = episodeNumber();
        EpisodeNames.Catalog names = library.episodeNames(part.bvid());
        String episode = number == 0 ? part.label() : names.label(number);
        if (names.names().containsKey(number)) {
            return book.isEmpty() ? episode : episode + " · " + book;
        }
        return book.isEmpty() ? episode : book + " · " + episode;
    }

    private String notificationCaption() {
        if (!prepared || preview || captionTrack == null || captionTrack.cues().isEmpty()) {
            return "";
        }
        int time = absolutePosition();
        List<AudioLibrary.Cue> cues = captionTrack.cues();
        AudioLibrary.Cue cue = cues.get(CaptionText.closestCue(cues, time));
        return time >= cue.start() && time < (long) cue.end() + 350 ? cue.text() : "";
    }

    private void refresh(boolean notify) {
        if (prepared && !preview && captionTrack == null) {
            captionTrack();
        }
        String label = notificationTitle();
        String caption = notificationCaption();
        boolean titleChanged = metadata == null || !label.equals(metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
        boolean captionChanged = metadata == null
                || !caption.equals(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
        int length = duration();
        if (titleChanged || captionChanged
                || length != metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                || cover != metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)) {
            metadata = new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, label)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, caption)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, label)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, caption)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, length)
                    .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover).build();
            session.setMetadata(metadata);
        }
        session.setPlaybackState(new PlaybackState.Builder().setActions(
                PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(loading() ? PlaybackState.STATE_BUFFERING
                                : playing() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        position(), playing() ? speed : 0).build());
        if (notify || (prepared && (captionChanged || titleChanged))) {
            notifications.notify(NOTIFICATION, notification());
        }
    }

    private Notification notification() {
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(notificationTitle())
                .setContentText(notificationCaption()).setLargeIcon(cover).setContentIntent(openApp())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_previous),
                        "上一集", command(PREVIOUS, 1)).build())
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,
                        wantsPlay ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play),
                        wantsPlay ? "暂停" : "播放", command(TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(
                        this, android.R.drawable.ic_media_next), "下一集", command(NEXT, 3)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2)).build();
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent command(String action, int request) {
        return PendingIntent.getService(this, request, new Intent(this, PlaybackService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void fail(String message) {
        error = message;
        wantsPlay = false;
        dropFocus();
        releasePlayer();
        stopForeground(STOP_FOREGROUND_REMOVE);
        refresh(false);
    }

    private void releasePlayer() {
        main.removeCallbacks(tick);
        if (captionWork != null) {
            captionWork.cancel(true);
            captionWork = null;
        }
        captionTrack = null;
        prepared = false;
        pendingSeek = SEEK_NONE;
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        AudioLibrary.Part previous = part;
        saveProgress();
        dropFocus();
        releasePlayer();
        captionWorker.shutdownNow();
        unregisterReceiver(noisy);
        session.release();
        if (active == this) {
            active = null;
        }
        if (previous != null) {
            cleanFinishedMedia(this, previous);
        }
        super.onDestroy();
    }
}

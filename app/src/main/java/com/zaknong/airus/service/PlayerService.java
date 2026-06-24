package com.zaknong.airus.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.os.IBinder;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.lifecycle.LifecycleService;

import com.zaknong.airus.database.AppDatabase;
import com.zaknong.airus.database.entity.Song;
import com.zaknong.airus.engine.AudioEngine;
import com.zaknong.airus.engine.AudioFormatRouter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PlayerService
 *
 * Foreground service utama Airus. Titik koordinasi antara:
 * - UI (via PlayerState LiveData)
 * - MediaSession (via MediaSessionManager)
 * - Notification (via NotificationBuilder)
 * - Audio Focus (via AudioFocusManager)
 * - Audio Engine (via AudioEngine JNI — Tahap 4)
 * - Database (via AppDatabase)
 *
 * Lifecycle:
 *   MainActivity.startService() → onCreate() → onStartCommand()
 *   User stop / task removed   → onDestroy()
 *
 * Binding:
 *   Activity bind ke service via LocalBinder untuk akses langsung
 *   method seperti playSong(), seekTo(), dll — tanpa harus kirim Intent.
 *   Ini lebih efisien dari Intent untuk operasi yang sering dipanggil.
 */
public class PlayerService extends LifecycleService
        implements AudioFocusManager.AudioFocusListener,
        MediaSessionManager.Callback {

    private static final String TAG = "PlayerService";

    private final AudioEngine.EngineCallback engineCallback =
            new AudioEngine.EngineCallback() {

                @Override
                public void onTrackCompleted() {
                    // Sudah di main thread (di-dispatch Handler di AudioEngine.java)
                    skipToNext();
                }

                @Override
                public void onNearingEnd(long remainingMs) {
                    // Preload lagu berikutnya untuk gapless
                    Song next = getNextSong();
                    if (next == null) return;
                    AudioFormatRouter.FormatInfo info =
                            AudioFormatRouter.getFormatInfo(next.filePath);
                    if (info != null) {
                        audioEngine.preloadNextTrack(next.filePath, info.displayName);
                    }
                }

                @Override
                public void onError(int errorCode, String message) {
                    Log.e(TAG, "Engine error " + errorCode + ": " + message);
                    playerState.setPlaybackState(PlayerState.State.ERROR);
                    playerState.setLastError(PlayerState.PlayerError.DECODE_ERROR);
                }

                @Override
                public void onSampleRateChanged(int sampleRate, int bitDepth) {
                    playerState.setActiveSampleRate(sampleRate);
                    playerState.setActiveBitDepth(bitDepth);
                }
            };

    // =========================================================
    // Action Constants — digunakan oleh NotificationBuilder
    // dan BroadcastReceiver di dalam service ini
    // =========================================================

    public static final String ACTION_PLAY                 = "com.zaknong.airus.PLAY";
    public static final String ACTION_PAUSE                = "com.zaknong.airus.PAUSE";
    public static final String ACTION_STOP                 = "com.zaknong.airus.STOP";
    public static final String ACTION_NEXT                 = "com.zaknong.airus.NEXT";
    public static final String ACTION_PREVIOUS             = "com.zaknong.airus.PREVIOUS";
    public static final String ACTION_TOGGLE_BIT_PERFECT   = "com.zaknong.airus.TOGGLE_BP";
    public static final String ACTION_TOGGLE_SHUFFLE       = "com.zaknong.airus.TOGGLE_SHUFFLE";
    public static final String ACTION_CYCLE_REPEAT         = "com.zaknong.airus.CYCLE_REPEAT";
    public static final String ACTION_SEEK_TO              = "com.zaknong.airus.SEEK_TO";

    public static final String EXTRA_POSITION_MS = "position_ms";

    // =========================================================
    // Binder — untuk binding dari Activity
    // =========================================================

    public class LocalBinder extends Binder {
        public PlayerService getService() {
            return PlayerService.this;
        }
    }

    private final IBinder binder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) {
        super.onBind(intent);
        return binder;
    }

    // =========================================================
    // Components
    // =========================================================

    private PlayerState playerState;
    private AudioFocusManager audioFocusManager;
    private MediaSessionManager mediaSessionManager;
    private NotificationBuilder notificationBuilder;
    private AppDatabase database;
    private AudioEngine audioEngine;

    // AudioEngine akan ditambahkan di Tahap 4 (JNI):
    // private AudioEngine audioEngine;

    // =========================================================
    // Queue
    // =========================================================

    private final List<Song> queue         = new ArrayList<>();
    private final List<Song> originalQueue = new ArrayList<>(); // untuk shuffle restore
    private int currentIndex = 0;

    // =========================================================
    // State flags
    // =========================================================

    /** True setelah pause akibat audio focus loss transient.
     *  Digunakan untuk auto-resume saat fokus kembali. */
    private boolean pausedByFocusLoss = false;

    /** True setelah pause akibat headphone dicabut.
     *  TIDAK auto-resume — user harus play manual. */
    private boolean pausedByUnplug = false;

    // =========================================================
    // Position update — update posisi ke PlayerState setiap detik
    // =========================================================

    private final android.os.Handler positionHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable positionUpdater = new Runnable() {
        @Override
        public void run() {
            if (playerState.isPlaying()) {
                long pos = audioEngine.getPositionMs();
                playerState.setPosition(pos);
                mediaSessionManager.setPlaybackState(
                        PlaybackStateCompat.STATE_PLAYING, pos, 1.0f);
                positionHandler.postDelayed(this, 500);
            }
        }
    };

    // =========================================================
    // Lifecycle
    // =========================================================

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "PlayerService onCreate");

        playerState          = PlayerState.getInstance();
        database             = AppDatabase.getInstance(this);
        audioFocusManager    = new AudioFocusManager(this, this);
        mediaSessionManager  = new MediaSessionManager(this, this);
        notificationBuilder  = new NotificationBuilder(
                this,
                mediaSessionManager.getSessionToken());

        // AudioEngine init — Tahap 4:
        // audioEngine = new AudioEngine();
        // audioEngine.initialize();

        registerNotificationReceiver();

        audioEngine = new AudioEngine();
        audioEngine.initialize();
        audioEngine.setEngineCallback(engineCallback);

        // Observe bit-perfect state untuk update notification
        playerState.isBitPerfectActive().observe(this, isBitPerfect -> {
            // Saat bit-perfect di-toggle, update EQ di native engine
            // Tahap 4: audioEngine.setEqEnabled(!isBitPerfect);
            refreshNotification();
            Log.d(TAG, "Bit-perfect: " + isBitPerfect);
        });

        Log.d(TAG, "PlayerService initialized");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        // Start sebagai foreground service dengan empty notification dulu.
        // Notification akan di-update saat lagu mulai main.
        startForeground(
                NotificationBuilder.NOTIFICATION_ID,
                notificationBuilder.build(
                        null, null, false, true, "",
                        PlayerState.RepeatMode.OFF,
                        PlayerState.ShuffleMode.OFF
                )
        );

        Log.d(TAG, "PlayerService started as foreground");

        // START_STICKY: sistem akan restart service jika di-kill,
        // dengan intent null. Ini agar playback bisa dilanjutkan.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "PlayerService onDestroy");

        positionHandler.removeCallbacks(positionUpdater);
        audioFocusManager.release();
        mediaSessionManager.release();
        notificationBuilder.cancel();
        unregisterNotificationReceiver();
        audioEngine.release();
        playerState.reset();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // User swipe app dari recent apps
        // Jika tidak sedang playing, stop service sekalian
        if (!playerState.isPlaying()) {
            stopSelf();
        }
        super.onTaskRemoved(rootIntent);
    }

    // =========================================================
    // Public API — dipanggil dari Activity via LocalBinder
    // =========================================================

    /**
     * Mulai putar daftar lagu dari index tertentu.
     *
     * @param songs      list lagu yang akan jadi queue
     * @param startIndex index lagu yang langsung diputar
     */
    public void playQueue(List<Song> songs, int startIndex) {
        if (songs == null || songs.isEmpty()) return;

        queue.clear();
        queue.addAll(songs);
        originalQueue.clear();
        originalQueue.addAll(songs);
        currentIndex = startIndex;

        playerState.setQueueSize(queue.size());
        playerState.setQueueIndex(currentIndex);

        playCurrent();
    }

    /**
     * Putar satu lagu langsung (queue = hanya lagu ini).
     */
    public void playSong(Song song) {
        List<Song> single = new ArrayList<>();
        single.add(song);
        playQueue(single, 0);
    }

    public void play() {
        if (queue.isEmpty()) return;

        if (playerState.isPaused()) {
            // Resume
            boolean hasFocus = audioFocusManager.requestAudioFocus();
            if (hasFocus) {
                // Tahap 4: audioEngine.resume();
                playerState.setPlaybackState(PlayerState.State.PLAYING);
                mediaSessionManager.setPlaybackState(
                        PlaybackStateCompat.STATE_PLAYING,
                        getCurrentPosition(), 1.0f
                );
                startPositionUpdater();
                refreshNotification();
                pausedByFocusLoss = false;
                pausedByUnplug    = false;
            }
        } else {
            playCurrent();
        }
    }

    public void pause() {
        pause(false, false);
    }

    /**
     * @param byFocusLoss true jika pause karena audio focus loss
     * @param byUnplug    true jika pause karena headphone dicabut
     */
    private void pause(boolean byFocusLoss, boolean byUnplug) {
        // Tahap 4: audioEngine.pause();
        playerState.setPlaybackState(PlayerState.State.PAUSED);
        mediaSessionManager.setPlaybackState(
                PlaybackStateCompat.STATE_PAUSED,
                getCurrentPosition(), 0f
        );
        positionHandler.removeCallbacks(positionUpdater);
        refreshNotification();

        pausedByFocusLoss = byFocusLoss;
        pausedByUnplug    = byUnplug;

        Log.d(TAG, "Paused — byFocusLoss=" + byFocusLoss + " byUnplug=" + byUnplug);
    }

    public void stop() {
        // Tahap 4: audioEngine.stop();
        audioFocusManager.abandonAudioFocus();
        playerState.setPlaybackState(PlayerState.State.IDLE);
        playerState.reset();
        positionHandler.removeCallbacks(positionUpdater);
        stopForeground(true);
        stopSelf();
    }

    public void skipToNext() {
        if (queue.isEmpty()) return;

        PlayerState.RepeatMode repeat = playerState.getRepeatMode().getValue();

        if (repeat == PlayerState.RepeatMode.ONE) {
            // Repeat one: putar ulang lagu yang sama
            seekTo(0);
            return;
        }

        if (currentIndex < queue.size() - 1) {
            currentIndex++;
        } else if (repeat == PlayerState.RepeatMode.ALL) {
            currentIndex = 0;
        } else {
            // Sudah lagu terakhir, tidak repeat
            Log.d(TAG, "Queue ended");
            pause();
            return;
        }

        playerState.setQueueIndex(currentIndex);
        playCurrent();
    }

    public void skipToPrevious() {
        if (queue.isEmpty()) return;

        // Jika sudah lebih dari 3 detik, kembali ke awal lagu dulu
        // (behaviour standard di semua music player)
        if (getCurrentPosition() > 3000) {
            seekTo(0);
            return;
        }

        if (currentIndex > 0) {
            currentIndex--;
        } else {
            currentIndex = 0; // sudah di lagu pertama
        }

        playerState.setQueueIndex(currentIndex);
        playCurrent();
    }

    public void seekTo(long positionMs) {
        // Tahap 4: audioEngine.seekTo(positionMs);
        playerState.setPosition(positionMs);
        mediaSessionManager.setPlaybackState(
                playerState.isPlaying()
                        ? PlaybackStateCompat.STATE_PLAYING
                        : PlaybackStateCompat.STATE_PAUSED,
                positionMs, playerState.isPlaying() ? 1.0f : 0f
        );
    }

    public void toggleBitPerfect() {
        boolean current = Boolean.TRUE.equals(
                playerState.isBitPerfectActive().getValue());
        boolean newValue = !current;

        playerState.setBitPerfectActive(newValue);

        // Jika bit-perfect ON → matikan EQ
        // Jika bit-perfect OFF → EQ bisa aktif
        if (newValue) {
            playerState.setEqActive(false);
            // Tahap 4: audioEngine.setEqEnabled(false);
        }

        Log.d(TAG, "Bit-perfect toggled: " + newValue);
    }

    public void toggleShuffle() {
        PlayerState.ShuffleMode current =
                playerState.getShuffleMode().getValue();

        if (current == PlayerState.ShuffleMode.OFF) {
            // Aktifkan shuffle
            Song currentSong = queue.get(currentIndex);
            Collections.shuffle(queue);
            // Pastikan lagu yang sedang main tetap di posisi 0
            queue.remove(currentSong);
            queue.add(0, currentSong);
            currentIndex = 0;
            playerState.setShuffleMode(PlayerState.ShuffleMode.ON);
        } else {
            // Kembalikan urutan original
            Song currentSong = queue.get(currentIndex);
            queue.clear();
            queue.addAll(originalQueue);
            currentIndex = queue.indexOf(currentSong);
            if (currentIndex < 0) currentIndex = 0;
            playerState.setShuffleMode(PlayerState.ShuffleMode.OFF);
        }

        playerState.setQueueIndex(currentIndex);
        playerState.setQueueSize(queue.size());
    }

    public void cycleRepeatMode() {
        PlayerState.RepeatMode current =
                playerState.getRepeatMode().getValue();
        PlayerState.RepeatMode next;

        switch (current) {
            case OFF: next = PlayerState.RepeatMode.ALL; break;
            case ALL: next = PlayerState.RepeatMode.ONE; break;
            default:  next = PlayerState.RepeatMode.OFF; break;
        }

        playerState.setRepeatMode(next);
        Log.d(TAG, "Repeat mode: " + next);
    }

    // =========================================================
    // Private — Core Playback
    // =========================================================

    /**
     * Putar lagu di currentIndex.
     * Ini pusat dari semua operasi playback.
     */
    private void playCurrent() {
        if (queue.isEmpty() || currentIndex >= queue.size()) return;

        Song song = queue.get(currentIndex);
        Log.d(TAG, "playCurrent: " + song.title + " [" + song.getFormatLabel() + "]");

        // 1. Request audio focus
        boolean hasFocus = audioFocusManager.requestAudioFocus();
        if (!hasFocus) {
            playerState.setLastError(PlayerState.PlayerError.AUDIO_FOCUS_DENIED);
            Log.w(TAG, "Audio focus denied, cannot play");
            return;
        }

        // 2. Update PlayerState
        playerState.setPlaybackState(PlayerState.State.LOADING);
        playerState.setCurrentSong(song);
        playerState.setFormatLabel(song.getFormatLabel());
        playerState.setActiveSampleRate(song.sampleRate);
        playerState.setActiveBitDepth(song.bitDepth);
        playerState.setDuration(song.durationMs);
        playerState.setPosition(0);

        // 3. Tentukan jalur decoder
        AudioFormatRouter.FormatInfo formatInfo =
                AudioFormatRouter.getFormatInfo(song.filePath);

        if (formatInfo == null) {
            playerState.setPlaybackState(PlayerState.State.ERROR);
            playerState.setLastError(PlayerState.PlayerError.FORMAT_UNSUPPORTED);
            Log.e(TAG, "Format tidak didukung: " + song.filePath);
            return;
        }

        // 4. Mulai decode + playback
        if (formatInfo.decoderPath == AudioFormatRouter.DecoderPath.MEDIA_CODEC) {
            // Jalur MediaCodec (MP3, AAC, ALAC, OGG, dll)
            startMediaCodecPlayback(song);
        } else {
            // Jalur Native C++ (FLAC, WAV, DSD, WavPack)
            startNativePlayback(song, formatInfo);
        }

        // 5. Update MediaSession metadata
        loadAlbumArtAndUpdateSession(song);

        // 6. Update database — increment play count di background
        AppDatabase.databaseWriteExecutor.execute(() ->
                database.songDao().incrementPlayCount(
                        song.id, System.currentTimeMillis())
        );

        // 7. Start position updater
        startPositionUpdater();
    }

    /**
     * Playback via MediaCodec — untuk format lossy dan ALAC.
     * Engine: akan menggunakan MediaPlayer atau ExoPlayer sebagai
     * thin wrapper, tapi output-nya tetap dijaga via Oboe.
     *
     * Detail implementasi di Tahap 4.
     */
    private void startMediaCodecPlayback(Song song) {
        audioEngine.setReplayGain(
                song.rgTrackGain, song.rgTrackPeak,
                song.rgAlbumGain, song.rgAlbumPeak,
                false
        );
        boolean ok = audioEngine.playMediaCodec(song.filePath);
        if (!ok) {
            playerState.setPlaybackState(PlayerState.State.ERROR);
            playerState.setLastError(PlayerState.PlayerError.DECODE_ERROR);
            return;
        }
        playerState.setPlaybackState(PlayerState.State.PLAYING);
        mediaSessionManager.setPlaybackState(
                PlaybackStateCompat.STATE_PLAYING, 0, 1.0f);
        refreshNotification();
    }

    /**
     * Playback via Native C++ engine — untuk FLAC, WAV, DSD.
     * Bit-perfect, bypass Android mixer.
     *
     * Detail implementasi di Tahap 4.
     */
    private void startNativePlayback(Song song,
                                     AudioFormatRouter.FormatInfo info) {
        audioEngine.setReplayGain(
                song.rgTrackGain, song.rgTrackPeak,
                song.rgAlbumGain, song.rgAlbumPeak,
                false
        );
        boolean ok = audioEngine.playNative(song.filePath, info.displayName);
        if (!ok) {
            playerState.setPlaybackState(PlayerState.State.ERROR);
            playerState.setLastError(PlayerState.PlayerError.DECODE_ERROR);
            return;
        }
        playerState.setPlaybackState(PlayerState.State.PLAYING);
        mediaSessionManager.setPlaybackState(
                PlaybackStateCompat.STATE_PLAYING, 0, 1.0f);
        refreshNotification();
    }

    /**
     * Load album art secara async lalu update MediaSession dan notification.
     * Dilakukan di background thread karena decode bitmap bisa lambat.
     */
    private void loadAlbumArtAndUpdateSession(Song song) {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            Bitmap art = null;
            if (song.albumArtPath != null) {
                art = BitmapFactory.decodeFile(song.albumArtPath);
            }

            final Bitmap finalArt = art;
            Song currentSong = song;

            // Update MediaSession di main thread
            positionHandler.post(() -> {
                mediaSessionManager.updateMetadata(currentSong, finalArt);
                refreshNotification();
            });
        });
    }

    // =========================================================
    // Notification
    // =========================================================

    private void refreshNotification() {
        Song song           = playerState.getCurrentSong().getValue();
        boolean isPlaying   = playerState.isPlaying();
        boolean isBP        = Boolean.TRUE.equals(
                playerState.isBitPerfectActive().getValue());
        String formatLabel  = playerState.getFormatLabel().getValue();
        PlayerState.RepeatMode repeat   = playerState.getRepeatMode().getValue();
        PlayerState.ShuffleMode shuffle = playerState.getShuffleMode().getValue();

        // Load album art dari cache
        Bitmap art = null;
        if (song != null && song.albumArtPath != null) {
            art = BitmapFactory.decodeFile(song.albumArtPath);
        }

        android.app.Notification notification = notificationBuilder.build(
                song, art, isPlaying, isBP, formatLabel, repeat, shuffle);

        notificationBuilder.notify(notification);
    }

    // =========================================================
    // Position Updater
    // =========================================================

    private void startPositionUpdater() {
        positionHandler.removeCallbacks(positionUpdater);
        positionHandler.post(positionUpdater);
    }

    private long getCurrentPosition() {
        Long pos = playerState.getPosition().getValue();
        return pos != null ? pos : 0L;
    }

    // =========================================================
    // AudioFocusManager.AudioFocusListener
    // =========================================================

    @Override
    public void onAudioFocusGained() {
        // Auto-resume hanya jika pause karena focus loss (bukan unplug)
        if (pausedByFocusLoss && !pausedByUnplug) {
            Log.d(TAG, "Focus regained — auto-resuming");
            play();
        }
    }

    @Override
    public void onAudioFocusLost() {
        // Focus hilang permanen — pause, tidak auto-resume
        if (playerState.isPlaying()) {
            pause(false, false);
        }
    }

    @Override
    public void onAudioFocusLostTransient(boolean canDuck) {
        // Focus hilang sementara — pause dengan flag agar auto-resume nanti
        if (playerState.isPlaying()) {
            pause(true, false);
        }
    }

    @Override
    public void onAudioDeviceUnplugged() {
        // Headphone/DAC dicabut — pause, TIDAK auto-resume
        if (playerState.isPlaying()) {
            pause(false, true);
            Log.d(TAG, "Audio device unplugged — manual resume required");
        }
    }

    // =========================================================
    // MediaSessionManager.Callback
    // =========================================================

    @Override public void onPlay()               { play(); }
    @Override public void onPause()              { pause(); }
    @Override public void onSkipToNext()         { skipToNext(); }
    @Override public void onSkipToPrevious()     { skipToPrevious(); }
    @Override public void onSeekTo(long ms)      { seekTo(ms); }
    @Override public void onStop()               { stop(); }
    @Override public void onToggleBitPerfect()   { toggleBitPerfect(); }
    @Override public void onToggleShuffle()      { toggleShuffle(); }
    @Override public void onCycleRepeat()        { cycleRepeatMode(); }

    // =========================================================
    // BroadcastReceiver — dari tombol notification
    // =========================================================

    private BroadcastReceiver notificationReceiver;

    private void registerNotificationReceiver() {
        notificationReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                switch (intent.getAction()) {
                    case ACTION_PLAY:               play();             break;
                    case ACTION_PAUSE:              pause();            break;
                    case ACTION_STOP:               stop();             break;
                    case ACTION_NEXT:               skipToNext();       break;
                    case ACTION_PREVIOUS:           skipToPrevious();   break;
                    case ACTION_TOGGLE_BIT_PERFECT: toggleBitPerfect(); break;
                    case ACTION_TOGGLE_SHUFFLE:     toggleShuffle();    break;
                    case ACTION_CYCLE_REPEAT:       cycleRepeatMode();  break;
                    case ACTION_SEEK_TO:
                        long pos = intent.getLongExtra(EXTRA_POSITION_MS, 0);
                        seekTo(pos);
                        break;
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PLAY);
        filter.addAction(ACTION_PAUSE);
        filter.addAction(ACTION_STOP);
        filter.addAction(ACTION_NEXT);
        filter.addAction(ACTION_PREVIOUS);
        filter.addAction(ACTION_TOGGLE_BIT_PERFECT);
        filter.addAction(ACTION_TOGGLE_SHUFFLE);
        filter.addAction(ACTION_CYCLE_REPEAT);
        filter.addAction(ACTION_SEEK_TO);

        registerReceiver(notificationReceiver, filter,
                Context.RECEIVER_NOT_EXPORTED);  // API 33+: tidak terima broadcast dari app lain
    }

    private void unregisterNotificationReceiver() {
        if (notificationReceiver != null) {
            try {
                unregisterReceiver(notificationReceiver);
            } catch (IllegalArgumentException e) {
                // Sudah unregister, ignore
            }
        }
    }

    // =========================================================
    // Queue Getters — untuk UI
    // =========================================================

    public List<Song> getQueue()    { return Collections.unmodifiableList(queue); }
    public int getCurrentIndex()    { return currentIndex; }
    public Song getCurrentSong()    {
        if (queue.isEmpty() || currentIndex >= queue.size()) return null;
        return queue.get(currentIndex);
    }
    private Song getNextSong() {
        if (queue.isEmpty()) return null;
        PlayerState.RepeatMode repeat =
                playerState.getRepeatMode().getValue();
        if (repeat == PlayerState.RepeatMode.ONE) {
            return queue.get(currentIndex);
        }
        int nextIndex = currentIndex + 1;
        if (nextIndex < queue.size()) return queue.get(nextIndex);
        if (repeat == PlayerState.RepeatMode.ALL) return queue.get(0);
        return null;
    }
    public AudioEngine getAudioEngine() {
        return audioEngine;
    }
}

package com.zaknong.airus.service;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.zaknong.airus.database.entity.Song;

/**
 * PlayerState — Single source of truth untuk kondisi playback.
 *
 * Singleton. Semua komponen (UI, Service, ViewModel) observe LiveData
 * di sini, tidak ada yang simpan state sendiri-sendiri.
 *
 * Kenapa singleton dan bukan ViewModel?
 * Karena PlayerService punya lifecycle berbeda dari Activity/Fragment.
 * Service bisa hidup saat Activity sudah mati. LiveData di ViewModel
 * akan di-clear saat Activity destroyed. PlayerState tetap hidup
 * selama Service hidup.
 */
public class PlayerState {

    // --- Singleton ---
    private static volatile PlayerState INSTANCE;
    public static PlayerState getInstance() {
        if (INSTANCE == null) {
            synchronized (PlayerState.class) {
                if (INSTANCE == null) INSTANCE = new PlayerState();
            }
        }
        return INSTANCE;
    }
    private PlayerState() {}

    // =========================================================
    // Playback State
    // =========================================================

    public enum State {
        IDLE,       // belum ada lagu dipilih
        LOADING,    // sedang buffering/decode awal
        PLAYING,
        PAUSED,
        ERROR
    }

    private final MutableLiveData<State> playbackState =
            new MutableLiveData<>(State.IDLE);

    private final MutableLiveData<Song> currentSong =
            new MutableLiveData<>(null);

    /** Posisi playback dalam milidetik */
    private final MutableLiveData<Long> position =
            new MutableLiveData<>(0L);

    /** Durasi lagu aktif dalam milidetik */
    private final MutableLiveData<Long> duration =
            new MutableLiveData<>(0L);

    // =========================================================
    // Audio Engine State
    // =========================================================

    /** True jika bit-perfect aktif (EQ bypass ON) */
    private final MutableLiveData<Boolean> bitPerfectActive =
            new MutableLiveData<>(true);

    /** True jika EQ sedang aktif */
    private final MutableLiveData<Boolean> eqActive =
            new MutableLiveData<>(false);

    /** Nama format yang ditampilkan di UI: "FLAC 24/96", "DSD128", "MP3 320" */
    private final MutableLiveData<String> formatLabel =
            new MutableLiveData<>("");

    /** Sample rate aktif dalam Hz, untuk ditampilkan di overlay info */
    private final MutableLiveData<Integer> activeSampleRate =
            new MutableLiveData<>(0);

    /** Bit depth aktif */
    private final MutableLiveData<Integer> activeBitDepth =
            new MutableLiveData<>(0);

    /** True jika USB DAC terhubung */
    private final MutableLiveData<Boolean> usbDacConnected =
            new MutableLiveData<>(false);

    /** Nama DAC yang terhubung, null jika tidak ada */
    private final MutableLiveData<String> usbDacName =
            new MutableLiveData<>(null);

    // =========================================================
    // Queue State
    // =========================================================

    public enum RepeatMode { OFF, ONE, ALL }
    public enum ShuffleMode { OFF, ON }

    private final MutableLiveData<RepeatMode> repeatMode =
            new MutableLiveData<>(RepeatMode.OFF);

    private final MutableLiveData<ShuffleMode> shuffleMode =
            new MutableLiveData<>(ShuffleMode.OFF);

    /** Index lagu di queue */
    private final MutableLiveData<Integer> queueIndex =
            new MutableLiveData<>(0);

    /** Total lagu di queue */
    private final MutableLiveData<Integer> queueSize =
            new MutableLiveData<>(0);

    private final MutableLiveData<java.util.List<Song>> queue =
            new MutableLiveData<>(new java.util.ArrayList<>());

    // =========================================================
    // Error State
    // =========================================================

    public enum PlayerError {
        NONE,
        FILE_NOT_FOUND,
        FORMAT_UNSUPPORTED,
        DECODE_ERROR,
        USB_DAC_DISCONNECTED,
        AUDIO_FOCUS_DENIED,
        SAMPLE_RATE_MISMATCH   // untuk warning gapless
    }

    private final MutableLiveData<PlayerError> lastError =
            new MutableLiveData<>(PlayerError.NONE);

    // =========================================================
    // Public Getters (LiveData — untuk di-observe)
    // =========================================================

    public LiveData<State> getPlaybackState()     { return playbackState; }
    public LiveData<Song>  getCurrentSong()        { return currentSong; }
    public LiveData<Long>  getPosition()           { return position; }
    public LiveData<Long>  getDuration()           { return duration; }
    public LiveData<Boolean> isBitPerfectActive()  { return bitPerfectActive; }
    public LiveData<Boolean> isEqActive()          { return eqActive; }
    public LiveData<String>  getFormatLabel()      { return formatLabel; }
    public LiveData<Integer> getActiveSampleRate() { return activeSampleRate; }
    public LiveData<Integer> getActiveBitDepth()   { return activeBitDepth; }
    public LiveData<Boolean> isUsbDacConnected()   { return usbDacConnected; }
    public LiveData<String>  getUsbDacName()       { return usbDacName; }
    public LiveData<RepeatMode>  getRepeatMode()   { return repeatMode; }
    public LiveData<ShuffleMode> getShuffleMode()  { return shuffleMode; }
    public LiveData<Integer> getQueueIndex()       { return queueIndex; }
    public LiveData<Integer> getQueueSize()        { return queueSize; }
    public LiveData<java.util.List<Song>> getQueue() { return queue; }
    public LiveData<PlayerError> getLastError()    { return lastError; }

    // =========================================================
    // Setters — hanya boleh dipanggil dari PlayerService
    // (bukan dari UI langsung!)
    // =========================================================

    public void setPlaybackState(State state)   { playbackState.postValue(state); }
    public void setCurrentSong(Song song)        { currentSong.postValue(song); }
    public void setPosition(long ms)             { position.postValue(ms); }
    public void setDuration(long ms)             { duration.postValue(ms); }
    public void setBitPerfectActive(boolean v)   { bitPerfectActive.postValue(v); }
    public void setEqActive(boolean v)           { eqActive.postValue(v); }
    public void setFormatLabel(String label)     { formatLabel.postValue(label); }
    public void setActiveSampleRate(int hz)      { activeSampleRate.postValue(hz); }
    public void setActiveBitDepth(int bits)      { activeBitDepth.postValue(bits); }
    public void setUsbDacConnected(boolean v)    { usbDacConnected.postValue(v); }
    public void setUsbDacName(String name)       { usbDacName.postValue(name); }
    public void setRepeatMode(RepeatMode mode)   { repeatMode.postValue(mode); }
    public void setShuffleMode(ShuffleMode mode) { shuffleMode.postValue(mode); }
    public void setQueueIndex(int idx)           { queueIndex.postValue(idx); }
    public void setQueueSize(int size)           { queueSize.postValue(size); }
    public void setQueue(java.util.List<Song> q) { queue.postValue(q); }
    public void setLastError(PlayerError error)  { lastError.postValue(error); }

    // =========================================================
    // Convenience helpers
    // =========================================================

    public boolean isPlaying() {
        return playbackState.getValue() == State.PLAYING;
    }

    public boolean isPaused() {
        return playbackState.getValue() == State.PAUSED;
    }

    /**
     * Reset semua state ke kondisi awal.
     * Dipanggil saat PlayerService di-destroy.
     */
    public void reset() {
        playbackState.postValue(State.IDLE);
        currentSong.postValue(null);
        position.postValue(0L);
        duration.postValue(0L);
        formatLabel.postValue("");
        activeSampleRate.postValue(0);
        activeBitDepth.postValue(0);
        lastError.postValue(PlayerError.NONE);
    }
}
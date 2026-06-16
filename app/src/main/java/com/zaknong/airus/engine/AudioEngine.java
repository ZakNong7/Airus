package com.zaknong.airus.engine;

import android.util.Log;

/**
 * AudioEngine
 *
 * JNI wrapper — jembatan antara Java (PlayerService) dan
 * native C++ engine (Oboe + decoder).
 *
 * Semua method di sini hanya forward call ke native.
 * Tidak ada logika di sini — logika ada di C++.
 *
 * Cara kerja JNI:
 * Java memanggil method native → JVM lookup fungsi C++ dengan
 * nama konvensi: Java_com_zaknong_airus_engine_AudioEngine_methodName
 * → C++ jalankan → return ke Java.
 *
 * Loading native library:
 * static block di bawah load "harmonia_engine.so" yang di-compile
 * CMake. Nama .so = nama target di CMakeLists.txt.
 */
public class AudioEngine {

    private static final String TAG = "AudioEngine";

    // Load native library saat class pertama kali di-load
    static {
        System.loadLibrary("airus_engine");
    }

    // =========================================================
    // Lifecycle
    // =========================================================

    /**
     * Inisialisasi Oboe stream dan allocate buffer.
     * Dipanggil sekali dari PlayerService.onCreate().
     *
     * @return true jika berhasil
     */
    public native boolean initialize();

    /**
     * Release semua resource native.
     * Dipanggil dari PlayerService.onDestroy().
     * Setelah ini, instance AudioEngine tidak boleh dipakai lagi.
     */
    public native void release();

    // =========================================================
    // Playback — Native Path (FLAC, WAV, DSD)
    // =========================================================

    /**
     * Mulai decode dan putar file via native decoder.
     * Bit-perfect: bypass Android mixer sepenuhnya.
     *
     * @param filePath  path absolut file audio
     * @param format    "FLAC", "WAV", "DSD64", "DSD128", dll
     * @return true jika berhasil mulai
     */
    public native boolean playNative(String filePath, String format);

    // =========================================================
    // Playback — MediaCodec Path (MP3, AAC, ALAC, OGG)
    // =========================================================

    /**
     * Mulai decode via MediaCodec dan routing ke Oboe output stream.
     * Untuk format lossy dan ALAC yang lebih efisien via hardware decoder.
     *
     * @param filePath  path absolut file audio
     * @return true jika berhasil mulai
     */
    public native boolean playMediaCodec(String filePath);

    // =========================================================
    // Transport Controls
    // =========================================================

    public native void pause();
    public native void resume();
    public native void stop();

    /**
     * Seek ke posisi tertentu.
     * @param positionMs posisi dalam milidetik
     */
    public native void seekTo(long positionMs);

    /**
     * Posisi playback saat ini dalam milidetik.
     * Dipanggil setiap 500ms oleh positionUpdater di PlayerService.
     */
    public native long getPositionMs();

    /**
     * Durasi total lagu yang sedang main dalam milidetik.
     * Biasanya sama dengan Song.durationMs dari database,
     * tapi nilai dari engine lebih akurat (dari header file langsung).
     */
    public native long getDurationMs();

    // =========================================================
    // EQ Controls
    // =========================================================

    /**
     * Enable atau disable EQ processing.
     * Saat disable (bit-perfect mode), sinyal tidak disentuh sama sekali.
     *
     * @param enabled true = EQ aktif, false = bypass (bit-perfect)
     */
    public native void setEqEnabled(boolean enabled);

    /**
     * Set gain untuk satu band EQ.
     *
     * @param bandIndex  0-9 (10 band)
     * @param frequencyHz center frequency band ini
     * @param gainDb     gain dalam dB (-12.0 hingga +12.0)
     * @param q          Q factor (0.1 hingga 10.0), menentukan lebar band
     */
    public native void setEqBand(int bandIndex, float frequencyHz,
                                 float gainDb, float q);

    /**
     * Set semua band sekaligus dari preset.
     * Lebih efisien dari setEqBand() 10x untuk load preset.
     *
     * @param frequencies array 10 float: center freq tiap band
     * @param gains       array 10 float: gain dB tiap band
     * @param qFactors    array 10 float: Q factor tiap band
     * @param preampDb    global preamp gain (untuk cegah clipping)
     */
    public native void setEqPreset(float[] frequencies, float[] gains,
                                   float[] qFactors, float preampDb);

    // =========================================================
    // ReplayGain
    // =========================================================

    /**
     * Set ReplayGain untuk lagu yang sedang/akan main.
     * Dipanggil dari PlayerService.playCurrent() sebelum playback.
     *
     * @param trackGainDb  gain track dalam dB (Float.NaN = tidak ada tag)
     * @param trackPeak    peak level track (Float.NaN = tidak ada tag)
     * @param albumGainDb  gain album dalam dB (Float.NaN = tidak ada tag)
     * @param albumPeak    peak level album
     * @param useAlbumMode true = album mode, false = track mode
     */
    public native void setReplayGain(float trackGainDb, float trackPeak,
                                     float albumGainDb, float albumPeak,
                                     boolean useAlbumMode);

    // =========================================================
    // Gapless
    // =========================================================

    /**
     * Pre-decode lagu berikutnya ke buffer gapless.
     * Dipanggil dari PlayerService saat lagu aktif tersisa ~5 detik.
     *
     * @param filePath path file lagu berikutnya
     * @param format   format lagu berikutnya
     */
    public native void preloadNextTrack(String filePath, String format);

    /**
     * Batalkan pre-decode yang sedang berjalan.
     * Dipanggil jika user skip sebelum pre-decode selesai.
     */
    public native void cancelPreload();

    // =========================================================
    // USB DAC
    // =========================================================

    /**
     * Buka koneksi eksklusif ke USB DAC.
     *
     * @param usbDevicePath path device USB: "/dev/bus/usb/001/002"
     * @param vendorId      USB vendor ID
     * @param productId     USB product ID
     * @return true jika berhasil
     */
    public native boolean openUsbDac(String usbDevicePath,
                                     int vendorId, int productId);

    /**
     * Tutup koneksi USB DAC — kembali ke output internal.
     */
    public native void closeUsbDac();

    /**
     * Set volume hardware DAC (jika DAC support).
     * @param volume 0.0f (mute) hingga 1.0f (max)
     */
    public native void setHardwareVolume(float volume);

    // =========================================================
    // Crossfeed
    // =========================================================

    /**
     * Toggle BS2B crossfeed.
     * @param enabled true = aktif
     * @param cutFreq cut frequency Hz (default 700)
     * @param feed    feed level 0.0-1.0 (default 0.45)
     */
    public native void setCrossfeed(boolean enabled,
                                    float cutFreq, float feed);

    // =========================================================
    // Callbacks dari C++ → Java
    // (dipanggil dari native thread, harus thread-safe)
    // =========================================================

    /**
     * Dipanggil native engine saat lagu selesai diputar.
     * PlayerService perlu skip ke lagu berikutnya.
     */
    @SuppressWarnings("unused") // dipanggil dari JNI
    private void onTrackCompleted() {
        Log.d(TAG, "onTrackCompleted dari native");
        if (completionListener != null) {
            // Post ke main thread — native callback dari audio thread
            android.os.Handler mainHandler =
                    new android.os.Handler(android.os.Looper.getMainLooper());
            mainHandler.post(() -> completionListener.onTrackCompleted());
        }
    }

    /**
     * Dipanggil native engine saat tersisa N milidetik.
     * Trigger pre-decode lagu berikutnya untuk gapless.
     */
    @SuppressWarnings("unused") // dipanggil dari JNI
    private void onNearingEnd(long remainingMs) {
        Log.d(TAG, "onNearingEnd: " + remainingMs + "ms remaining");
        if (completionListener != null) {
            android.os.Handler mainHandler =
                    new android.os.Handler(android.os.Looper.getMainLooper());
            mainHandler.post(() -> completionListener.onNearingEnd(remainingMs));
        }
    }

    /**
     * Dipanggil native engine saat terjadi error decode/playback.
     */
    @SuppressWarnings("unused") // dipanggil dari JNI
    private void onError(int errorCode, String message) {
        Log.e(TAG, "Native error " + errorCode + ": " + message);
        if (completionListener != null) {
            android.os.Handler mainHandler =
                    new android.os.Handler(android.os.Looper.getMainLooper());
            mainHandler.post(() -> completionListener.onError(errorCode, message));
        }
    }

    /**
     * Dipanggil saat sample rate stream berubah (untuk gapless antar lagu beda SR).
     */
    @SuppressWarnings("unused") // dipanggil dari JNI
    private void onSampleRateChanged(int newSampleRate, int newBitDepth) {
        Log.d(TAG, "Sample rate changed: " + newSampleRate + "Hz / " + newBitDepth + "bit");
        if (completionListener != null) {
            android.os.Handler mainHandler =
                    new android.os.Handler(android.os.Looper.getMainLooper());
            mainHandler.post(() ->
                    completionListener.onSampleRateChanged(newSampleRate, newBitDepth));
        }
    }

    // =========================================================
    // Callback interface
    // =========================================================

    public interface EngineCallback {
        void onTrackCompleted();
        void onNearingEnd(long remainingMs);
        void onError(int errorCode, String message);
        void onSampleRateChanged(int sampleRate, int bitDepth);
    }

    private EngineCallback completionListener;

    public void setEngineCallback(EngineCallback listener) {
        this.completionListener = listener;
    }
}
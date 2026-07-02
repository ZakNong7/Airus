package com.zaknong.airus.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.util.Log;

/**
 * AudioFocusManager
 *
 * Menangani dua hal kritis:
 *
 * 1. Audio Focus — agar Airus otomatis pause saat:
 *    - Ada telepon masuk
 *    - Aplikasi lain (YouTube, Spotify, dll) minta fokus audio
 *    - Notifikasi berbunyi
 *
 * 2. Headphone/DAC Disconnect — agar Airus otomatis pause saat:
 *    - Kabel headphone dicabut
 *    - USB DAC dicabut (ACTION_USB_DEVICE_DETACHED)
 *    - Bluetooth headphone disconnect
 *
 * Cara pakai:
 *   AudioFocusManager manager = new AudioFocusManager(context, listener);
 *   manager.requestAudioFocus();  // sebelum mulai playback
 *   manager.abandonAudioFocus(); // saat stop playback atau onDestroy
 */
public class AudioFocusManager {

    private static final String TAG = "AudioFocusManager";

    /**
     * Callback yang dikirim ke PlayerService.
     */
    public interface AudioFocusListener {
        /** Boleh mulai/lanjutkan playback */
        void onAudioFocusGained();
        /** Harus pause playback */
        void onAudioFocusLost();
        /**
         * Fokus hilang sementara (telepon masuk, dll).
         * Bisa pause atau kecilkan volume (ducking).
         * @param canDuck true jika boleh kecilkan volume saja (bukan harus pause)
         */
        void onAudioFocusLostTransient(boolean canDuck);
        /** Headphone/DAC dicabut — harus pause dan jangan auto-resume */
        void onAudioDeviceUnplugged();
    }

    private final Context context;
    private final AudioManager audioManager;
    private final AudioFocusListener listener;
    private AudioFocusRequest focusRequest;   // API 26+
    private boolean hasFocus = false;

    // =========================================================
    // Constructor
    // =========================================================
    public AudioFocusManager(Context context, AudioFocusListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        registerNoisyReceiver();
    }

    // =========================================================
    // Audio Focus Request
    // =========================================================

    /**
     * Minta audio focus ke sistem Android.
     * Harus dipanggil sebelum memulai playback.
     *
     * @return true jika focus berhasil didapat, false jika tidak
     */
    public boolean requestAudioFocus() {
        int result;

        // API 26+ menggunakan AudioFocusRequest builder
        AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(false) // Handle ducking manually
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build();

        result = audioManager.requestAudioFocus(focusRequest);

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            hasFocus = true;
            Log.d(TAG, "Audio focus granted");
            return true;
        } else if (result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
            // Fokus akan datang nanti (saat telepon selesai, dll)
            // FocusChangeListener akan handle ini
            Log.d(TAG, "Audio focus request delayed");
            return false;
        } else {
            hasFocus = false;
            Log.w(TAG, "Audio focus request failed");
            return false;
        }
    }

    /**
     * Lepas audio focus. Panggil saat:
     * - User menekan stop
     * - PlayerService di-destroy
     */
    public void abandonAudioFocus() {
        if (hasFocus) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            hasFocus = false;
            Log.d(TAG, "Audio focus abandoned");
        }
    }

    public boolean hasFocus() {
        return hasFocus;
    }

    // =========================================================
    // Audio Focus Change Listener
    // =========================================================
    private final AudioManager.OnAudioFocusChangeListener focusChangeListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                    switch (focusChange) {

                        case AudioManager.AUDIOFOCUS_GAIN:
                            // Fokus kembali (setelah telepon selesai, dll)
                            hasFocus = true;
                            Log.d(TAG, "Focus GAIN — resume playback");
                            listener.onAudioFocusGained();
                            break;

                        case AudioManager.AUDIOFOCUS_LOSS:
                            // Fokus hilang permanen (aplikasi lain ambil alih)
                            // Contoh: user buka YouTube → Airus harus pause dan tidak auto-resume
                            hasFocus = false;
                            Log.d(TAG, "Focus LOSS permanent — pause playback");
                            listener.onAudioFocusLost();
                            break;

                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            // Fokus hilang sementara — harus pause
                            // Contoh: ada telepon masuk, Google Assistant aktif
                            hasFocus = false;
                            Log.d(TAG, "Focus LOSS transient — pause playback");
                            listener.onAudioFocusLostTransient(false);
                            break;

                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            // Jika mode normal (suara aktif), maka duck. Jika getar/silent, abaikan.
                            int ringerMode = audioManager.getRingerMode();
                            if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                                Log.d(TAG, "Focus LOSS transient (can duck) — ducking volume");
                                listener.onAudioFocusLostTransient(true);
                            } else {
                                Log.d(TAG, "Focus LOSS transient (can duck) — silent mode, ignoring");
                                // Tetap anggap punya fokus penuh
                                hasFocus = true;
                                listener.onAudioFocusGained();
                            }
                            break;

                        default:
                            Log.w(TAG, "Unknown audio focus change: " + focusChange);
                            break;
                    }
                }
            };

    // =========================================================
    // Noisy Receiver — Deteksi cabut headphone/DAC
    // =========================================================

    /**
     * ACTION_AUDIO_BECOMING_NOISY dikirim Android saat:
     * - Headphone 3.5mm dicabut
     * - Bluetooth headphone disconnect
     * - USB DAC dicabut (di beberapa device)
     *
     * Nama "BECOMING_NOISY" = audio akan keluar dari speaker (lebih berisik/noisy)
     * karena output berpindah dari headphone ke speaker internal.
     */
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                Log.d(TAG, "Audio device unplugged (BECOMING_NOISY) — pausing");
                listener.onAudioDeviceUnplugged();
            }
        }
    };

    private void registerNoisyReceiver() {
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        context.registerReceiver(noisyReceiver, filter);
        Log.d(TAG, "Noisy receiver registered");
    }

    /**
     * WAJIB dipanggil dari PlayerService.onDestroy() untuk menghindari memory leak!
     */
    public void release() {
        abandonAudioFocus();
        try {
            context.unregisterReceiver(noisyReceiver);
            Log.d(TAG, "Noisy receiver unregistered");
        } catch (IllegalArgumentException e) {
            // Receiver belum terdaftar, ignore
        }
    }
}

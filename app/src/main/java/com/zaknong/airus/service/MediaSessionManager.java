package com.zaknong.airus.service;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import com.zaknong.airus.database.entity.Song;
import com.zaknong.airus.ui.MainActivity;

/**
 * MediaSessionManager
 *
 * Menangani MediaSession — jembatan antara PlayerService dan sistem Android untuk:
 * - Kontrol dari lock screen (play/pause/next/prev)
 * - Kontrol dari headphone button (single/double/triple tap)
 * - Kontrol dari Android Auto dan WearOS
 * - Metadata yang tampil di notification, lock screen, dan status bar
 *
 * Kenapa MediaSessionCompat dan bukan MediaSession langsung?
 * Compat memberi kita backward compatibility sampai API 21 tanpa
 * kode if/else per versi Android.
 */
public class MediaSessionManager {

    private static final String TAG = "MediaSessionManager";
    private static final String SESSION_TAG = "AirusMediaSession";

    private final Context context;
    private final MediaSessionCompat mediaSession;
    private final Callback callback;

    /**
     * Callback ke PlayerService — semua aksi dari luar (headphone, lock screen,
     * Android Auto) masuk lewat sini.
     */
    public interface Callback {
        void onPlay();
        void onPause();
        void onSkipToNext();
        void onSkipToPrevious();
        void onSeekTo(long positionMs);
        void onStop();
        /** Custom action: toggle bit-perfect / EQ bypass */
        void onToggleBitPerfect();
        /** Custom action: toggle shuffle */
        void onToggleShuffle();
        /** Custom action: cycle repeat mode */
        void onCycleRepeat();
    }

    // =========================================================
    // Constructor
    // =========================================================

    public MediaSessionManager(Context context, Callback callback) {
        this.context  = context;
        this.callback = callback;

        mediaSession = new MediaSessionCompat(context, SESSION_TAG);

        // Daftarkan aksi yang kita support
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        );

        // Intent untuk buka MainActivity saat user tap notification
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        mediaSession.setSessionActivity(pendingIntent);

        // Set callback untuk handle aksi dari luar
        mediaSession.setCallback(mediaSessionCallback);

        // Set playback state awal
        setPlaybackState(PlaybackStateCompat.STATE_NONE, 0, 1.0f);

        mediaSession.setActive(true);
        Log.d(TAG, "MediaSession created and active");
    }

    // =========================================================
    // MediaSession Callback — handle aksi dari lock screen, headphone, dll
    // =========================================================

    private final MediaSessionCompat.Callback mediaSessionCallback =
            new MediaSessionCompat.Callback() {

                @Override
                public void onPlay() {
                    Log.d(TAG, "MediaSession → onPlay");
                    callback.onPlay();
                }

                @Override
                public void onPause() {
                    Log.d(TAG, "MediaSession → onPause");
                    callback.onPause();
                }

                @Override
                public void onSkipToNext() {
                    Log.d(TAG, "MediaSession → onSkipToNext");
                    callback.onSkipToNext();
                }

                @Override
                public void onSkipToPrevious() {
                    Log.d(TAG, "MediaSession → onSkipToPrevious");
                    callback.onSkipToPrevious();
                }

                @Override
                public void onSeekTo(long pos) {
                    Log.d(TAG, "MediaSession → onSeekTo: " + pos + "ms");
                    callback.onSeekTo(pos);
                }

                @Override
                public void onStop() {
                    Log.d(TAG, "MediaSession → onStop");
                    callback.onStop();
                }

                /**
                 * Custom actions — aksi yang tidak ada di MediaSession standar.
                 * Dikirim dari notification button custom atau shortcut.
                 */
                @Override
                public void onCustomAction(String action, Bundle extras) {
                    switch (action) {
                        case PlayerService.ACTION_TOGGLE_BIT_PERFECT:
                            callback.onToggleBitPerfect();
                            break;
                        case PlayerService.ACTION_TOGGLE_SHUFFLE:
                            callback.onToggleShuffle();
                            break;
                        case PlayerService.ACTION_CYCLE_REPEAT:
                            callback.onCycleRepeat();
                            break;
                        default:
                            Log.w(TAG, "Unknown custom action: " + action);
                    }
                }

                /**
                 * Handle tombol headphone:
                 * - 1x tap = play/pause
                 * - 2x tap = next
                 * - 3x tap = previous
                 *
                 * Android mengirim ini sebagai KeyEvent, MediaSessionCompat
                 * yang handle parsing tap count-nya untuk kita.
                 */
                @Override
                public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
                    // Biarkan super handle parsing tap count
                    return super.onMediaButtonEvent(mediaButtonEvent);
                }
            };

    // =========================================================
    // Update Metadata — tampil di lock screen dan notification
    // =========================================================

    /**
     * Update metadata lagu yang sedang main.
     * Dipanggil dari PlayerService setiap ganti lagu.
     *
     * @param song      entity Song dari database
     * @param albumArt  bitmap album art, null jika tidak ada
     */
    public void updateMetadata(Song song, Bitmap albumArt) {
        if (song == null) {
            mediaSession.setMetadata(null);
            return;
        }

        // Fallback album art jika tidak ada
        Bitmap art = albumArt;
        if (art == null) {
            art = BitmapFactory.decodeResource(
                    context.getResources(),
                    com.zaknong.airus.R.drawable.ic_default_album_art
            );
        }

        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE,       song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,      song.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM,       song.album)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST,song.albumArtist)
                .putLong  (MediaMetadataCompat.METADATA_KEY_DURATION,    song.durationMs)
                .putLong  (MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER,song.trackNumber)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART,   art)
                // Genre dan tahun untuk info tambahan
                .putString(MediaMetadataCompat.METADATA_KEY_GENRE,       song.genre)
                .putLong  (MediaMetadataCompat.METADATA_KEY_YEAR,        song.year);

        mediaSession.setMetadata(builder.build());
        Log.d(TAG, "Metadata updated: " + song.title + " — " + song.getFormatLabel());
    }

    // =========================================================
    // Update Playback State
    // =========================================================

    /**
     * Update state playback di MediaSession.
     * Ini yang mengontrol tombol play/pause di lock screen dan notification.
     *
     * @param state       PlaybackStateCompat.STATE_PLAYING / STATE_PAUSED / dll
     * @param positionMs  posisi playback saat ini
     * @param speed       kecepatan playback (1.0f = normal)
     */
    public void setPlaybackState(int state, long positionMs, float speed) {

        // Aksi yang tersedia tergantung state
        long actions = PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_STOP;

        if (state == PlaybackStateCompat.STATE_PLAYING) {
            actions |= PlaybackStateCompat.ACTION_PAUSE;
        } else {
            actions |= PlaybackStateCompat.ACTION_PLAY;
        }

        PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
                .setState(state, positionMs, speed)
                .setActions(actions)
                // Custom actions — tampil sebagai tombol tambahan di notification
                .addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                        PlayerService.ACTION_TOGGLE_BIT_PERFECT,
                        "Bit-Perfect",
                        com.zaknong.airus.R.drawable.ic_bitperfect
                ).build())
                .addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                        PlayerService.ACTION_CYCLE_REPEAT,
                        "Repeat",
                        com.zaknong.airus.R.drawable.ic_repeat
                ).build());

        mediaSession.setPlaybackState(builder.build());
    }

    // =========================================================
    // Getters
    // =========================================================

    /** Token ini dibutuhkan oleh NotificationBuilder */
    public MediaSessionCompat.Token getSessionToken() {
        return mediaSession.getSessionToken();
    }

    public MediaSessionCompat getMediaSession() {
        return mediaSession;
    }

    // =========================================================
    // Lifecycle
    // =========================================================

    public void release() {
        mediaSession.setActive(false);
        mediaSession.release();
        Log.d(TAG, "MediaSession released");
    }
}
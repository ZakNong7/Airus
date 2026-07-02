package com.zaknong.airus.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import com.zaknong.airus.R;
import com.zaknong.airus.database.entity.Song;

/**
 * NotificationBuilder
 *
 * Membangun notification media player yang tampil saat PlayerService
 * berjalan sebagai foreground service.
 *
 * Tampilan notification Airus:
 *
 * ┌─────────────────────────────────────────────────┐
 * │ [Album Art]  Judul Lagu                         │
 * │              Artis · FLAC 24/96 ◆ Bit-Perfect   │
 * │                                                  │
 * │   [⏮]  [⏸]  [⏭]  [⊛ BP]  [🔁]               │
 * └─────────────────────────────────────────────────┘
 *
 * "◆ Bit-Perfect" dan format label adalah diferensiasi utama
 * Airus vs player biasa — terlihat bahkan dari notification shade.
 *
 * Kenapa MediaStyle?
 * MediaStyle adalah subclass NotificationCompat.Style khusus media player.
 * Ia otomatis handle:
 * - Layout compact vs expanded
 * - Integrasi dengan MediaSession token
 * - Tampilan di lock screen
 * - Kontrol dari Android Auto
 */
public class NotificationBuilder {

    private static final String TAG = "NotificationBuilder";

    public static final int NOTIFICATION_ID = 1001;
    public static final String CHANNEL_ID   = "airus_playback";

    private final Context context;
    private final NotificationManager notificationManager;
    private final MediaSessionCompat.Token sessionToken;

    // =========================================================
    // Constructor
    // =========================================================

    public NotificationBuilder(Context context, MediaSessionCompat.Token sessionToken) {
        this.context            = context;
        this.sessionToken       = sessionToken;
        this.notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        createNotificationChannel();
    }

    // =========================================================
    // Notification Channel — wajib di Android 8+
    // =========================================================

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Airus Playback",           // nama yang tampil di Settings → Notifications
                NotificationManager.IMPORTANCE_LOW  // LOW = tidak ada suara, tidak ada heads-up
        );
        channel.setDescription("Kontrol playback Airus");
        channel.setShowBadge(false);    // tidak tampilkan badge angka di icon app
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC); // tampil di lock screen

        notificationManager.createNotificationChannel(channel);
    }

    // =========================================================
    // Build Notification
    // =========================================================

    /**
     * Build notification lengkap.
     *
     * @param song          lagu yang sedang main
     * @param albumArt      bitmap album art (bisa null)
     * @param isPlaying     true jika sedang play
     * @param isBitPerfect  true jika bit-perfect aktif
     * @param formatLabel   label format: "FLAC 24/96", "DSD128", "MP3 320", dll
     * @param repeatMode    untuk icon repeat yang tepat
     * @param shuffleMode   untuk icon shuffle yang tepat
     */
    public Notification build(
            Song song,
            Bitmap albumArt,
            boolean isPlaying,
            boolean isBitPerfect,
            String formatLabel,
            PlayerState.RepeatMode repeatMode,
            PlayerState.ShuffleMode shuffleMode) {

        if (song == null) return buildEmptyNotification();

        // Subtitle: artis + format + badge bit-perfect
        String subtitle = buildSubtitle(song.artist, formatLabel, isBitPerfect);

        // Tombol play atau pause tergantung state
        NotificationCompat.Action playPauseAction = isPlaying
                ? buildAction(R.drawable.ic_pause, "Pause", PlayerService.ACTION_PAUSE)
                : buildAction(R.drawable.ic_play,  "Play",  PlayerService.ACTION_PLAY);

        // Tombol repeat dengan icon sesuai mode
        int repeatIcon = getRepeatIcon(repeatMode);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)

                // ---- Konten ----
                .setContentTitle(song.title)
                .setContentText(subtitle)
                .setSubText(formatLabel)           // baris ketiga di expanded view
                .setLargeIcon(albumArt)
                .setSmallIcon(R.drawable.ic_notification)  // icon kecil di status bar

                // ---- MediaStyle ----
                .setStyle(new MediaStyle()
                        .setMediaSession(sessionToken)
                        // Index tombol yang tampil di compact view (maks 3):
                        // 0=prev, 1=play/pause, 2=next
                        .setShowActionsInCompactView(0, 1, 2)
                        .setShowCancelButton(true)
                        .setCancelButtonIntent(
                                buildPendingBroadcast(PlayerService.ACTION_STOP)
                        )
                )

                // ---- Aksi (urutan = urutan tampil di notification) ----
                .addAction(buildAction(R.drawable.ic_previous, "Previous",
                        PlayerService.ACTION_PREVIOUS))
                .addAction(playPauseAction)
                .addAction(buildAction(R.drawable.ic_next, "Next",
                        PlayerService.ACTION_NEXT))
                .addAction(buildAction(R.drawable.ic_shuffle, "Shuffle",
                        PlayerService.ACTION_TOGGLE_SHUFFLE))
                .addAction(buildAction(repeatIcon, "Repeat",
                        PlayerService.ACTION_CYCLE_REPEAT))

                // ---- Behaviour ----
                .setOngoing(true)               // tidak bisa di-dismiss swipe saat playing
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)  // tampil di lock screen
                .setSilent(true)                // tidak ada suara/vibrate
                .setPriority(NotificationCompat.PRIORITY_LOW)

                // Tap notification → buka MainActivity
                .setContentIntent(buildLaunchIntent());

        // Warna accent AMOLED amber untuk notification chrome
        builder.setColor(context.getColor(R.color.accent_primary));
        builder.setColorized(true);

        return builder.build();
    }

    // =========================================================
    // Update tanpa rebuild penuh — untuk update posisi / state kecil
    // =========================================================

    /**
     * Update hanya play/pause state di notification yang sudah ada.
     * Lebih efisien dari rebuild penuh — dipanggil setiap kali toggle play/pause.
     */
    public void updatePlayPauseState(boolean isPlaying) {
        // Kita tidak bisa update sebagian notification di Android.
        // Cara satu-satunya adalah post ulang dengan ID yang sama.
        // NotificationManager akan update in-place tanpa flicker
        // jika ID sama dan channel sama.
        //
        // Ini akan dipanggil dari PlayerService yang sudah punya
        // semua parameter, jadi kita cukup expose method ini
        // sebagai signal ke PlayerService untuk rebuild.
        //
        // Implementasi ada di PlayerService.refreshNotification()
    }

    /**
     * Notify sistem untuk update notification yang sudah ada.
     * Dipanggil dari PlayerService setelah build() menghasilkan Notification baru.
     */
    public void notify(Notification notification) {
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    /**
     * Hapus notification — dipanggil saat service stop.
     */
    public void cancel() {
        notificationManager.cancel(NOTIFICATION_ID);
    }

    // =========================================================
    // Helper — Build Actions
    // =========================================================

    private NotificationCompat.Action buildAction(int icon, String title, String action) {
        return new NotificationCompat.Action(
                icon, title, buildPendingBroadcast(action)
        );
    }

    /**
     * PendingIntent untuk broadcast — diterima oleh PlayerService
     * via BroadcastReceiver yang terdaftar di dalam service.
     *
     * Kenapa broadcast dan bukan startService()?
     * Broadcast lebih ringan dan tidak me-restart service jika
     * service sudah mati. Lebih aman untuk tombol notification.
     */
    private android.app.PendingIntent buildPendingBroadcast(String action) {
        android.content.Intent intent = new android.content.Intent(action);
        intent.setPackage(context.getPackageName());
        return android.app.PendingIntent.getBroadcast(
                context,
                action.hashCode(),      // requestCode unik per action
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT |
                        android.app.PendingIntent.FLAG_IMMUTABLE
        );
    }

    private android.app.PendingIntent buildLaunchIntent() {
        android.content.Intent intent =
                new android.content.Intent(context, com.zaknong.airus.ui.MainActivity.class);
        intent.setFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return android.app.PendingIntent.getActivity(
                context, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT |
                        android.app.PendingIntent.FLAG_IMMUTABLE
        );
    }

    // =========================================================
    // Helper — Icons
    // =========================================================

    private int getRepeatIcon(PlayerState.RepeatMode mode) {
        switch (mode) {
            case ONE: return R.drawable.ic_repeat_one;
            case ALL: return R.drawable.ic_repeat_all;
            default:  return R.drawable.ic_repeat_off;
        }
    }

    // =========================================================
    // Empty notification — saat service start tapi belum ada lagu
    // =========================================================

    private Notification buildEmptyNotification() {
        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Airus")
                .setContentText("Siap memutar musik")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(false)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    // =========================================================
    // Subtitle builder
    // =========================================================

    private String buildSubtitle(String artist, String formatLabel, boolean isBitPerfect) {
        StringBuilder sb = new StringBuilder();
        if (artist != null && !artist.isEmpty()) {
            sb.append(artist);
        }
        if (formatLabel != null && !formatLabel.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(formatLabel);
        }
        if (isBitPerfect) {
            sb.append(" ◆");   // simbol kecil penanda bit-perfect aktif
        }
        return sb.toString();
    }
}
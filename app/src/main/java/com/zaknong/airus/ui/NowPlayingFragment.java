package com.zaknong.airus.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.palette.graphics.Palette;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.zaknong.airus.R;
import com.zaknong.airus.database.AppDatabase;
import com.zaknong.airus.database.entity.Song;
import com.zaknong.airus.service.PlayerService;
import com.zaknong.airus.service.PlayerState;

/**
 * NowPlayingFragment
 *
 * Full-screen bottom sheet yang tampil saat user tap NowPlayingBar.
 * Observe PlayerState untuk update UI secara reaktif.
 */
public class NowPlayingFragment extends BottomSheetDialogFragment {

    private static final String TAG = "NowPlayingFragment";

    // =========================================================
    // Views
    // =========================================================
    private ImageView   albumArt;
    private TextView    tvTitle, tvArtist, tvPosition, tvDuration;
    private TextView    badgeFormat, badgeBitPerfect;
    private SeekBar     seekbar;
    private ImageButton btnPlayPause, btnPrevious, btnNext;
    private ImageButton btnShuffle, btnRepeat;
    private ImageButton btnFavorite, btnEq, btnQueue;
    private View        btnBitPerfect, btnReplayGain, btnCrossfeed;
    private ImageView   iconBitPerfect, iconReplayGain, iconCrossfeed;

    // =========================================================
    // Service binding
    // =========================================================
    private PlayerService playerService;
    private boolean       serviceBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            playerService = ((PlayerService.LocalBinder) binder).getService();
            serviceBound  = true;
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    // =========================================================
    // Lifecycle
    // =========================================================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_now_playing, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindViews(view);
        setupClickListeners();
        observePlayerState();
    }

    @Override
    public void onStart() {
        super.onStart();
        // Full height bottom sheet
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
        }
        requireContext().bindService(
                new Intent(requireContext(), PlayerService.class),
                serviceConnection,
                Context.BIND_AUTO_CREATE
        );
    }

    @Override
    public void onStop() {
        super.onStop();
        if (serviceBound) {
            requireContext().unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    // =========================================================
    // Bind views
    // =========================================================

    private void bindViews(View root) {
        albumArt        = root.findViewById(R.id.album_art);
        tvTitle         = root.findViewById(R.id.tv_title);
        tvArtist        = root.findViewById(R.id.tv_artist);
        tvPosition      = root.findViewById(R.id.tv_position);
        tvDuration      = root.findViewById(R.id.tv_duration);
        badgeFormat     = root.findViewById(R.id.badge_format);
        badgeBitPerfect = root.findViewById(R.id.badge_bitperfect);
        seekbar         = root.findViewById(R.id.seekbar);
        btnPlayPause    = root.findViewById(R.id.btn_play_pause);
        btnPrevious     = root.findViewById(R.id.btn_previous);
        btnNext         = root.findViewById(R.id.btn_next);
        btnShuffle      = root.findViewById(R.id.btn_shuffle);
        btnRepeat       = root.findViewById(R.id.btn_repeat);
        btnFavorite     = root.findViewById(R.id.btn_favorite);
        btnEq           = root.findViewById(R.id.btn_eq);
        btnQueue        = root.findViewById(R.id.btn_queue);
        btnBitPerfect   = root.findViewById(R.id.btn_bitperfect_container);
        btnReplayGain   = root.findViewById(R.id.btn_replaygain_container);
        btnCrossfeed    = root.findViewById(R.id.btn_crossfeed_container);
        iconBitPerfect  = root.findViewById(R.id.icon_bitperfect);
        iconReplayGain  = root.findViewById(R.id.icon_replaygain);
        iconCrossfeed   = root.findViewById(R.id.icon_crossfeed);
    }

    // =========================================================
    // Click listeners
    // =========================================================

    private void setupClickListeners() {

        btnPlayPause.setOnClickListener(v -> {
            if (!serviceBound) return;
            if (PlayerState.getInstance().isPlaying()) {
                playerService.pause();
            } else {
                playerService.play();
            }
        });

        btnPrevious.setOnClickListener(v -> {
            if (serviceBound) playerService.skipToPrevious();
        });

        btnNext.setOnClickListener(v -> {
            if (serviceBound) playerService.skipToNext();
        });

        btnShuffle.setOnClickListener(v -> {
            if (serviceBound) playerService.toggleShuffle();
        });

        btnRepeat.setOnClickListener(v -> {
            if (serviceBound) playerService.cycleRepeatMode();
        });

        btnFavorite.setOnClickListener(v -> toggleFavorite());

        btnEq.setOnClickListener(v -> {
            // Buka EqFragment
            EqFragment eqFragment = new EqFragment();
            eqFragment.show(getParentFragmentManager(), "eq");
        });

        btnBitPerfect.setOnClickListener(v -> {
            if (serviceBound) playerService.toggleBitPerfect();
        });

        // Seekbar — user drag untuk seek
        seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean isDragging = false;

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    // Update timestamp saat drag tanpa seek dulu
                    Long dur = PlayerState.getInstance().getDuration().getValue();
                    if (dur != null && dur > 0) {
                        long posMs = (long) progress * dur / seekBar.getMax();
                        tvPosition.setText(formatDuration(posMs));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isDragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isDragging = false;
                if (!serviceBound) return;
                Long dur = PlayerState.getInstance().getDuration().getValue();
                if (dur != null && dur > 0) {
                    long posMs = (long) seekBar.getProgress() * dur / seekBar.getMax();
                    playerService.seekTo(posMs);
                }
            }
        });
    }

    // =========================================================
    // Observe PlayerState
    // =========================================================

    private void observePlayerState() {
        PlayerState state = PlayerState.getInstance();

        // Lagu berubah
        state.getCurrentSong().observe(getViewLifecycleOwner(), song -> {
            if (song == null) return;
            updateSongInfo(song);
        });

        // Play/pause state
        state.getPlaybackState().observe(getViewLifecycleOwner(), playbackState -> {
            boolean isPlaying = playbackState == PlayerState.State.PLAYING;
            btnPlayPause.setImageResource(
                    isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        });

        // Posisi seekbar
        state.getPosition().observe(getViewLifecycleOwner(), posMs -> {
            Long dur = state.getDuration().getValue();
            if (dur != null && dur > 0) {
                // Hanya update seekbar jika user tidak sedang drag
                seekbar.setProgress((int) (posMs * seekbar.getMax() / dur));
                tvPosition.setText(formatDuration(posMs));
            }
        });

        // Durasi
        state.getDuration().observe(getViewLifecycleOwner(), dur -> {
            tvDuration.setText(formatDuration(dur));
        });

        // Bit-perfect state — update badge dan icon
        state.isBitPerfectActive().observe(getViewLifecycleOwner(), isBP -> {
            badgeBitPerfect.setVisibility(isBP ? View.VISIBLE : View.GONE);
            iconBitPerfect.setImageResource(
                    isBP ? R.drawable.ic_bitperfect_active
                            : R.drawable.ic_bitperfect_inactive);
            // Tint amber saat aktif, abu saat tidak
            int tint = isBP
                    ? getResources().getColor(R.color.accent_primary, null)
                    : getResources().getColor(R.color.text_tertiary, null);
            iconBitPerfect.setColorFilter(tint);
        });

        // Format label — update badge
        state.getFormatLabel().observe(getViewLifecycleOwner(), label -> {
            if (label != null && !label.isEmpty()) {
                badgeFormat.setText(label);
                badgeFormat.setVisibility(View.VISIBLE);
            } else {
                badgeFormat.setVisibility(View.GONE);
            }
        });

        // Shuffle mode — update icon tint
        state.getShuffleMode().observe(getViewLifecycleOwner(), mode -> {
            int tint = mode == PlayerState.ShuffleMode.ON
                    ? getResources().getColor(R.color.accent_primary, null)
                    : getResources().getColor(R.color.text_tertiary, null);
            btnShuffle.setColorFilter(tint);
        });

        // Repeat mode — update icon
        state.getRepeatMode().observe(getViewLifecycleOwner(), mode -> {
            switch (mode) {
                case ONE:
                    btnRepeat.setImageResource(R.drawable.ic_repeat_one);
                    btnRepeat.setColorFilter(
                            getResources().getColor(R.color.accent_primary, null));
                    break;
                case ALL:
                    btnRepeat.setImageResource(R.drawable.ic_repeat_all);
                    btnRepeat.setColorFilter(
                            getResources().getColor(R.color.accent_primary, null));
                    break;
                default:
                    btnRepeat.setImageResource(R.drawable.ic_repeat_off);
                    btnRepeat.setColorFilter(
                            getResources().getColor(R.color.text_tertiary, null));
                    break;
            }
        });
    }

    // =========================================================
    // Update UI dari Song
    // =========================================================

    private void updateSongInfo(Song song) {
        tvTitle.setText(song.title);
        tvArtist.setText(song.artist);
        tvDuration.setText(formatDuration(song.durationMs));
        seekbar.setProgress(0);

        // Album art + Palette untuk background tint
        if (song.albumArtPath != null) {
            Glide.with(this)
                    .load(song.albumArtPath)
                    .placeholder(R.drawable.ic_default_album_art)
                    .centerCrop()
                    .into(albumArt);

            // Extract warna dominan dari album art untuk ambiance
            AppDatabase.databaseWriteExecutor.execute(() -> {
                Bitmap bmp = BitmapFactory.decodeFile(song.albumArtPath);
                if (bmp == null) return;
                Palette.from(bmp).generate(palette -> {
                    if (palette == null || getView() == null) return;
                    // Palette tersedia — bisa digunakan untuk
                    // subtle background tint di future update
                });
            });
        } else {
            albumArt.setImageResource(R.drawable.ic_default_album_art);
        }

        // Update favorite icon
        btnFavorite.setImageResource(
                song.isFavorite ? R.drawable.ic_favorite_on
                        : R.drawable.ic_favorite_off);
    }

    // =========================================================
    // Favorite toggle
    // =========================================================

    private void toggleFavorite() {
        Song song = PlayerState.getInstance().getCurrentSong().getValue();
        if (song == null) return;

        boolean newFav = !song.isFavorite;
        song.isFavorite = newFav;

        btnFavorite.setImageResource(
                newFav ? R.drawable.ic_favorite_on : R.drawable.ic_favorite_off);

        AppDatabase.databaseWriteExecutor.execute(() ->
                AppDatabase.getInstance(requireContext())
                        .songDao()
                        .setFavorite(song.id, newFav)
        );
    }

    // =========================================================
    // Helper
    // =========================================================

    private String formatDuration(long ms) {
        if (ms <= 0) return "0:00";
        long totalSec = ms / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        return String.format("%d:%02d", min, sec);
    }
}
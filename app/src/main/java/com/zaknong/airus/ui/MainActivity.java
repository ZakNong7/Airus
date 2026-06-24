package com.zaknong.airus.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.zaknong.airus.R;
import com.zaknong.airus.database.entity.Song;
import com.zaknong.airus.permissions.PermissionManager;
import com.zaknong.airus.scanner.MediaScanner;
import com.zaknong.airus.scanner.TagEnricher;
import com.zaknong.airus.service.PlayerService;
import com.zaknong.airus.service.PlayerState;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // =========================================================
    // Views
    // =========================================================
    private BottomNavigationView bottomNav;
    private View                 nowPlayingBar;
    private ImageView            miniAlbumArt;
    private TextView             miniTitle;
    private TextView             miniArtist;
    private ImageButton          miniPlayPause;
    private ImageButton          miniNext;
    private ProgressBar          miniProgress;

    // =========================================================
    // Service binding
    // =========================================================
    private PlayerService playerService;
    private boolean       serviceBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            PlayerService.LocalBinder localBinder =
                    (PlayerService.LocalBinder) binder;
            playerService = localBinder.getService();
            serviceBound  = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    // =========================================================
    // Permission + Scanner
    // =========================================================
    private PermissionManager permissionManager;

    // =========================================================
    // Lifecycle
    // =========================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        setContentView(R.layout.activity_main);

        // ← Start service dulu sebelum apapun
        startPlayerService();

        permissionManager = new PermissionManager(this, granted -> {
            if (granted) {
                startMediaScan();
            } else {
                showPermissionEmptyState();
            }
        });

        bindViews();
        setupNavigation();
        observePlayerState();

        permissionManager.requestStoragePermission();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Bind ke PlayerService
        Intent intent = new Intent(this, PlayerService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    // =========================================================
    // Setup
    // =========================================================

    private void bindViews() {
        bottomNav    = findViewById(R.id.bottom_nav);
        nowPlayingBar = findViewById(R.id.now_playing_bar);
        miniAlbumArt  = nowPlayingBar.findViewById(R.id.mini_album_art);
        miniTitle     = nowPlayingBar.findViewById(R.id.mini_title);
        miniArtist    = nowPlayingBar.findViewById(R.id.mini_artist);
        miniPlayPause = nowPlayingBar.findViewById(R.id.mini_play_pause);
        miniNext      = nowPlayingBar.findViewById(R.id.mini_next);
        miniProgress  = nowPlayingBar.findViewById(R.id.mini_progress);

        // Sembunyikan NowPlayingBar sampai ada lagu
        nowPlayingBar.setVisibility(View.GONE);
    }

    private void setupNavigation() {
        NavHostFragment navHostFragment = (NavHostFragment)
                getSupportFragmentManager()
                        .findFragmentById(R.id.nav_host_fragment);

        NavController navController = navHostFragment.getNavController();
        NavigationUI.setupWithNavController(bottomNav, navController);
    }

    // =========================================================
    // Observe PlayerState — update mini player
    // =========================================================

    private void observePlayerState() {
        PlayerState state = PlayerState.getInstance();

        // Update mini player saat lagu berubah
        state.getCurrentSong().observe(this, song -> {
            if (song == null) {
                nowPlayingBar.setVisibility(View.GONE);
                return;
            }
            nowPlayingBar.setVisibility(View.VISIBLE);
            updateMiniPlayer(song);
        });

        // Update icon play/pause
        state.getPlaybackState().observe(this, playbackState -> {
            boolean isPlaying = playbackState == PlayerState.State.PLAYING;
            miniPlayPause.setImageResource(
                    isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        });

        // Update progress bar
        state.getPosition().observe(this, posMs -> {
            Long durMs = state.getDuration().getValue();
            if (durMs != null && durMs > 0) {
                int progress = (int) (posMs * 1000L / durMs);
                miniProgress.setProgress(progress);
            }
        });
    }

    private void updateMiniPlayer(Song song) {
        miniTitle.setText(song.title);

        // Artis + format label di baris kedua
        String subtitle = song.artist;
        String fmt = song.getFormatLabel();
        if (fmt != null && !fmt.isEmpty()) {
            subtitle = subtitle + " · " + fmt;
        }
        miniArtist.setText(subtitle);

        // Album art via Glide
        if (song.albumArtPath != null) {
            Glide.with(this)
                    .load(song.albumArtPath)
                    .placeholder(R.drawable.ic_default_album_art)
                    .centerCrop()
                    .into(miniAlbumArt);
        } else {
            miniAlbumArt.setImageResource(R.drawable.ic_default_album_art);
        }

        // Tap bar → buka NowPlayingFragment
        nowPlayingBar.setOnClickListener(v -> openNowPlaying());

        // Tap play/pause
        miniPlayPause.setOnClickListener(v -> {
            if (serviceBound) {
                PlayerState.State current =
                        PlayerState.getInstance().getPlaybackState().getValue();
                if (current == PlayerState.State.PLAYING) {
                    playerService.pause();
                } else {
                    playerService.play();
                }
            }
        });

        // Tap next
        miniNext.setOnClickListener(v -> {
            if (serviceBound) playerService.skipToNext();
        });
    }

    // =========================================================
    // NowPlaying
    // =========================================================

    private void openNowPlaying() {
        NowPlayingFragment fragment = new NowPlayingFragment();
        fragment.show(getSupportFragmentManager(), "now_playing");
    }

    // =========================================================
    // Service + Scanner
    // =========================================================

    private void startPlayerService() {
        Intent intent = new Intent(this, PlayerService.class);
        startForegroundService(intent);
    }

    private void startMediaScan() {
        MediaScanner scanner = new MediaScanner(this);
        TagEnricher  enricher = new TagEnricher(this);

        scanner.getScanProgress().observe(this, progress -> {
            if (progress.isFinished) {
                // Scan selesai — mulai enrich metadata detail di background
                enricher.enrichAll();
            }
        });

        scanner.startScan();
    }

    private void showPermissionEmptyState() {
        // Akan kita tambahkan empty state UI di LibraryFragment
    }
}
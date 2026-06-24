package com.zaknong.airus.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.zaknong.airus.R;
import com.zaknong.airus.database.AppDatabase;
import com.zaknong.airus.scanner.MediaScanner;
import com.zaknong.airus.scanner.TagEnricher;
import com.zaknong.airus.service.PlayerService;

import androidx.viewpager2.widget.ViewPager2;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class LibraryFragment extends Fragment {

    private static final String TAG = "LibraryFragment";

    private TabLayout  tabLayout;
    private ViewPager2 viewPager;
    private TextView   tvStats;
    private ImageButton btnScan;

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
        return inflater.inflate(R.layout.fragment_library, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tabLayout  = view.findViewById(R.id.tab_layout);
        viewPager  = view.findViewById(R.id.view_pager);
        tvStats    = view.findViewById(R.id.tv_library_stats);
        btnScan    = view.findViewById(R.id.btn_scan);

        setupViewPager();
        observeStats();
        setupScanButton();
    }

    @Override
    public void onStart() {
        super.onStart();
        requireContext().bindService(
                new Intent(requireContext(), PlayerService.class),
                serviceConnection, Context.BIND_AUTO_CREATE
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
    // ViewPager — tabs Songs / Albums / Artists
    // =========================================================

    private void setupViewPager() {
        LibraryPagerAdapter adapter =
                new LibraryPagerAdapter(requireActivity());
        viewPager.setAdapter(adapter);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case 0: tab.setText("Lagu");   break;
                case 1: tab.setText("Album");  break;
                case 2: tab.setText("Artis");  break;
            }
        }).attach();
    }

    // =========================================================
    // Stats — "342 lagu · 28 Hi-Res"
    // =========================================================

    private void observeStats() {
        AppDatabase db = AppDatabase.getInstance(requireContext());

        db.songDao().getTotalSongCount().observe(getViewLifecycleOwner(), total -> {
            db.songDao().getHiResSongCount().observe(getViewLifecycleOwner(), hiRes -> {
                String stats = total + " lagu";
                if (hiRes != null && hiRes > 0) {
                    stats += " · " + hiRes + " Hi-Res";
                }
                tvStats.setText(stats);
            });
        });
    }

    // =========================================================
    // Scan button
    // =========================================================

    private void setupScanButton() {
        btnScan.setOnClickListener(v -> {
            // Animasi rotate saat scan
            btnScan.animate().rotation(360f).setDuration(600).start();

            MediaScanner scanner = new MediaScanner(requireContext());
            TagEnricher  enricher = new TagEnricher(requireContext());

            scanner.getScanProgress().observe(getViewLifecycleOwner(), progress -> {
                if (progress.isFinished) {
                    enricher.enrichAll();
                    btnScan.animate().rotation(0f).setDuration(300).start();
                }
            });

            scanner.startScan();
        });
    }

    // =========================================================
    // ViewPager Adapter
    // =========================================================

    private static class LibraryPagerAdapter extends FragmentStateAdapter {

        public LibraryPagerAdapter(@NonNull FragmentActivity fa) {
            super(fa);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 1:  return new AlbumsFragment();
                case 2:  return new ArtistsFragment();
                default: return new SongsFragment();
            }
        }

        @Override
        public int getItemCount() { return 3; }
    }
}
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
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.google.android.material.chip.ChipGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.Glide;
import com.zaknong.airus.R;
import com.zaknong.airus.database.AppDatabase;
import com.zaknong.airus.database.entity.Song;
import com.zaknong.airus.service.PlayerService;

import java.util.ArrayList;
import java.util.List;

public class SongsFragment extends Fragment {

    private RecyclerView      recyclerSongs;
    private SwipeRefreshLayout swipeRefresh;
    private SongAdapter        adapter;
    private TextView           tvQuote, tvQuoteAuthor;
    private ChipGroup          chipGroup;

    private PlayerService playerService;
    private boolean       serviceBound = false;

    private static final String[][] QUOTES = {
            {"\"Where words fail, music speaks.\"", "Hans Christian Andersen"},
            {"\"Music is the moonlight in the gloomy night of life.\"", "Jean Paul"},
            {"\"Life is one grand, sweet song, so just start the music.\"", "Ronald Reagan"},
            {"\"Music is the shorthand of emotion.\"", "Leo Tolstoy"},
            {"\"One good thing about music, when it hits you, you feel no pain.\"", "Bob Marley"},
            {"\"Music is the wine that fills the cup of silence.\"", "Robert Fripp"},
            {"\"Without music, life would be a mistake.\"", "Friedrich Nietzsche"}
    };

    private final SongAdapter.OnSongClickListener songClickListener = song -> {
        android.util.Log.d("AIRUS_DEBUG", "onSongClick START: " + song.title
                + " | fragment=" + System.identityHashCode(SongsFragment.this));

        if (!serviceBound || playerService == null) {
            android.util.Log.e("AIRUS_DEBUG", "Service not bound!");
            return;
        }

        List<Song> songs = adapter.getSongs();
        int index = songs.indexOf(song);
        android.util.Log.d("AIRUS_DEBUG", "index=" + index + " total=" + songs.size());
        playerService.playQueue(songs, index);
    };

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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        android.util.Log.d("AIRUS_DEBUG", "SongsFragment onCreateView");
        return inflater.inflate(R.layout.fragment_songs, container, false);
        }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerSongs = view.findViewById(R.id.recycler_songs);
        swipeRefresh  = view.findViewById(R.id.swipe_refresh);
        tvQuote       = view.findViewById(R.id.tv_quote);
        tvQuoteAuthor = view.findViewById(R.id.tv_quote_author);
        chipGroup     = view.findViewById(R.id.chip_group_tabs);

        setupHeader();
        setupTabs();

        adapter = new SongAdapter(songClickListener);

        recyclerSongs.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerSongs.setAdapter(adapter);

        // SwipeRefresh warna
        swipeRefresh.setColorSchemeResources(R.color.accent_primary);
        swipeRefresh.setBackgroundColor(
                getResources().getColor(R.color.black_true, null));
        swipeRefresh.setOnRefreshListener(() ->
                swipeRefresh.setRefreshing(false));

        // Default: Observe All Songs
        observeSongs(0);
    }

    private void setupHeader() {
        int randomIndex = (int) (Math.random() * QUOTES.length);
        tvQuote.setText(QUOTES[randomIndex][0]);
        tvQuoteAuthor.setText("— " + QUOTES[randomIndex][1]);
    }

    private void setupTabs() {
        chipGroup.check(R.id.chip_all);
        chipGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.chip_all) {
                observeSongs(0);
            } else if (checkedId == R.id.chip_recent) {
                observeSongs(1);
            } else if (checkedId == R.id.chip_favorites) {
                observeSongs(2);
            }
        });
    }

    private void observeSongs(int type) {
        // Remove previous observers
        AppDatabase.getInstance(requireContext()).songDao().getAllSongsAlpha().removeObservers(getViewLifecycleOwner());
        AppDatabase.getInstance(requireContext()).songDao().getAllSongsRecent().removeObservers(getViewLifecycleOwner());
        AppDatabase.getInstance(requireContext()).songDao().getFavoriteSongs().removeObservers(getViewLifecycleOwner());

        androidx.lifecycle.LiveData<List<Song>> liveData;
        if (type == 1) {
            liveData = AppDatabase.getInstance(requireContext()).songDao().getAllSongsRecent();
        } else if (type == 2) {
            liveData = AppDatabase.getInstance(requireContext()).songDao().getFavoriteSongs();
        } else {
            liveData = AppDatabase.getInstance(requireContext()).songDao().getAllSongsAlpha();
        }

        liveData.observe(getViewLifecycleOwner(), songs -> {
            android.util.Log.d("AIRUS_DEBUG", "Songs loaded (" + type + "): " + (songs != null ? songs.size() : 0));
            adapter.setSongs(songs);
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        android.util.Log.d("AIRUS_DEBUG", "SongsFragment onStart, serviceBound=" + serviceBound);
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
    // RecyclerView Adapter
    // =========================================================

    public static class SongAdapter
            extends RecyclerView.Adapter<SongAdapter.SongViewHolder> {

        public interface OnSongClickListener {
            void onSongClick(Song song);
        }

        private List<Song>          songs = new ArrayList<>();
        private final OnSongClickListener listener;

        public SongAdapter(OnSongClickListener listener) {
            this.listener = listener;
            android.util.Log.d("AIRUS_DEBUG", "SongAdapter created, listener="
                    + (listener != null ? "OK" : "NULL"));
        }

        public void setSongs(List<Song> newSongs) {
            this.songs = newSongs != null ? newSongs : new ArrayList<>();
            android.util.Log.d("AIRUS_DEBUG", "setSongs called: " + this.songs.size());
            notifyDataSetChanged();
        }

        public List<Song> getSongs() { return songs; }

        @NonNull
        @Override
        public SongViewHolder onCreateViewHolder(@NonNull ViewGroup parent,
                                                 int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_song, parent, false);
            return new SongViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull SongViewHolder holder, int position) {
            android.util.Log.d("AIRUS_DEBUG", "onBind pos=" + position
                    + " listener=" + System.identityHashCode(listener));
            holder.bind(songs.get(position), listener);
        }

        @Override
        public int getItemCount() { return songs.size(); }

        // ---- ViewHolder ----

        static class SongViewHolder extends RecyclerView.ViewHolder {

            private final ImageView  albumArt;
            private final TextView   title, artist, formatBadge;
            private final ImageButton menuBtn;

            SongViewHolder(@NonNull View itemView) {
                super(itemView);
                albumArt    = itemView.findViewById(R.id.item_album_art);
                title       = itemView.findViewById(R.id.item_title);
                artist      = itemView.findViewById(R.id.item_artist);
                formatBadge = itemView.findViewById(R.id.item_format_badge);
                menuBtn     = itemView.findViewById(R.id.item_menu);
            }

            void bind(Song song, OnSongClickListener listener) {
                android.util.Log.d("AIRUS_DEBUG", "BINDING song: " + song.title);
                title.setText(song.title);

                // "Artis · Album"
                String sub = song.artist != null ? song.artist : "";
                if (song.album != null && !song.album.isEmpty()) {
                    sub += sub.isEmpty() ? song.album : " · " + song.album;
                }
                artist.setText(sub);

                // Album art
                if (song.albumArtPath != null) {
                    Glide.with(itemView.getContext())
                            .load(song.albumArtPath)
                            .placeholder(R.drawable.ic_default_album_art)
                            .centerCrop()
                            .into(albumArt);
                } else {
                    albumArt.setImageResource(R.drawable.ic_default_album_art);
                }

                // Format badge — tampilkan hanya jika hi-res atau DSD
                if (song.isHiRes || song.isDsd) {
                    formatBadge.setVisibility(View.VISIBLE);
                    formatBadge.setText(song.getFormatLabel());
                } else {
                    formatBadge.setVisibility(View.GONE);
                }

                // Menu per item
                menuBtn.setOnClickListener(v -> {
                    PopupMenu popup = new PopupMenu(itemView.getContext(), v);
                    popup.getMenu().add(0, 0, 0, "Tambah ke Playlist");
                    popup.getMenu().add(0, 1, 1, "Tambah ke Queue");
                    popup.getMenu().add(0, 2, 2, "Info Lagu");
                    popup.setOnMenuItemClickListener(item -> {
                        // Akan diimplementasikan lebih lanjut
                        return true;
                    });
                    popup.show();

                });
                itemView.setOnClickListener(v -> {
                    android.util.Log.d("AIRUS_DEBUG", "TAPPED_XYZ_999: " + song.title);
                    android.util.Log.d("AIRUS_DEBUG", "BEFORE_LISTENER_CALL");
                    android.util.Log.d("AIRUS_DEBUG", "listener hash=" + System.identityHashCode(listener));
                    listener.onSongClick(song);
                    android.util.Log.d("AIRUS_DEBUG", "AFTER_LISTENER_CALL");
                });
            }
        }
    }
}
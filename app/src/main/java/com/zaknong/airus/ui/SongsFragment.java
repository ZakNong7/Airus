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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_songs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerSongs = view.findViewById(R.id.recycler_songs);
        swipeRefresh  = view.findViewById(R.id.swipe_refresh);
        final SongAdapter[] adapterRef = new SongAdapter[1];

        // Setup RecyclerView
        adapterRef[0] = new SongAdapter(song -> {
            try {
                android.util.Log.d("AIRUS_DEBUG", "onSongClick START: " + song.title);

                if (!serviceBound || playerService == null) {
                    android.util.Log.e("AIRUS_DEBUG", "Service not bound!");
                    return;
                }

                // Gunakan adapterRef[0] bukan adapter
                List<Song> songs = adapterRef[0].getSongs();
                int index = songs.indexOf(song);
                android.util.Log.d("AIRUS_DEBUG", "index=" + index + " total=" + songs.size());
                playerService.playQueue(songs, index);

            } catch (Exception e) {
                android.util.Log.e("AIRUS_DEBUG", "EXCEPTION: " + e.getMessage(), e);
                e.printStackTrace();
            }
        });

        adapter = adapterRef[0];
        recyclerSongs.setLayoutManager(
                new LinearLayoutManager(requireContext()));
        recyclerSongs.setAdapter(adapter);
        recyclerSongs.setHasFixedSize(true);

        // SwipeRefresh warna
        swipeRefresh.setColorSchemeResources(R.color.accent_primary);
        swipeRefresh.setBackgroundColor(
                getResources().getColor(R.color.black_true, null));
        swipeRefresh.setOnRefreshListener(() -> {
            swipeRefresh.setRefreshing(false); // scan via LibraryFragment
        });

        // Observe lagu dari database
        AppDatabase.getInstance(requireContext())
                .songDao()
                .getAllSongsAlpha()
                .observe(getViewLifecycleOwner(), songs -> {
                    android.util.Log.d("AIRUS_DEBUG",
                            "Songs loaded from DB: " + (songs != null ? songs.size() : 0));
                    adapter.setSongs(songs);
                });
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
        public void onBindViewHolder(@NonNull SongViewHolder holder,
                                     int position) {
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
                    android.util.Log.d("AIRUS_DEBUG", "TAPPED song: " + song.title); // ← ubah jadi TAPPED
                    listener.onSongClick(song);
                });
            }
        }
    }
}
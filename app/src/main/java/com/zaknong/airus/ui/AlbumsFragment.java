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
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.zaknong.airus.R;
import com.zaknong.airus.database.AppDatabase;
import com.zaknong.airus.database.entity.Album;
import com.zaknong.airus.database.entity.Song;
import com.zaknong.airus.service.PlayerService;

import java.util.ArrayList;
import java.util.List;

public class AlbumsFragment extends Fragment {

    private RecyclerView  recyclerAlbums;
    private AlbumAdapter  adapter;

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

        recyclerAlbums = view.findViewById(R.id.recycler_songs);

        adapter = new AlbumAdapter(album -> {
            if (!serviceBound) return;
            // Tap album → load semua lagu album dan play
            AppDatabase.databaseWriteExecutor.execute(() -> {
                List<Song> songs = AppDatabase
                        .getInstance(requireContext())
                        .songDao()
                        .getSongsByAlbum(album.id)
                        .getValue();
                if (songs == null || songs.isEmpty()) return;
                requireActivity().runOnUiThread(() ->
                        playerService.playQueue(songs, 0));
            });
        });

        // Grid 2 kolom untuk tampilan album
        recyclerAlbums.setLayoutManager(
                new GridLayoutManager(requireContext(), 2));
        recyclerAlbums.setAdapter(adapter);
        recyclerAlbums.setHasFixedSize(true);

        AppDatabase.getInstance(requireContext())
                .albumDao()
                .getAllAlbums()
                .observe(getViewLifecycleOwner(), albums ->
                        adapter.setAlbums(albums));
    }

    @Override
    public void onStart() {
        super.onStart();
        requireContext().bindService(
                new Intent(requireContext(), PlayerService.class),
                serviceConnection, Context.BIND_AUTO_CREATE);
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
    // Adapter
    // =========================================================

    public static class AlbumAdapter
            extends RecyclerView.Adapter<AlbumAdapter.AlbumViewHolder> {

        public interface OnAlbumClickListener {
            void onAlbumClick(Album album);
        }

        private List<Album>             albums = new ArrayList<>();
        private final OnAlbumClickListener listener;

        public AlbumAdapter(OnAlbumClickListener listener) {
            this.listener = listener;
        }

        public void setAlbums(List<Album> newAlbums) {
            this.albums = newAlbums != null ? newAlbums : new ArrayList<>();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public AlbumViewHolder onCreateViewHolder(@NonNull ViewGroup parent,
                                                  int viewType) {
            return new AlbumViewHolder(
                    LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.item_album, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull AlbumViewHolder holder,
                                     int position) {
            holder.bind(albums.get(position), listener);
        }

        @Override
        public int getItemCount() { return albums.size(); }

        static class AlbumViewHolder extends RecyclerView.ViewHolder {

            private final ImageView albumArt;
            private final TextView  albumName, albumArtist;
            private final TextView  trackCount, hiresBadge;

            AlbumViewHolder(@NonNull View itemView) {
                super(itemView);
                albumArt    = itemView.findViewById(R.id.item_album_art);
                albumName   = itemView.findViewById(R.id.item_album_name);
                albumArtist = itemView.findViewById(R.id.item_album_artist);
                trackCount  = itemView.findViewById(R.id.item_track_count);
                hiresBadge  = itemView.findViewById(R.id.item_hires_badge);
            }

            void bind(Album album, OnAlbumClickListener listener) {
                albumName.setText(album.albumName);
                albumArtist.setText(album.albumArtist);
                trackCount.setText(album.trackCount + " lagu"
                        + (album.year > 0 ? " · " + album.year : ""));

                // Hi-res badge
                if (album.hasHiRes) {
                    hiresBadge.setVisibility(View.VISIBLE);
                    hiresBadge.setText(album.bestFormat);
                } else {
                    hiresBadge.setVisibility(View.GONE);
                }

                // Album art
                if (album.albumArtPath != null) {
                    Glide.with(itemView.getContext())
                            .load(album.albumArtPath)
                            .placeholder(R.drawable.ic_default_album_art)
                            .centerCrop()
                            .into(albumArt);
                } else {
                    albumArt.setImageResource(R.drawable.ic_default_album_art);
                }

                itemView.setOnClickListener(v -> listener.onAlbumClick(album));
            }
        }
    }
}
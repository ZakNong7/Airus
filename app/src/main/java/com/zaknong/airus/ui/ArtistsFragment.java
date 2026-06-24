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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.zaknong.airus.R;
import com.zaknong.airus.database.AppDatabase;
import com.zaknong.airus.database.entity.Song;
import com.zaknong.airus.service.PlayerService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ArtistsFragment extends Fragment {

    private RecyclerView   recyclerArtists;
    private ArtistAdapter  adapter;

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

        recyclerArtists = view.findViewById(R.id.recycler_songs);

        adapter = new ArtistAdapter(artistName -> {
            if (!serviceBound) return;
            // Tap artis → play semua lagu artis
            AppDatabase.databaseWriteExecutor.execute(() -> {
                List<Song> songs = AppDatabase
                        .getInstance(requireContext())
                        .songDao()
                        .getSongsByArtist(artistName)
                        .getValue();
                if (songs == null || songs.isEmpty()) return;
                requireActivity().runOnUiThread(() ->
                        playerService.playQueue(songs, 0));
            });
        });

        recyclerArtists.setLayoutManager(
                new LinearLayoutManager(requireContext()));
        recyclerArtists.setAdapter(adapter);
        recyclerArtists.setHasFixedSize(true);

        // Observe semua lagu lalu group by artist
        AppDatabase.getInstance(requireContext())
                .songDao()
                .getAllSongsAlpha()
                .observe(getViewLifecycleOwner(), songs -> {
                    adapter.setArtists(groupByArtist(songs));
                });
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

    /**
     * Group lagu berdasarkan artist name.
     * Return: Map<artistName, songCount>
     */
    private Map<String, Integer> groupByArtist(List<Song> songs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (songs == null) return map;
        for (Song song : songs) {
            String artist = song.artist != null ? song.artist : "Unknown Artist";
            map.put(artist, map.getOrDefault(artist, 0) + 1);
        }
        return map;
    }

    // =========================================================
    // Adapter
    // =========================================================

    public static class ArtistAdapter
            extends RecyclerView.Adapter<ArtistAdapter.ArtistViewHolder> {

        public interface OnArtistClickListener {
            void onArtistClick(String artistName);
        }

        // List of Pair<artistName, songCount>
        private final List<String>  artistNames  = new ArrayList<>();
        private final List<Integer> songCounts   = new ArrayList<>();
        private final OnArtistClickListener listener;

        public ArtistAdapter(OnArtistClickListener listener) {
            this.listener = listener;
        }

        public void setArtists(Map<String, Integer> artistMap) {
            artistNames.clear();
            songCounts.clear();
            artistNames.addAll(artistMap.keySet());
            songCounts.addAll(artistMap.values());
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ArtistViewHolder onCreateViewHolder(@NonNull ViewGroup parent,
                                                   int viewType) {
            return new ArtistViewHolder(
                    LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.item_artist, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ArtistViewHolder holder,
                                     int position) {
            holder.bind(
                    artistNames.get(position),
                    songCounts.get(position),
                    listener
            );
        }

        @Override
        public int getItemCount() { return artistNames.size(); }

        static class ArtistViewHolder extends RecyclerView.ViewHolder {

            private final TextView  artistName, artistSubtitle, artistCount;

            ArtistViewHolder(@NonNull View itemView) {
                super(itemView);
                artistName     = itemView.findViewById(R.id.item_artist_name);
                artistSubtitle = itemView.findViewById(R.id.item_artist_subtitle);
                artistCount    = itemView.findViewById(R.id.item_artist_count);
            }

            void bind(String name, int songCount,
                      OnArtistClickListener listener) {
                artistName.setText(name);
                artistSubtitle.setText(songCount + " lagu");
                artistCount.setVisibility(View.GONE); // simplify

                itemView.setOnClickListener(v -> listener.onArtistClick(name));
            }
        }
    }
}
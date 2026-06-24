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
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.zaknong.airus.R;
import com.zaknong.airus.database.AppDatabase;
import com.zaknong.airus.database.entity.Playlist;
import com.zaknong.airus.database.entity.Song;
import com.zaknong.airus.service.PlayerService;

import java.util.ArrayList;
import java.util.List;

public class PlaylistsFragment extends Fragment {

    private RecyclerView      recyclerPlaylists;
    private PlaylistAdapter   adapter;
    private FloatingActionButton fabNewPlaylist;

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
        // Pakai layout khusus dengan FAB
        return inflater.inflate(
                R.layout.fragment_playlists, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerPlaylists = view.findViewById(R.id.recycler_playlists);
        fabNewPlaylist    = view.findViewById(R.id.fab_new_playlist);

        adapter = new PlaylistAdapter(
                // Tap playlist → play
                playlist -> {
                    if (!serviceBound) return;
                    AppDatabase.databaseWriteExecutor.execute(() -> {
                        List<Song> songs = AppDatabase
                                .getInstance(requireContext())
                                .playlistDao()
                                .getSongsInPlaylist(playlist.id)
                                .getValue();
                        if (songs == null || songs.isEmpty()) return;
                        requireActivity().runOnUiThread(() ->
                                playerService.playQueue(songs, 0));
                    });
                },
                // Long press / menu → delete
                playlist -> showPlaylistMenu(playlist)
        );

        recyclerPlaylists.setLayoutManager(
                new LinearLayoutManager(requireContext()));
        recyclerPlaylists.setAdapter(adapter);

        // FAB — buat playlist baru
        fabNewPlaylist.setOnClickListener(v -> showCreatePlaylistDialog());

        // Observe playlists dari database
        AppDatabase.getInstance(requireContext())
                .playlistDao()
                .getAllPlaylists()
                .observe(getViewLifecycleOwner(), playlists ->
                        adapter.setPlaylists(playlists));
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
    // Dialog buat playlist baru
    // =========================================================

    private void showCreatePlaylistDialog() {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_save_preset, null); // reuse layout input

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Playlist Baru")
                .setView(dialogView)
                .setPositiveButton("Buat", (dialog, which) -> {
                    EditText input = dialogView.findViewById(R.id.et_preset_name);
                    if (input == null) return;
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) createPlaylist(name);
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void createPlaylist(String name) {
        Playlist playlist     = new Playlist();
        playlist.name         = name;
        playlist.dateCreated  = System.currentTimeMillis();
        playlist.dateModified = playlist.dateCreated;
        playlist.songCount    = 0;

        AppDatabase.databaseWriteExecutor.execute(() ->
                AppDatabase.getInstance(requireContext())
                        .playlistDao()
                        .insertPlaylist(playlist));
    }

    // =========================================================
    // Playlist menu (rename / delete)
    // =========================================================

    private void showPlaylistMenu(Playlist playlist) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(playlist.name)
                .setItems(new String[]{"Hapus Playlist"}, (dialog, which) -> {
                    if (which == 0) {
                        AppDatabase.databaseWriteExecutor.execute(() ->
                                AppDatabase.getInstance(requireContext())
                                        .playlistDao()
                                        .deletePlaylist(playlist));
                    }
                })
                .show();
    }

    // =========================================================
    // Adapter
    // =========================================================

    public static class PlaylistAdapter
            extends RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder> {

        public interface OnPlaylistClickListener {
            void onPlaylistClick(Playlist playlist);
        }

        public interface OnPlaylistMenuListener {
            void onPlaylistMenu(Playlist playlist);
        }

        private List<Playlist>             playlists = new ArrayList<>();
        private final OnPlaylistClickListener clickListener;
        private final OnPlaylistMenuListener  menuListener;

        public PlaylistAdapter(OnPlaylistClickListener clickListener,
                               OnPlaylistMenuListener menuListener) {
            this.clickListener = clickListener;
            this.menuListener  = menuListener;
        }

        public void setPlaylists(List<Playlist> newPlaylists) {
            this.playlists = newPlaylists != null
                    ? newPlaylists : new ArrayList<>();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public PlaylistViewHolder onCreateViewHolder(@NonNull ViewGroup parent,
                                                     int viewType) {
            return new PlaylistViewHolder(
                    LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.item_playlist, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull PlaylistViewHolder holder,
                                     int position) {
            holder.bind(playlists.get(position),
                    clickListener, menuListener);
        }

        @Override
        public int getItemCount() { return playlists.size(); }

        static class PlaylistViewHolder extends RecyclerView.ViewHolder {

            private final ImageView playlistArt;
            private final TextView  playlistName, playlistCount;
            private final ImageButton menuBtn;

            PlaylistViewHolder(@NonNull View itemView) {
                super(itemView);
                playlistArt   = itemView.findViewById(R.id.item_playlist_art);
                playlistName  = itemView.findViewById(R.id.item_playlist_name);
                playlistCount = itemView.findViewById(R.id.item_playlist_count);
                menuBtn       = itemView.findViewById(R.id.btn_playlist_menu);
            }

            void bind(Playlist playlist,
                      OnPlaylistClickListener clickListener,
                      OnPlaylistMenuListener menuListener) {
                playlistName.setText(playlist.name);
                playlistCount.setText(playlist.songCount + " lagu");

                if (playlist.coverArtPath != null) {
                    Glide.with(itemView.getContext())
                            .load(playlist.coverArtPath)
                            .placeholder(R.drawable.ic_playlist)
                            .centerCrop()
                            .into(playlistArt);
                } else {
                    playlistArt.setImageResource(R.drawable.ic_playlist);
                }

                itemView.setOnClickListener(v ->
                        clickListener.onPlaylistClick(playlist));

                menuBtn.setOnClickListener(v ->
                        menuListener.onPlaylistMenu(playlist));
            }
        }
    }
}
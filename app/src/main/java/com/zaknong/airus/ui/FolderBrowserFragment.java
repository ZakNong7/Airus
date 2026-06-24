package com.zaknong.airus.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
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

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Stack;

public class FolderBrowserFragment extends Fragment {

    private static final String TAG = "FolderBrowserFragment";

    // =========================================================
    // Views
    // =========================================================
    private RecyclerView  recyclerFolder;
    private TextView      tvFolderTitle;
    private TextView      tvBreadcrumb;
    private ImageButton   btnBack;
    private LinearLayout  emptyState;

    // =========================================================
    // State navigasi
    // Stack menyimpan history folder untuk tombol back
    // =========================================================
    private final Stack<File> navigationStack = new Stack<>();
    private File currentDir;
    private FolderAdapter adapter;

    // =========================================================
    // Root folder default = /storage/emulated/0/Music
    // Bisa diganti user via Settings nanti
    // =========================================================
    private File rootDir;

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
        return inflater.inflate(
                R.layout.fragment_folder_browser, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvFolderTitle  = view.findViewById(R.id.tv_folder_title);
        tvBreadcrumb   = view.findViewById(R.id.tv_breadcrumb);
        btnBack        = view.findViewById(R.id.btn_back);
        recyclerFolder = view.findViewById(R.id.recycler_folder);
        emptyState     = view.findViewById(R.id.empty_state);

        // Root dir
        rootDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC);

        // Fallback ke root storage jika folder Music tidak ada
        if (!rootDir.exists()) {
            rootDir = Environment.getExternalStorageDirectory();
        }

        setupRecyclerView();
        setupBackButton();
        navigateTo(rootDir);
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
    // Setup
    // =========================================================

    private void setupRecyclerView() {
        adapter = new FolderAdapter(
                // Tap folder → masuk ke subfolder
                folder -> navigateTo(folder),
                // Tap lagu → play semua lagu di folder ini
                song -> {
                    if (!serviceBound) return;
                    loadAndPlayFolder(currentDir, song);
                }
        );

        recyclerFolder.setLayoutManager(
                new LinearLayoutManager(requireContext()));
        recyclerFolder.setAdapter(adapter);
        recyclerFolder.setHasFixedSize(false);
    }

    private void setupBackButton() {
        btnBack.setOnClickListener(v -> navigateUp());
    }

    // =========================================================
    // Navigasi folder
    // =========================================================

    /**
     * Masuk ke folder tertentu.
     * Push folder sebelumnya ke navigation stack.
     */
    private void navigateTo(File dir) {
        if (currentDir != null) {
            navigationStack.push(currentDir);
        }
        currentDir = dir;
        loadFolder(dir);
    }

    /**
     * Kembali ke folder sebelumnya.
     * Pop dari navigation stack.
     */
    private void navigateUp() {
        if (!navigationStack.isEmpty()) {
            currentDir = navigationStack.pop();
            loadFolder(currentDir);
        }
    }

    /**
     * Handle back press dari Activity.
     * Return true jika masih bisa naik ke folder atas.
     */
    public boolean onBackPressed() {
        if (!navigationStack.isEmpty()) {
            navigateUp();
            return true; // consumed
        }
        return false; // biarkan Activity handle
    }

    /**
     * Load isi folder: pisahkan subfolder dan file audio.
     */
    private void loadFolder(File dir) {
        // Update header
        boolean isRoot = dir.equals(rootDir);
        tvFolderTitle.setText(isRoot ? "Folder" : dir.getName());
        tvBreadcrumb.setText(dir.getAbsolutePath());
        btnBack.setVisibility(isRoot ? View.GONE : View.VISIBLE);

        // Scroll ke atas saat pindah folder
        recyclerFolder.scrollToPosition(0);

        // List isi folder
        File[] files = dir.listFiles();
        if (files == null) {
            showEmptyState();
            return;
        }

        // Sort: folder dulu, lalu file; keduanya alfabetis
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        List<FolderItem> items = new ArrayList<>();

        for (File file : files) {
            if (file.isHidden()) continue; // skip hidden

            if (file.isDirectory()) {
                // Hitung jumlah audio file di dalamnya (tidak rekursif)
                int audioCount = countAudioFiles(file);
                if (audioCount > 0 || hasSubfolders(file)) {
                    items.add(new FolderItem(file, audioCount, true));
                }
            } else {
                // File audio
                if (com.zaknong.airus.engine.AudioFormatRouter
                        .isSupported(file.getName())) {
                    items.add(new FolderItem(file, 0, false));
                }
            }
        }

        if (items.isEmpty()) {
            showEmptyState();
        } else {
            showContent(items);
        }
    }

    private void showEmptyState() {
        recyclerFolder.setVisibility(View.GONE);
        emptyState.setVisibility(View.VISIBLE);
    }

    private void showContent(List<FolderItem> items) {
        emptyState.setVisibility(View.GONE);
        recyclerFolder.setVisibility(View.VISIBLE);
        adapter.setItems(items);
    }

    // =========================================================
    // Play semua lagu di folder
    // =========================================================

    private void loadAndPlayFolder(File dir, Song tappedSong) {
        // Query database untuk lagu-lagu di folder ini
        AppDatabase.databaseWriteExecutor.execute(() -> {
            List<Song> songs = AppDatabase.getInstance(requireContext())
                    .songDao()
                    .getSongsInFolderSync(dir.getAbsolutePath());

            // Fallback jika LiveData belum ada nilai
            // (lagu belum di-scan) — buat list dari file langsung
            if (songs == null || songs.isEmpty()) return;

            int startIndex = 0;
            for (int i = 0; i < songs.size(); i++) {
                if (songs.get(i).filePath.equals(
                        tappedSong != null ? tappedSong.filePath : "")) {
                    startIndex = i;
                    break;
                }
            }

            final List<Song> finalSongs = songs;
            final int finalIndex = startIndex;

            // Play di main thread
            requireActivity().runOnUiThread(() -> {
                if (serviceBound) {
                    playerService.playQueue(finalSongs, finalIndex);
                }
            });
        });
    }

    // =========================================================
    // Helper
    // =========================================================

    private int countAudioFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int count = 0;
        for (File f : files) {
            if (f.isFile() && com.zaknong.airus.engine.AudioFormatRouter
                    .isSupported(f.getName())) {
                count++;
            }
        }
        return count;
    }

    private boolean hasSubfolders(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.isDirectory() && !f.isHidden()) return true;
        }
        return false;
    }

    // =========================================================
    // Data model — satu item bisa folder atau file audio
    // =========================================================

    public static class FolderItem {
        public final File    file;
        public final int     audioCount;  // hanya untuk folder
        public final boolean isDirectory;

        public FolderItem(File file, int audioCount, boolean isDirectory) {
            this.file        = file;
            this.audioCount  = audioCount;
            this.isDirectory = isDirectory;
        }
    }

    // =========================================================
    // RecyclerView Adapter
    // =========================================================

    public static class FolderAdapter
            extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_FOLDER = 0;
        private static final int TYPE_SONG   = 1;

        public interface OnFolderClickListener {
            void onFolderClick(File folder);
        }

        public interface OnSongClickListener {
            void onSongClick(Song song);
        }

        private List<FolderItem>      items = new ArrayList<>();
        private final OnFolderClickListener folderListener;
        private final OnSongClickListener   songListener;

        public FolderAdapter(OnFolderClickListener folderListener,
                             OnSongClickListener songListener) {
            this.folderListener = folderListener;
            this.songListener   = songListener;
        }

        public void setItems(List<FolderItem> newItems) {
            this.items = newItems;
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).isDirectory ? TYPE_FOLDER : TYPE_SONG;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(
                @NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_FOLDER) {
                return new FolderViewHolder(
                        inflater.inflate(R.layout.item_folder, parent, false));
            } else {
                return new SongsFragment.SongAdapter.SongViewHolder(
                        inflater.inflate(R.layout.item_song, parent, false));
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder,
                                     int position) {
            FolderItem item = items.get(position);

            if (holder instanceof FolderViewHolder) {
                ((FolderViewHolder) holder).bind(item, folderListener);
            } else if (holder instanceof
                    SongsFragment.SongAdapter.SongViewHolder) {
                // Buat Song sederhana dari File untuk tampilan
                Song song = new Song();
                song.filePath = item.file.getAbsolutePath();
                song.fileName = item.file.getName();
                // Hapus ekstensi untuk title
                String name = item.file.getName();
                int dot = name.lastIndexOf('.');
                song.title  = dot > 0 ? name.substring(0, dot) : name;
                song.artist = "";

                com.zaknong.airus.engine.AudioFormatRouter.FormatInfo fmt =
                        com.zaknong.airus.engine.AudioFormatRouter
                                .getFormatInfo(item.file.getName());
                if (fmt != null) {
                    song.format     = fmt.displayName;
                    song.isLossless = fmt.isLossless;
                    song.isDsd      = fmt.isDsd;
                    song.isHiRes    = fmt.isDsd;
                }

                ((SongsFragment.SongAdapter.SongViewHolder) holder)
                        .bind(song, s -> songListener.onSongClick(s));
            }
        }

        @Override
        public int getItemCount() { return items.size(); }
    }

    // =========================================================
    // Folder ViewHolder
    // =========================================================

    static class FolderViewHolder extends RecyclerView.ViewHolder {

        private final TextView  tvFolderName;
        private final TextView  tvFolderCount;

        FolderViewHolder(@NonNull View itemView) {
            super(itemView);
            tvFolderName  = itemView.findViewById(R.id.tv_folder_name);
            tvFolderCount = itemView.findViewById(R.id.tv_folder_count);
        }

        void bind(FolderItem item,
                  FolderAdapter.OnFolderClickListener listener) {
            tvFolderName.setText(item.file.getName());

            if (item.audioCount > 0) {
                tvFolderCount.setText(item.audioCount + " lagu");
                tvFolderCount.setVisibility(View.VISIBLE);
            } else {
                tvFolderCount.setVisibility(View.GONE);
            }

            itemView.setOnClickListener(v ->
                    listener.onFolderClick(item.file));
        }
    }
}
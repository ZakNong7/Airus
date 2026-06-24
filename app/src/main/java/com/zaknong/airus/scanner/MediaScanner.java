package com.zaknong.airus.scanner;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.zaknong.airus.database.AppDatabase;
import com.zaknong.airus.database.entity.Album;
import com.zaknong.airus.database.entity.Song;
import com.zaknong.airus.engine.AudioFormatRouter;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MediaScanner
 *
 * Scan storage untuk menemukan semua file audio lalu
 * populate database Room (tabel songs dan albums).
 *
 * Dua strategi scan yang dikombinasikan:
 *
 * 1. MediaStore query — cepat, pakai index Android.
 *    Cocok untuk MP3, AAC, FLAC yang sudah dikenal Android.
 *    Kelemahan: Android sering tidak index DSD (.dsf/.dff)
 *    dan kadang metadata-nya tidak akurat untuk hi-res files.
 *
 * 2. Folder scan (File API) — lambat tapi teliti.
 *    Digunakan sebagai fallback untuk file yang tidak ada
 *    di MediaStore, atau untuk folder yang di-exclude Android
 *    (misalnya folder dengan nama diawali titik).
 *    Juga digunakan untuk baca tag langsung via jAudioTagger
 *    agar metadata lebih akurat dari MediaStore.
 *
 * Strategi final Airus:
 *   - MediaStore scan dulu untuk kecepatan
 *   - Folder scan untuk DSD dan file yang terlewat
 *   - jAudioTagger untuk verifikasi dan enrichment metadata hi-res
 *
 * Progress di-expose via LiveData agar UI bisa tampilkan progress bar.
 */
public class MediaScanner {

    private static final String TAG = "MediaScanner";

    // =========================================================
    // Scan Progress — di-observe oleh UI
    // =========================================================

    public static class ScanProgress {
        public final int scanned;
        public final int total;
        public final String currentFile;
        public final boolean isFinished;
        public final int newSongs;
        public final int updatedSongs;
        public final int removedSongs;

        public ScanProgress(int scanned, int total, String currentFile,
                            boolean isFinished, int newSongs,
                            int updatedSongs, int removedSongs) {
            this.scanned      = scanned;
            this.total        = total;
            this.currentFile  = currentFile;
            this.isFinished   = isFinished;
            this.newSongs     = newSongs;
            this.updatedSongs = updatedSongs;
            this.removedSongs = removedSongs;
        }

        public static ScanProgress finished(int newSongs, int updated, int removed) {
            return new ScanProgress(0, 0, null, true, newSongs, updated, removed);
        }
    }

    private final MutableLiveData<ScanProgress> scanProgress =
            new MutableLiveData<>();

    public LiveData<ScanProgress> getScanProgress() {
        return scanProgress;
    }

    // =========================================================
    // Dependencies
    // =========================================================

    private final Context context;
    private final AppDatabase database;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // =========================================================
    // Constructor
    // =========================================================

    public MediaScanner(Context context) {
        this.context  = context.getApplicationContext();
        this.database = AppDatabase.getInstance(context);
    }

    // =========================================================
    // Public API
    // =========================================================

    /**
     * Mulai scan di background thread.
     * Progress bisa di-observe via getScanProgress().
     *
     * Scan berjalan di single thread executor — jika dipanggil
     * dua kali, scan kedua antri setelah yang pertama selesai.
     */
    public void startScan() {
        executor.execute(this::performScan);
    }

    /**
     * Scan hanya satu folder spesifik.
     * Digunakan saat user tambah folder baru secara manual.
     */
    public void scanFolder(String folderPath) {
        executor.execute(() -> performFolderScan(new File(folderPath), new ScanStats()));
    }

    // =========================================================
    // Core Scan Logic
    // =========================================================

    private void performScan() {
        Log.d(TAG, "Scan started");
        ScanStats stats = new ScanStats();

        // Step 1: Kumpulkan semua path yang sudah ada di database
        // untuk deteksi file yang dihapus dari storage
        List<Song> existingSongs = getAllSongsSync();
        Map<String, Song> existingPathMap = new HashMap<>();
        for (Song s : existingSongs) {
            existingPathMap.put(s.filePath, s);
        }

        // Step 2: Scan via MediaStore (cepat)
        List<String> foundPaths = new ArrayList<>();
        scanViaMediaStore(foundPaths, existingPathMap, stats);

        // Step 3: Folder scan untuk DSD dan file yang terlewat MediaStore
        scanDsdFiles(foundPaths, existingPathMap, stats);

        // Step 4: Hapus lagu dari database yang file-nya sudah tidak ada
        removeOrphanedSongs(existingPathMap, foundPaths, stats);

        // Step 5: Rebuild tabel albums
        rebuildAlbums();

        Log.d(TAG, String.format("Scan selesai: +%d baru, ~%d update, -%d hapus",
                stats.newSongs, stats.updatedSongs, stats.removedSongs));

        scanProgress.postValue(
                ScanProgress.finished(stats.newSongs, stats.updatedSongs, stats.removedSongs));
    }

    // =========================================================
    // Step 2: MediaStore Scan
    // =========================================================

    private void scanViaMediaStore(List<String> foundPaths,
                                   Map<String, Song> existingMap,
                                   ScanStats stats) {
        // Kolom yang kita ambil dari MediaStore
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATA,          // path absolut
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.TRACK,         // track number
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.BITRATE,
        };

        // Filter: hanya file musik (bukan ringtone, notifikasi, dll)
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";

        Uri contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        try (Cursor cursor = context.getContentResolver().query(
                contentUri, projection, selection, null,
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {

            if (cursor == null) {
                Log.w(TAG, "MediaStore cursor null");
                return;
            }

            int totalFromMediaStore = cursor.getCount();
            Log.d(TAG, "MediaStore menemukan " + totalFromMediaStore + " file");
            int scanned = 0;

            while (cursor.moveToNext()) {
                String path = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA));

                if (path == null) continue;

                // Cek apakah format ini didukung Airus
                if (!AudioFormatRouter.isSupported(path)) continue;

                foundPaths.add(path);
                scanned++;

                // Update progress setiap 50 file
                if (scanned % 50 == 0) {
                    File f = new File(path);
                    scanProgress.postValue(new ScanProgress(
                            scanned, totalFromMediaStore, f.getName(),
                            false, stats.newSongs, stats.updatedSongs, 0));
                }

                Song existingSong = existingMap.get(path);
                long dateModified = cursor.getLong(
                        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED));

                if (existingSong != null &&
                        existingSong.dateModified == dateModified * 1000L) {
                    // File tidak berubah — skip, tidak perlu re-scan
                    continue;
                }

                // File baru atau berubah — baca metadata lengkap
                Song song = buildSongFromMediaStoreCursor(cursor, path);

                if (existingSong != null) {
                    // Update lagu yang sudah ada
                    song.id = existingSong.id;
                    song.playCount   = existingSong.playCount;
                    song.lastPlayed  = existingSong.lastPlayed;
                    song.isFavorite  = existingSong.isFavorite;
                    song.rating      = existingSong.rating;
                    database.songDao().updateSong(song);
                    stats.updatedSongs++;
                } else {
                    // Insert lagu baru
                    database.songDao().insertSong(song);
                    stats.newSongs++;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "MediaStore scan error: " + e.getMessage(), e);
        }
    }

    private Song buildSongFromMediaStoreCursor(Cursor cursor, String path) {
        Song song = new Song();
        song.filePath     = path;
        song.folderPath   = new File(path).getParent();
        song.fileName     = new File(path).getName();
        song.title        = cursor.getString(
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE));
        song.artist       = cursor.getString(
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
        song.album        = cursor.getString(
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM));
        song.albumArtist  = cursor.getString(
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST));
        song.durationMs   = cursor.getLong(
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION));
        song.fileSize     = cursor.getLong(
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE));
        song.dateAdded    = cursor.getLong(
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)) * 1000L;
        song.dateModified = cursor.getLong(
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)) * 1000L;
        song.year         = cursor.getInt(
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR));
        song.bitrate      = cursor.getInt(
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.BITRATE)) / 1000;

        // Parse track number (format MediaStore: "1/12" atau "1")
        String trackStr = cursor.getString(
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK));
        song.trackNumber  = parseTrackNumber(trackStr);

        // Deteksi format dari path
        AudioFormatRouter.FormatInfo info = AudioFormatRouter.getFormatInfo(path);
        if (info != null) {
            song.format    = info.displayName;
            song.isLossless = info.isLossless;
            song.isDsd      = info.isDsd;
        }

        // MediaStore tidak expose sample rate dan bit depth secara langsung.
        // Untuk hi-res FLAC, kita set flag sementara berdasarkan bitrate.
        // Nilai akurat akan di-enrich oleh TagReader nanti (Tahap ini scope-nya MediaStore dulu).
        // FLAC dengan bitrate > 1000 kbps kemungkinan besar hi-res 24-bit
        song.isHiRes = (song.bitrate > 1000 && song.isLossless) || song.isDsd;

        // Fallback title jika kosong
        if (song.title == null || song.title.isEmpty()) {
            song.title = song.fileName;
        }

        return song;
    }

    // =========================================================
    // Step 3: DSD Scan (File API)
    // =========================================================

    /**
     * Scan khusus untuk file DSD (.dsf, .dff) yang sering tidak
     * diindex MediaStore karena Android tidak mengenali format ini.
     *
     * Scan dilakukan di folder Music utama dan semua subfolder-nya.
     */
    private void scanDsdFiles(List<String> foundPaths,
                              Map<String, Song> existingMap,
                              ScanStats stats) {
        File musicDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC);

        if (!musicDir.exists()) return;

        Log.d(TAG, "Scanning DSD files di: " + musicDir.getAbsolutePath());
        performFolderScanForDsd(musicDir, foundPaths, existingMap, stats);
    }

    private void performFolderScanForDsd(File dir,
                                         List<String> foundPaths,
                                         Map<String, Song> existingMap,
                                         ScanStats stats) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                // Rekursif ke subfolder
                // Skip folder hidden (diawali titik)
                if (!file.getName().startsWith(".")) {
                    performFolderScanForDsd(file, foundPaths, existingMap, stats);
                }
            } else {
                String name = file.getName().toLowerCase();
                // Hanya proses .dsf dan .dff
                if (!name.endsWith(".dsf") && !name.endsWith(".dff")) continue;

                String path = file.getAbsolutePath();
                if (foundPaths.contains(path)) continue; // sudah ditemukan MediaStore

                foundPaths.add(path);

                Song existingSong = existingMap.get(path);
                if (existingSong != null &&
                        existingSong.dateModified == file.lastModified()) {
                    continue; // tidak berubah
                }

                Song song = buildSongFromFile(file);
                if (existingSong != null) {
                    song.id          = existingSong.id;
                    song.playCount   = existingSong.playCount;
                    song.lastPlayed  = existingSong.lastPlayed;
                    song.isFavorite  = existingSong.isFavorite;
                    song.rating      = existingSong.rating;
                    database.songDao().updateSong(song);
                    stats.updatedSongs++;
                } else {
                    database.songDao().insertSong(song);
                    stats.newSongs++;
                    Log.d(TAG, "DSD ditemukan: " + file.getName());
                }
            }
        }
    }

    /**
     * Folder scan umum — digunakan untuk scanFolder() public API.
     */
    private void performFolderScan(File dir, ScanStats stats) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                if (!file.getName().startsWith(".")) {
                    performFolderScan(file, stats);
                }
            } else {
                if (!AudioFormatRouter.isSupported(file.getName())) continue;

                String path = file.getAbsolutePath();
                Song existing = database.songDao().getSongByPath(path);

                if (existing != null &&
                        existing.dateModified == file.lastModified()) continue;

                Song song = buildSongFromFile(file);
                if (existing != null) {
                    song.id = existing.id;
                    database.songDao().updateSong(song);
                    stats.updatedSongs++;
                } else {
                    database.songDao().insertSong(song);
                    stats.newSongs++;
                }
            }
        }
    }

    private Song buildSongFromFile(File file) {
        Song song         = new Song();
        song.filePath     = file.getAbsolutePath();
        song.folderPath   = file.getParent();
        song.fileName     = file.getName();
        song.fileSize     = file.length();
        song.dateAdded    = System.currentTimeMillis();
        song.dateModified = file.lastModified();

        // Gunakan nama file sebagai title sementara
        // Tag sebenarnya akan dibaca oleh TagReader (dipanggil terpisah)
        String nameWithoutExt = file.getName();
        int dot = nameWithoutExt.lastIndexOf('.');
        if (dot > 0) nameWithoutExt = nameWithoutExt.substring(0, dot);
        song.title = nameWithoutExt;

        AudioFormatRouter.FormatInfo info =
                AudioFormatRouter.getFormatInfo(file.getName());
        if (info != null) {
            song.format     = info.displayName;
            song.isLossless = info.isLossless;
            song.isDsd      = info.isDsd;
            song.isHiRes    = info.isDsd; // DSD selalu hi-res
        }

        return song;
    }

    // =========================================================
    // Step 4: Remove Orphaned Songs
    // =========================================================

    private void removeOrphanedSongs(Map<String, Song> existingMap,
                                     List<String> foundPaths,
                                     ScanStats stats) {
        for (String existingPath : existingMap.keySet()) {
            if (!foundPaths.contains(existingPath)) {
                // File sudah tidak ada di storage
                Song orphan = existingMap.get(existingPath);
                if (orphan != null) {
                    database.songDao().deleteSong(orphan);
                    stats.removedSongs++;
                    Log.d(TAG, "Removed orphan: " + existingPath);
                }
            }
        }
    }

    // =========================================================
    // Step 5: Rebuild Albums
    // =========================================================

    /**
     * Rebuild tabel albums dari data songs yang sudah ada.
     *
     * Strategi: group songs by (album, albumArtist), lalu
     * insert/update satu entri Album per group.
     */
    private void rebuildAlbums() {
        // Query semua song yang punya album name
        // Implementasi sederhana: gunakan raw SQL via database
        // karena Room tidak punya GROUP BY yang mudah via DAO

        try {
            SupportSQLiteDatabase db =
                    database.getOpenHelper().getWritableDatabase();

            // Insert album baru atau update yang sudah ada
            // berdasarkan group (album, album_artist)
            db.execSQL(
                    "INSERT OR REPLACE INTO albums " +
                            "(album_name, album_artist, year, track_count, date_added, " +
                            " is_lossless, has_hi_res, best_format) " +
                            "SELECT " +
                            "  album, " +
                            "  COALESCE(album_artist, artist) as album_artist, " +
                            "  MAX(year), " +
                            "  COUNT(*) as track_count, " +
                            "  MIN(date_added) as date_added, " +
                            "  MIN(CASE WHEN is_lossless = 0 THEN 0 ELSE 1 END) as is_lossless, " +
                            "  MAX(is_hi_res) as has_hi_res, " +
                            "  MAX(format) as best_format " +   // simplifikasi, cukup untuk sekarang
                            "FROM songs " +
                            "WHERE album IS NOT NULL AND album != '' " +
                            "GROUP BY album, COALESCE(album_artist, artist)"
            );

            // Update album_id di tabel songs
            db.execSQL(
                    "UPDATE songs SET album_id = (" +
                            "  SELECT id FROM albums " +
                            "  WHERE albums.album_name = songs.album " +
                            "  AND albums.album_artist = COALESCE(songs.album_artist, songs.artist)" +
                            ") WHERE album IS NOT NULL AND album != ''"
            );

            Log.d(TAG, "Albums rebuilt");

        } catch (Exception e) {
            Log.e(TAG, "rebuildAlbums error: " + e.getMessage(), e);
        }
    }

    // =========================================================
    // Helpers
    // =========================================================

    private List<Song> getAllSongsSync() {
        // Query sinkronus — aman karena kita sudah di background thread
        try {
            android.database.Cursor cursor = database.getOpenHelper().getReadableDatabase()
                    .query("SELECT id, file_path, date_modified, play_count, " +
                            "last_played, is_favorite, rating FROM songs", null);
            return parseSongsFromRaw(cursor);
        } catch (Exception e) {
            Log.e(TAG, "getAllSongsSync error: " + e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    private List<Song> parseSongsFromRaw(android.database.Cursor cursor) {
        List<Song> songs = new ArrayList<>();
        if (cursor == null) return songs;
        try {
            while (cursor.moveToNext()) {
                Song s = new Song();
                int pathIdx = cursor.getColumnIndex("file_path");
                int modIdx  = cursor.getColumnIndex("date_modified");
                int idIdx   = cursor.getColumnIndex("id");
                int pcIdx   = cursor.getColumnIndex("play_count");
                int lpIdx   = cursor.getColumnIndex("last_played");
                int favIdx  = cursor.getColumnIndex("is_favorite");
                int ratIdx  = cursor.getColumnIndex("rating");

                if (pathIdx >= 0) s.filePath     = cursor.getString(pathIdx);
                if (modIdx  >= 0) s.dateModified = cursor.getLong(modIdx);
                if (idIdx   >= 0) s.id           = cursor.getLong(idIdx);
                if (pcIdx   >= 0) s.playCount    = cursor.getInt(pcIdx);
                if (lpIdx   >= 0) s.lastPlayed   = cursor.getLong(lpIdx);
                if (favIdx  >= 0) s.isFavorite   = cursor.getInt(favIdx) == 1;
                if (ratIdx  >= 0) s.rating       = cursor.getInt(ratIdx);

                songs.add(s);
            }
        } finally {
            cursor.close();
        }
        return songs;
    }

    private int parseTrackNumber(String trackStr) {
        if (trackStr == null || trackStr.isEmpty()) return 0;
        try {
            // Format "5/12" → ambil angka pertama
            String[] parts = trackStr.split("/");
            return Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // =========================================================
    // Stats helper
    // =========================================================

    private static class ScanStats {
        int newSongs     = 0;
        int updatedSongs = 0;
        int removedSongs = 0;
    }
}
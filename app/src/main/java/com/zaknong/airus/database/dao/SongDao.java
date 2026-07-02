package com.zaknong.airus.database.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.zaknong.airus.database.entity.Song;

import java.util.List;

/**
 * DAO: SongDao
 *
 * Semua operasi database untuk tabel songs.
 * LiveData<> untuk query yang butuh auto-update UI saat data berubah.
 */
@Dao
public interface SongDao {

    // =========================================================
    // Insert / Update / Delete
    // =========================================================

    /**
     * Insert lagu baru. Jika path sudah ada (file di-scan ulang),
     * metadata di-replace dengan data terbaru.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertSong(Song song);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<Song> songs);

    @Update
    void updateSong(Song song);

    @Update
    void updateAll(List<Song> songs);

    @Delete
    void deleteSong(Song song);

    @Query("DELETE FROM songs WHERE file_path = :path")
    void deleteByFilePath(String path);

    /**
     * Hapus semua lagu yang path-nya tidak ada dalam daftar.
     */
    @Query("DELETE FROM songs WHERE file_path NOT IN (:existingPaths)")
    int deleteOrphanedSongs(List<String> existingPaths);

    @Query("SELECT file_path FROM songs")
    List<String> getAllFilePaths();

    // =========================================================
    // Query — Library
    // =========================================================

    /** Semua lagu, diurutkan alfabetis */
    @Query("SELECT * FROM songs ORDER BY title ASC")
    LiveData<List<Song>> getAllSongsAlpha();

    /** Semua lagu, diurutkan berdasarkan kapan ditambahkan (terbaru dulu) */
    @Query("SELECT * FROM songs ORDER BY date_added DESC")
    LiveData<List<Song>> getAllSongsRecent();

    /** Semua lagu berdasarkan album */
    @Query("SELECT * FROM songs WHERE album_id = :albumId ORDER BY disc_number ASC, track_number ASC")
    LiveData<List<Song>> getSongsByAlbum(long albumId);

    /** Semua lagu milik artis tertentu */
    @Query("SELECT * FROM songs WHERE artist = :artist ORDER BY album ASC, track_number ASC")
    LiveData<List<Song>> getSongsByArtist(String artist);

    /** Lagu-lagu favorit */
    @Query("SELECT * FROM songs WHERE is_favorite = 1 ORDER BY title ASC")
    LiveData<List<Song>> getFavoriteSongs();

    /** Lagu yang pernah diputar, diurutkan berdasarkan play count */
    @Query("SELECT * FROM songs WHERE play_count > 0 ORDER BY play_count DESC LIMIT :limit")
    LiveData<List<Song>> getMostPlayedSongs(int limit);

    /** Lagu yang terakhir diputar */
    @Query("SELECT * FROM songs WHERE last_played > 0 ORDER BY last_played DESC LIMIT :limit")
    LiveData<List<Song>> getRecentlyPlayedSongs(int limit);

    // =========================================================
    // Query — Folder Browser
    // =========================================================

    /** Ambil semua folder unik yang ada lagu-nya */
    @Query("SELECT DISTINCT folder_path FROM songs ORDER BY folder_path ASC")
    LiveData<List<String>> getAllFolderPaths();

    /** Ambil lagu-lagu di dalam satu folder (tidak rekursif) */
    @Query("SELECT * FROM songs WHERE folder_path = :folderPath ORDER BY track_number ASC, file_name ASC")
    LiveData<List<Song>> getSongsInFolder(String folderPath);

    /** Jumlah lagu dalam folder (untuk subtitle di folder list) */
    @Query("SELECT COUNT(*) FROM songs WHERE folder_path = :folderPath")
    int getSongCountInFolder(String folderPath);

    // =========================================================
    // Query — Format / Hi-Res Filter
    // =========================================================

    /** Hanya lagu hi-res */
    @Query("SELECT * FROM songs WHERE is_hi_res = 1 ORDER BY title ASC")
    LiveData<List<Song>> getHiResSongs();

    /** Hanya lagu DSD */
    @Query("SELECT * FROM songs WHERE is_dsd = 1 ORDER BY title ASC")
    LiveData<List<Song>> getDsdSongs();

    /** Hanya lagu lossless */
    @Query("SELECT * FROM songs WHERE is_lossless = 1 ORDER BY title ASC")
    LiveData<List<Song>> getLosslessSongs();

    // =========================================================
    // Query — Search
    // =========================================================

    /**
     * Pencarian full-text sederhana: cocokkan di title, artist, album.
     * Gunakan untuk search bar di UI.
     */
    @Query("SELECT * FROM songs WHERE " +
            "title LIKE '%' || :query || '%' OR " +
            "artist LIKE '%' || :query || '%' OR " +
            "album LIKE '%' || :query || '%' " +
            "ORDER BY title ASC LIMIT 100")
    LiveData<List<Song>> searchSongs(String query);

    // =========================================================
    // Query — Single Song
    // =========================================================

    @Query("SELECT * FROM songs WHERE id = :id LIMIT 1")
    Song getSongById(long id);

    @Query("SELECT * FROM songs WHERE file_path = :path LIMIT 1")
    Song getSongByPath(String path);

    // =========================================================
    // Update — Playback Stats
    // =========================================================

    /** Increment play count dan update last_played timestamp */
    @Query("UPDATE songs SET play_count = play_count + 1, last_played = :timestamp WHERE id = :id")
    void incrementPlayCount(long id, long timestamp);

    /** Toggle favorit */
    @Query("UPDATE songs SET is_favorite = :isFavorite WHERE id = :id")
    void setFavorite(long id, boolean isFavorite);

    /** Update rating */
    @Query("UPDATE songs SET rating = :rating WHERE id = :id")
    void setRating(long id, int rating);

    // =========================================================
    // Query — Stats (untuk header di UI)
    // =========================================================

    @Query("SELECT COUNT(*) FROM songs")
    LiveData<Integer> getTotalSongCount();

    @Query("SELECT COUNT(*) FROM songs WHERE is_hi_res = 1")
    LiveData<Integer> getHiResSongCount();

    @Query("SELECT COUNT(*) FROM songs WHERE is_lossless = 1")
    LiveData<Integer> getLosslessSongCount();

    @Query("SELECT COUNT(DISTINCT artist) FROM songs")
    LiveData<Integer> getArtistCount();

    @Query("SELECT DISTINCT album as album, artist as artist, album_art_path as album_art_path FROM songs WHERE album IS NOT NULL AND album != '' ORDER BY album ASC")
    LiveData<List<AlbumInfo>> getAllAlbums();

    @Query("SELECT * FROM songs WHERE album = :albumName ORDER BY disc_number ASC, track_number ASC")
    LiveData<List<Song>> getSongsByAlbumName(String albumName);

    @Query("SELECT DISTINCT artist FROM songs WHERE artist IS NOT NULL AND artist != '' ORDER BY artist ASC")
    LiveData<List<String>> getAllArtists();

    class AlbumInfo {
        public String album;
        public String artist;
        public String album_art_path;
    }

    // Tambahkan di SongDao.java:
    @Query("SELECT * FROM songs WHERE sample_rate = 0 AND is_dsd = 0")
    List<Song> getUnenrichedSongs();

    @Query("SELECT * FROM songs WHERE folder_path = :folderPath ORDER BY track_number ASC, file_name ASC")
    List<Song> getSongsInFolderSync(String folderPath);
}

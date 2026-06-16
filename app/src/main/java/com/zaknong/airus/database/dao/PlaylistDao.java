package com.zaknong.airus.database.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;

import com.zaknong.airus.database.entity.Playlist;
import com.zaknong.airus.database.entity.PlaylistSongCrossRef;
import com.zaknong.airus.database.entity.Song;

import java.util.List;

/**
 * DAO: PlaylistDao
 */
@Dao
public interface PlaylistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertPlaylist(Playlist playlist);

    @Update
    void updatePlaylist(Playlist playlist);

    @Delete
    void deletePlaylist(Playlist playlist);

    @Query("SELECT * FROM playlists ORDER BY date_modified DESC")
    LiveData<List<Playlist>> getAllPlaylists();

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    Playlist getPlaylistById(long id);

    // ---- Tambah / Hapus lagu dari playlist ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void addSongToPlaylist(PlaylistSongCrossRef crossRef);

    @Delete
    void removeSongFromPlaylist(PlaylistSongCrossRef crossRef);

    /**
     * Ambil semua lagu dalam playlist, urut berdasarkan posisi.
     */
    @Query("SELECT s.* FROM songs s " +
            "INNER JOIN playlist_songs ps ON s.id = ps.song_id " +
            "WHERE ps.playlist_id = :playlistId " +
            "ORDER BY ps.position ASC")
    LiveData<List<Song>> getSongsInPlaylist(long playlistId);

    /**
     * Update posisi semua lagu dalam playlist (untuk drag-reorder).
     * Dipanggil setelah user selesai mengurutkan ulang.
     */
    @Query("UPDATE playlist_songs SET position = :position WHERE playlist_id = :playlistId AND song_id = :songId")
    void updateSongPosition(long playlistId, long songId, int position);

    /** Update song_count di tabel playlist */
    @Query("UPDATE playlists SET song_count = (SELECT COUNT(*) FROM playlist_songs WHERE playlist_id = :playlistId) WHERE id = :playlistId")
    void refreshPlaylistCount(long playlistId);
}

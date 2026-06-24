package com.zaknong.airus.database.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.zaknong.airus.database.entity.Album;

import java.util.List;

@Dao
public interface AlbumDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertAlbum(Album album);

    @Update
    void updateAlbum(Album album);

    @Delete
    void deleteAlbum(Album album);

    @Query("SELECT * FROM albums ORDER BY album_name ASC")
    LiveData<List<Album>> getAllAlbums();

    @Query("SELECT * FROM albums WHERE id = :id LIMIT 1")
    Album getAlbumById(long id);

    @Query("SELECT * FROM albums WHERE album_name = :name AND album_artist = :artist LIMIT 1")
    Album getAlbumByNameAndArtist(String name, String artist);

    @Query("SELECT * FROM albums WHERE has_hi_res = 1 ORDER BY album_name ASC")
    LiveData<List<Album>> getHiResAlbums();

    @Query("SELECT COUNT(*) FROM albums")
    LiveData<Integer> getAlbumCount();
}
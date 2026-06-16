package com.zaknong.airus.database.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.zaknong.airus.database.entity.Album;

import java.util.List;

@Dao
public interface AlbumDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(Album album);

    @Update
    void update(Album album);

    @Query("SELECT * FROM albums WHERE id = :id")
    Album getAlbumById(long id);

    @Query("SELECT id FROM albums WHERE album_name = :title AND album_artist = :artist LIMIT 1")
    Long getAlbumIdByTitleAndArtist(String title, String artist);

    @Query("SELECT COUNT(*) FROM albums")
    int getAlbumCount();

    @Query("SELECT * FROM albums ORDER BY album_name ASC")
    LiveData<List<Album>> getAllAlbums();
}

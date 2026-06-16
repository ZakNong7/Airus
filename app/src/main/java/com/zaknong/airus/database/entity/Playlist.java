package com.zaknong.airus.database.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Entity: Playlist
 */
@Entity(tableName = "playlists")
public class Playlist {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "name")
    public String name;

    @ColumnInfo(name = "description")
    public String description;

    /** Epoch millis */
    @ColumnInfo(name = "date_created")
    public long dateCreated;

    @ColumnInfo(name = "date_modified")
    public long dateModified;

    /** Jumlah lagu (di-cache untuk efisiensi UI) */
    @ColumnInfo(name = "song_count")
    public int songCount;

    /** Path album art dari lagu pertama, untuk thumbnail playlist */
    @ColumnInfo(name = "cover_art_path")
    public String coverArtPath;
}

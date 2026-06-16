package com.zaknong.airus.database.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Entity: Album
 *
 * Satu entri per album unik.
 * Album dibuat/diperbarui oleh MediaScanner saat lagu baru ditemukan.
 */
@Entity(
        tableName = "albums",
        indices = {
                @Index(value = {"album_name", "album_artist"}, unique = true)
        }
)
public class Album {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "album_name")
    public String albumName;

    @ColumnInfo(name = "album_artist")
    public String albumArtist;

    @ColumnInfo(name = "year")
    public int year;

    @ColumnInfo(name = "genre")
    public String genre;

    /** Jumlah track dalam album ini */
    @ColumnInfo(name = "track_count")
    public int trackCount;

    /** Path album art di internal cache */
    @ColumnInfo(name = "album_art_path")
    public String albumArtPath;

    /**
     * Format terbaik dalam album ini.
     * Contoh: "DSD128", "FLAC 24/96", "MP3 320"
     * Digunakan untuk badge di library view.
     */
    @ColumnInfo(name = "best_format")
    public String bestFormat;

    /** True jika semua lagu dalam album ini lossless */
    @ColumnInfo(name = "is_lossless")
    public boolean isLossless;

    /** True jika ada satu atau lebih lagu hi-res */
    @ColumnInfo(name = "has_hi_res")
    public boolean hasHiRes;

    @ColumnInfo(name = "date_added")
    public long dateAdded;
}

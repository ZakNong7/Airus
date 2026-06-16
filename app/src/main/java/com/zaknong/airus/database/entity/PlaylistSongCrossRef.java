package com.zaknong.airus.database.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

/**
 * Junction table: relasi many-to-many antara Playlist dan Song.
 *
 * Satu playlist bisa punya banyak lagu,
 * satu lagu bisa ada di banyak playlist.
 */
@Entity(
        tableName = "playlist_songs",
        primaryKeys = {"playlist_id", "song_id"},
        foreignKeys = {
                @ForeignKey(
                        entity = Playlist.class,
                        parentColumns = "id",
                        childColumns = "playlist_id",
                        onDelete = ForeignKey.CASCADE   // hapus playlist → hapus semua entri ini
                ),
                @ForeignKey(
                        entity = Song.class,
                        parentColumns = "id",
                        childColumns = "song_id",
                        onDelete = ForeignKey.CASCADE   // hapus lagu → hapus dari playlist
                )
        },
        indices = {
                @Index(value = {"playlist_id"}),
                @Index(value = {"song_id"})
        }
)
public class PlaylistSongCrossRef {

    @ColumnInfo(name = "playlist_id")
    public long playlistId;

    @ColumnInfo(name = "song_id")
    public long songId;

    /**
     * Urutan lagu di dalam playlist (0-based).
     * Memungkinkan pengguna mengatur ulang urutan lagu.
     */
    @ColumnInfo(name = "position")
    public int position;

    /** Kapan lagu ini ditambahkan ke playlist */
    @ColumnInfo(name = "date_added")
    public long dateAdded;
}

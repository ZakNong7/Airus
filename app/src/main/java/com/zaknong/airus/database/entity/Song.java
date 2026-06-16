package com.zaknong.airus.database.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Entity: Song
 *
 * Menyimpan metadata lengkap satu file audio.
 * Mendukung semua format: MP3, AAC, OGG, ALAC, FLAC, WAV, AIFF, DSD (DSF/DFF), WavPack, Opus.
 */
@Entity(
        tableName = "songs",
        indices = {
                @Index(value = {"file_path"}, unique = true),  // path harus unik
                @Index(value = {"album_id"}),                  // untuk query by album
                @Index(value = {"folder_path"}),               // untuk folder browser
                @Index(value = {"title"})                      // untuk search
        }
)
public class Song {

    // =========================================================
    // Primary Key
    // =========================================================
    @PrimaryKey(autoGenerate = true)
    public long id;

    // =========================================================
    // File Info
    // =========================================================
    @ColumnInfo(name = "file_path")
    public String filePath;           // path absolut: /storage/emulated/0/Music/...

    @ColumnInfo(name = "folder_path")
    public String folderPath;         // parent directory path

    @ColumnInfo(name = "file_name")
    public String fileName;           // nama file saja, misal: "track01.flac"

    @ColumnInfo(name = "file_size")
    public long fileSize;             // bytes

    @ColumnInfo(name = "date_added")
    public long dateAdded;            // epoch millis, saat di-scan

    @ColumnInfo(name = "date_modified")
    public long dateModified;         // epoch millis dari file system

    // =========================================================
    // Audio Format
    // =========================================================

    /**
     * Format string: "FLAC", "MP3", "AAC", "ALAC", "WAV", "AIFF",
     *                "DSD64", "DSD128", "DSD256", "WAVPACK", "OPUS", "OGG"
     */
    @ColumnInfo(name = "format")
    public String format;

    /**
     * Sample rate dalam Hz.
     * Contoh: 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000
     * DSD64 = 2822400, DSD128 = 5644800
     */
    @ColumnInfo(name = "sample_rate")
    public int sampleRate;

    /**
     * Bit depth per sample.
     * MP3/AAC = 0 (lossy, tidak relevan)
     * FLAC/WAV 16-bit = 16, 24-bit = 24, 32-bit float = 32
     * DSD = 1 (1-bit, sangat banyak sample)
     */
    @ColumnInfo(name = "bit_depth")
    public int bitDepth;

    /**
     * Jumlah channel audio.
     * 1 = mono, 2 = stereo, 6 = 5.1 surround
     */
    @ColumnInfo(name = "channels")
    public int channels;

    /**
     * Bitrate dalam kbps (hanya relevan untuk format lossy).
     * MP3 320kbps = 320, FLAC ~900kbps = 900 (variable)
     */
    @ColumnInfo(name = "bitrate")
    public int bitrate;

    /**
     * Durasi dalam milidetik.
     */
    @ColumnInfo(name = "duration_ms")
    public long durationMs;

    /**
     * Flag: apakah file ini hi-res?
     * True jika: (sampleRate >= 88200) || (bitDepth >= 24) || isDsd()
     * Digunakan untuk menampilkan badge "Hi-Res" di UI.
     */
    @ColumnInfo(name = "is_hi_res")
    public boolean isHiRes;

    /**
     * Flag: apakah format DSD?
     * Jika true, butuh jalur decoding khusus (DoP atau native DSD).
     */
    @ColumnInfo(name = "is_dsd")
    public boolean isDsd;

    /**
     * Flag: apakah format lossless?
     * True untuk: FLAC, WAV, AIFF, ALAC, WavPack lossless, DSD
     */
    @ColumnInfo(name = "is_lossless")
    public boolean isLossless;

    // =========================================================
    // Metadata Tag (ID3 / Vorbis Comment / APEv2)
    // =========================================================
    @ColumnInfo(name = "title")
    public String title;

    @ColumnInfo(name = "artist")
    public String artist;

    @ColumnInfo(name = "album_artist")
    public String albumArtist;

    @ColumnInfo(name = "album")
    public String album;

    @ColumnInfo(name = "genre")
    public String genre;

    @ColumnInfo(name = "year")
    public int year;

    @ColumnInfo(name = "track_number")
    public int trackNumber;

    @ColumnInfo(name = "disc_number")
    public int discNumber;

    @ColumnInfo(name = "composer")
    public String composer;

    @ColumnInfo(name = "comment")
    public String comment;

    // =========================================================
    // Album Art
    // =========================================================
    /**
     * Path ke file album art yang sudah di-cache di internal storage.
     * Null jika belum di-extract, atau tidak ada embedded art.
     */
    @ColumnInfo(name = "album_art_path")
    public String albumArtPath;

    /**
     * Hash MD5 dari embedded album art. Digunakan untuk menghindari
     * extract ulang jika art sudah ada di cache.
     */
    @ColumnInfo(name = "album_art_hash")
    public String albumArtHash;

    // =========================================================
    // ReplayGain Tags
    // =========================================================
    /**
     * ReplayGain: gain adjustment dalam dB untuk per-track normalisasi.
     * Contoh: -6.5 dB artinya volume dikurangi 6.5 dB.
     * Float.NaN = tag tidak ada.
     */
    @ColumnInfo(name = "rg_track_gain")
    public float rgTrackGain = Float.NaN;

    /**
     * ReplayGain: peak level track (0.0 - 1.0).
     * Digunakan untuk mencegah clipping saat gain diterapkan.
     */
    @ColumnInfo(name = "rg_track_peak")
    public float rgTrackPeak = Float.NaN;

    /**
     * ReplayGain: gain adjustment per album (untuk album mode).
     */
    @ColumnInfo(name = "rg_album_gain")
    public float rgAlbumGain = Float.NaN;

    @ColumnInfo(name = "rg_album_peak")
    public float rgAlbumPeak = Float.NaN;

    // =========================================================
    // Library Management
    // =========================================================
    /** Foreign key ke tabel albums */
    @ColumnInfo(name = "album_id")
    public long albumId;

    /** Berapa kali lagu ini diputar */
    @ColumnInfo(name = "play_count")
    public int playCount;

    /** Epoch millis terakhir diputar */
    @ColumnInfo(name = "last_played")
    public long lastPlayed;

    /** User mark: apakah lagu ini difavoritkan */
    @ColumnInfo(name = "is_favorite")
    public boolean isFavorite;

    /** Rating 0-5 bintang (0 = belum dirating) */
    @ColumnInfo(name = "rating")
    public int rating;

    // =========================================================
    // Helper Methods
    // =========================================================

    /**
     * Menentukan label format yang ditampilkan di UI.
     * Contoh: "DSD128", "FLAC 24/96", "MP3 320", "ALAC"
     */
    public String getFormatLabel() {
        if (isDsd) {
            int dsdMultiplier = sampleRate / 44100;
            return "DSD" + dsdMultiplier;
        }
        if (isLossless && bitDepth > 0 && sampleRate > 0) {
            return format + " " + bitDepth + "/" + (sampleRate / 1000) + "k";
        }
        if (bitrate > 0) {
            return format + " " + bitrate;
        }
        return format != null ? format : "Unknown";
    }

    /**
     * Cek apakah file ini membutuhkan decoder native C++ (jalur bit-perfect).
     * MP3/AAC/ALAC/OGG menggunakan MediaCodec.
     */
    public boolean needsNativeDecoder() {
        if (format == null) return false;
        switch (format.toUpperCase()) {
            case "FLAC":
            case "WAV":
            case "AIFF":
            case "DSD64":
            case "DSD128":
            case "DSD256":
            case "WAVPACK":
                return true;
            default:
                return false;
        }
    }

    /**
     * Representasi teks durasi: "mm:ss" atau "hh:mm:ss"
     */
    public String getDurationFormatted() {
        long totalSeconds = durationMs / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    @Override
    public String toString() {
        return "Song{id=" + id + ", title='" + title + "', artist='" + artist
                + "', format=" + getFormatLabel() + ", path='" + filePath + "'}";
    }
}

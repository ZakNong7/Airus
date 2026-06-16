package com.zaknong.airus.scanner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.zaknong.airus.database.AppDatabase;
import com.zaknong.airus.database.entity.Song;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TagEnricher
 *
 * Pass 2 dari proses scan: baca metadata detail per file via jAudioTagger.
 *
 * Yang di-update ke database:
 * - Sample rate dan bit depth yang akurat (MediaStore sering salah untuk hi-res)
 * - ReplayGain tags (rg_track_gain, rg_track_peak, rg_album_gain, rg_album_peak)
 * - Metadata tambahan: composer, disc number, comment
 * - Flag isHiRes yang akurat (berdasarkan sample rate / bit depth aktual)
 * - Album art: extract dari tag, simpan ke cache, simpan path ke database
 *
 * Kenapa jAudioTagger dan bukan MediaMetadataRetriever?
 * MediaMetadataRetriever tidak expose sample rate, bit depth, atau ReplayGain.
 * jAudioTagger membaca langsung dari file header dan Vorbis/ID3/APE tags.
 *
 * Catatan: jAudioTagger tidak support DSD (DSF/DFF).
 * Untuk DSD, metadata dibaca oleh native C++ decoder di Tahap 4.
 */
public class TagEnricher {

    private static final String TAG_LOG = "TagEnricher";

    // Folder di internal storage untuk cache album art
    // Format: /data/data/com.zaknong.airus/files/artwork/<hash>.jpg
    private static final String ART_CACHE_DIR = "artwork";

    // =========================================================
    // Progress
    // =========================================================

    public static class EnrichProgress {
        public final int enriched;
        public final int total;
        public final boolean isFinished;
        public final String currentFile;

        public EnrichProgress(int enriched, int total,
                              boolean isFinished, String currentFile) {
            this.enriched = enriched;
            this.total = total;
            this.isFinished = isFinished;
            this.currentFile = currentFile;
        }
    }

    private final MutableLiveData<EnrichProgress> enrichProgress =
            new MutableLiveData<>();

    public LiveData<EnrichProgress> getEnrichProgress() {
        return enrichProgress;
    }

    // =========================================================
    // Dependencies
    // =========================================================

    private final Context context;
    private final AppDatabase database;
    private final File artCacheDir;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public TagEnricher(Context context) {
        this.context = context.getApplicationContext();
        this.database = AppDatabase.getInstance(context);
        this.artCacheDir = new File(context.getFilesDir(), ART_CACHE_DIR);

        if (!artCacheDir.exists()) artCacheDir.mkdirs();

        // Matikan logging jAudioTagger — sangat verbose di logcat
        Logger.getLogger("org.jaudiotagger").setLevel(Level.OFF);
    }

    // =========================================================
    // Public API
    // =========================================================

    /**
     * Enrich semua lagu yang belum di-enrich (sample_rate == 0).
     * Berjalan di background thread, progress via LiveData.
     */
    public void enrichAll() {
        executor.execute(this::performEnrichAll);
    }

    /**
     * Enrich satu lagu spesifik — dipanggil saat lagu akan diputar
     * agar info format akurat tersedia di now playing screen.
     */
    public void enrichSong(long songId) {
        executor.execute(() -> {
            Song song = database.songDao().getSongById(songId);
            if (song != null) enrichSingle(song);
        });
    }

    // =========================================================
    // Core
    // =========================================================

    private void performEnrichAll() {
        // Ambil semua lagu yang belum di-enrich
        // "belum di-enrich" = sample_rate masih 0
        List<Song> songs = getUnenrichedSongs();
        int total = songs.size();

        if (total == 0) {
            Log.d(TAG_LOG, "Semua lagu sudah di-enrich");
            enrichProgress.postValue(
                    new EnrichProgress(0, 0, true, null));
            return;
        }

        Log.d(TAG_LOG, "Enriching " + total + " lagu...");
        int enriched = 0;

        for (Song song : songs) {
            // Skip DSD — ditangani native C++ di Tahap 4
            if (song.isDsd) {
                enriched++;
                continue;
            }

            enrichSingle(song);
            enriched++;

            // Update progress setiap 10 lagu
            if (enriched % 10 == 0 || enriched == total) {
                enrichProgress.postValue(new EnrichProgress(
                        enriched, total, false, song.fileName));
            }
        }

        enrichProgress.postValue(
                new EnrichProgress(enriched, total, true, null));
        Log.d(TAG_LOG, "Enrich selesai: " + enriched + " lagu");
    }

    private void enrichSingle(Song song) {
        try {
            File file = new File(song.filePath);
            if (!file.exists()) return;

            AudioFile audioFile = AudioFileIO.read(file);
            AudioHeader header = audioFile.getAudioHeader();
            Tag tag = audioFile.getTag();

            // ---- Audio Header — informasi teknis ----
            song.sampleRate = header.getSampleRateAsNumber();
            song.bitDepth = parseBitDepth(header);
            song.channels = header.getChannelCount();

            // Bitrate dari header lebih akurat dari MediaStore
            try {
                song.bitrate = header.getBitRateAsNumber().intValue();
            } catch (Exception e) {
                // Bitrate tidak tersedia untuk beberapa format, biarkan nilai lama
            }

            // Durasi dari jAudioTagger lebih akurat
            song.durationMs = (long) header.getTrackLength() * 1000L;

            // Update flag hi-res berdasarkan data aktual
            // Hi-res: sample rate >= 44.1kHz * 2 = 88200, ATAU bit depth >= 24
            song.isHiRes = (song.sampleRate >= 88200) || (song.bitDepth >= 24);

            // ---- Tag — metadata teks ----
            if (tag != null) {
                // Override data dari MediaStore dengan tag langsung
                // (lebih akurat, terutama untuk FLAC dan AIFF)
                song.title = getTagField(tag, FieldKey.TITLE, song.title);
                song.artist = getTagField(tag, FieldKey.ARTIST, song.artist);
                song.album = getTagField(tag, FieldKey.ALBUM, song.album);
                song.albumArtist = getTagField(tag, FieldKey.ALBUM_ARTIST, song.albumArtist);
                song.genre = getTagField(tag, FieldKey.GENRE, song.genre);
                song.composer = getTagField(tag, FieldKey.COMPOSER, song.composer);
                song.comment = getTagField(tag, FieldKey.COMMENT, song.comment);

                String yearStr = getTagField(tag, FieldKey.YEAR, null);
                if (yearStr != null && !yearStr.isEmpty()) {
                    try {
                        // Tag tahun bisa berupa "2023" atau "2023-05-21"
                        song.year = Integer.parseInt(yearStr.substring(0, 4));
                    } catch (NumberFormatException ignored) {
                    }
                }

                String trackStr = getTagField(tag, FieldKey.TRACK, null);
                if (trackStr != null) {
                    song.trackNumber = parseNumber(trackStr);
                }

                String discStr = getTagField(tag, FieldKey.DISC_NO, null);
                if (discStr != null) {
                    song.discNumber = parseNumber(discStr);
                }

                // ---- ReplayGain Tags ----
                // Format standar: "REPLAYGAIN_TRACK_GAIN" = "-6.50 dB"
                song.rgTrackGain = parseReplayGainDb(
                        getTagField(tag, FieldKey.REPLAYGAIN_TRACK_GAIN, null));
                song.rgTrackPeak = parseReplayGainFloat(
                        getTagField(tag, FieldKey.REPLAYGAIN_TRACK_PEAK, null));
                song.rgAlbumGain = parseReplayGainDb(
                        getTagField(tag, FieldKey.REPLAYGAIN_ALBUM_GAIN, null));
                song.rgAlbumPeak = parseReplayGainFloat(
                        getTagField(tag, FieldKey.REPLAYGAIN_ALBUM_PEAK, null));

                // ---- Album Art ----
                extractAndCacheAlbumArt(song, tag);
            }

            // Simpan ke database
            database.songDao().updateSong(song);

        } catch (Exception e) {
            // jAudioTagger gagal baca file — tidak fatal, lanjut ke lagu berikutnya
            Log.w(TAG_LOG, "Gagal enrich: " + song.fileName + " — " + e.getMessage());
        }
    }

    // =========================================================
    // Album Art
    // =========================================================

    /**
     * Extract album art dari tag, simpan ke cache internal storage,
     * dan simpan path cache ke song.albumArtPath.
     * <p>
     * Kenapa tidak simpan Bitmap langsung di database?
     * Karena Bitmap bisa sampai beberapa MB per lagu — sangat boros.
     * Lebih baik simpan path ke file cache, lalu load via Glide di UI.
     * <p>
     * Kenapa internal storage dan bukan external?
     * Internal storage tidak perlu permission tambahan dan lebih aman.
     */
    private void extractAndCacheAlbumArt(Song song, Tag tag) {
        try {
            Artwork artwork = tag.getFirstArtwork();
            if (artwork == null) return;

            byte[] imageData = artwork.getBinaryData();
            if (imageData == null || imageData.length == 0) return;

            // Hash MD5 dari image data sebagai nama file cache
            // Jika dua lagu punya art yang sama, mereka share satu file cache
            String hash = md5(imageData);

            // Jika hash sama dengan yang sudah ada, skip extract
            if (hash.equals(song.albumArtHash)) return;

            File cacheFile = new File(artCacheDir, hash + ".jpg");

            if (!cacheFile.exists()) {
                // Decode → compress ke JPEG → simpan
                Bitmap bitmap = BitmapFactory.decodeByteArray(
                        imageData, 0, imageData.length);

                if (bitmap == null) return;

                // Resize jika terlalu besar (>800px) untuk hemat storage
                bitmap = resizeIfNeeded(bitmap, 800);

                try (FileOutputStream out = new FileOutputStream(cacheFile)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
                }

                bitmap.recycle();
            }

            song.albumArtPath = cacheFile.getAbsolutePath();
            song.albumArtHash = hash;

        } catch (Exception e) {
            Log.w(TAG_LOG, "Gagal extract art: " + song.fileName + " — " + e.getMessage());
        }
    }

    private Bitmap resizeIfNeeded(Bitmap original, int maxSize) {
        int w = original.getWidth();
        int h = original.getHeight();
        if (w <= maxSize && h <= maxSize) return original;

        float scale = Math.min((float) maxSize / w, (float) maxSize / h);
        int newW = Math.round(w * scale);
        int newH = Math.round(h * scale);

        Bitmap resized = Bitmap.createScaledBitmap(original, newW, newH, true);
        original.recycle();
        return resized;
    }

    // =========================================================
    // Parsing Helpers
    // =========================================================

    /**
     * Ambil field tag dengan fallback jika field tidak ada.
     */
    private String getTagField(Tag tag, FieldKey key, String fallback) {
        try {
            String value = tag.getFirst(key);
            return (value != null && !value.isEmpty()) ? value : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Bit depth tidak selalu tersedia via AudioHeader.getBitsPerSample().
     * Beberapa versi jAudioTagger menamai method ini berbeda.
     */
    private int parseBitDepth(AudioHeader header) {
        try {
            return header.getBitsPerSample();
        } catch (Exception e) {
            // Fallback: coba inferensi dari encoding type
            // "FLAC 16 bit" → 16, "FLAC 24 bit" → 24
            String encoding = header.getEncodingType();
            if (encoding != null) {
                if (encoding.contains("24")) return 24;
                if (encoding.contains("32")) return 32;
                if (encoding.contains("16")) return 16;
            }
            return 16; // default
        }
    }

    /**
     * Parse ReplayGain gain value: "-6.50 dB" → -6.5f
     * Atau "+1.23 dB" → 1.23f
     */
    private float parseReplayGainDb(String value) {
        if (value == null || value.isEmpty()) return Float.NaN;
        try {
            // Hapus " dB" di akhir, trim whitespace
            String clean = value.toLowerCase()
                    .replace("db", "")
                    .trim();
            return Float.parseFloat(clean);
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    /**
     * Parse ReplayGain peak value: "0.954325" → 0.954325f
     */
    private float parseReplayGainFloat(String value) {
        if (value == null || value.isEmpty()) return Float.NaN;
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    /**
     * Parse angka dari string yang mungkin berformat "1/12" atau "1"
     */
    private int parseNumber(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            return Integer.parseInt(value.split("/")[0].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(data);
            return new BigInteger(1, hash).toString(16);
        } catch (Exception e) {
            // Fallback: gunakan timestamp
            return String.valueOf(System.currentTimeMillis());
        }
    }

    // =========================================================
    // Database query helper
    // =========================================================

    private List<Song> getUnenrichedSongs() {
        return database.songDao().getUnenrichedSongs();
    }
}
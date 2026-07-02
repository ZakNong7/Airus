package com.zaknong.airus.database;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.zaknong.airus.database.dao.AlbumDao;
import com.zaknong.airus.database.dao.EqPresetDao;
import com.zaknong.airus.database.dao.PlaylistDao;
import com.zaknong.airus.database.dao.SongDao;
import com.zaknong.airus.database.dao.ScanFolderDao;
import com.zaknong.airus.database.entity.ScanFolder;
import com.zaknong.airus.database.entity.Album;
import com.zaknong.airus.database.entity.EqPreset;
import com.zaknong.airus.database.entity.Playlist;
import com.zaknong.airus.database.entity.PlaylistSongCrossRef;
import com.zaknong.airus.database.entity.Song;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AppDatabase — Room Database utama Airus.
 *
 * Singleton. Gunakan AppDatabase.getInstance(context) untuk mendapatkan instance.
 * Jangan pernah buat instance langsung dengan new.
 *
 * Thread pool 4 thread tersedia via databaseWriteExecutor untuk
 * operasi write di luar main thread.
 */
@Database(
        entities = {
                Song.class,
                Album.class,
                Playlist.class,
                PlaylistSongCrossRef.class,
                EqPreset.class,
                ScanFolder.class
        },
        version = 2,
        exportSchema = true   // ekspor schema ke /schemas/ untuk version control
)
public abstract class AppDatabase extends RoomDatabase {

    private static final String TAG = "AppDatabase";
    private static final String DB_NAME = "airus_library.db";

    // =========================================================
    // Abstract DAO accessors
    // =========================================================
    public abstract SongDao songDao();
    public abstract AlbumDao albumDao();
    public abstract PlaylistDao playlistDao();
    public abstract EqPresetDao eqPresetDao();
    public abstract ScanFolderDao scanFolderDao();

    // =========================================================
    // Singleton
    // =========================================================
    private static volatile AppDatabase INSTANCE;

    /**
     * Thread pool untuk write operations (insert, update, delete).
     * Jangan lakukan operasi database di main thread!
     * Gunakan: AppDatabase.databaseWriteExecutor.execute(() -> { db.songDao().insert(...); });
     */
    public static final ExecutorService databaseWriteExecutor =
            Executors.newFixedThreadPool(4);

    public static AppDatabase getInstance(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    DB_NAME
                            )
                            // Seed data awal saat database pertama kali dibuat
                            .addCallback(SEED_CALLBACK)
                            // Fallback: jika tidak ada migration yang cocok,
                            // hapus dan buat ulang database (hati-hati di production!)
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }

    // =========================================================
    // Seed Callback — data awal saat database baru dibuat
    // =========================================================
    private static final RoomDatabase.Callback SEED_CALLBACK = new RoomDatabase.Callback() {
        @Override
        public void onCreate(@NonNull SupportSQLiteDatabase db) {
            super.onCreate(db);
            Log.d(TAG, "Database baru dibuat, memasukkan data awal...");

            databaseWriteExecutor.execute(() -> {
                if (INSTANCE != null) {
                    seedDefaultEqPresets(INSTANCE);
                }
            });
        }
    };

    /**
     * Insert preset EQ bawaan:
     * - Flat (default aktif)
     * - Bass Boost
     * - Treble Boost
     * - Vocal Clarity
     */
    private static void seedDefaultEqPresets(AppDatabase db) {
        EqPresetDao dao = db.eqPresetDao();

        // 1. Flat (aktif secara default)
        EqPreset flat = EqPreset.createFlatPreset();
        dao.insertPreset(flat);

        // 2. Bass Boost
        EqPreset bassBoost = new EqPreset();
        bassBoost.name = "Bass Boost";
        bassBoost.presetType = "SYSTEM";
        bassBoost.preampDb = -3.0f;
        bassBoost.isActive = false;
        bassBoost.dateCreated = System.currentTimeMillis();
        bassBoost.dateModified = bassBoost.dateCreated;
        bassBoost.bandsJson =
                "[{\"freq\":32,\"gain\":5.0,\"q\":0.8,\"type\":\"LOW_SHELF\"}," +
                        "{\"freq\":64,\"gain\":4.5,\"q\":1.2,\"type\":\"PEAK\"}," +
                        "{\"freq\":125,\"gain\":3.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                        "{\"freq\":250,\"gain\":1.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                        "{\"freq\":500,\"gain\":0.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                        "{\"freq\":1000,\"gain\":0.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                        "{\"freq\":2000,\"gain\":0.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                        "{\"freq\":4000,\"gain\":0.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                        "{\"freq\":8000,\"gain\":0.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                        "{\"freq\":16000,\"gain\":0.0,\"q\":1.4,\"type\":\"HIGH_SHELF\"}]";
        dao.insertPreset(bassBoost);

        // 3. Vocal Clarity (mid emphasis)
        EqPreset vocalClarity = new EqPreset();
        vocalClarity.name = "Vocal Clarity";
        vocalClarity.presetType = "SYSTEM";
        vocalClarity.preampDb = -2.0f;
        vocalClarity.isActive = false;
        vocalClarity.dateCreated = System.currentTimeMillis();
        vocalClarity.dateModified = vocalClarity.dateCreated;
        vocalClarity.bandsJson =
                "[{\"freq\":32,\"gain\":-1.0,\"q\":0.8,\"type\":\"LOW_SHELF\"}," +
                        "{\"freq\":64,\"gain\":-1.0,\"q\":1.2,\"type\":\"PEAK\"}," +
                        "{\"freq\":125,\"gain\":0.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                        "{\"freq\":250,\"gain\":0.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                        "{\"freq\":500,\"gain\":1.5,\"q\":1.4,\"type\":\"PEAK\"}," +
                        "{\"freq\":1000,\"gain\":3.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                        "{\"freq\":2000,\"gain\":3.5,\"q\":1.4,\"type\":\"PEAK\"}," +
                        "{\"freq\":4000,\"gain\":2.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                        "{\"freq\":8000,\"gain\":1.5,\"q\":1.4,\"type\":\"PEAK\"}," +
                        "{\"freq\":16000,\"gain\":1.0,\"q\":1.4,\"type\":\"HIGH_SHELF\"}]";
        dao.insertPreset(vocalClarity);

        // 4. Treble Boost (untuk IEM yang dark-sounding)
        EqPreset trebleBoost = new EqPreset();
        trebleBoost.name = "Treble Boost";
        trebleBoost.presetType = "SYSTEM";
        trebleBoost.preampDb = -2.0f;
        trebleBoost.isActive = false;
        trebleBoost.dateCreated = System.currentTimeMillis();
        trebleBoost.dateModified = trebleBoost.dateCreated;
        trebleBoost.bandsJson =
                "[{\"freq\":32,\"gain\":0.0,\"q\":0.8,\"type\":\"LOW_SHELF\"}," +
                        "{\"freq\":64,\"gain\":0.0,\"q\":1.2,\"type\":\"PEAK\"}," +
                        "{\"freq\":125,\"gain\":0.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                        "{\"freq\":250,\"gain\":0.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                        "{\"freq\":500,\"gain\":0.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                        "{\"freq\":1000,\"gain\":0.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                        "{\"freq\":2000,\"gain\":1.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                        "{\"freq\":4000,\"gain\":2.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                        "{\"freq\":8000,\"gain\":3.5,\"q\":1.4,\"type\":\"PEAK\"}," +
                        "{\"freq\":16000,\"gain\":4.5,\"q\":1.4,\"type\":\"HIGH_SHELF\"}]";
        dao.insertPreset(trebleBoost);

        Log.d(TAG, "4 EQ preset default berhasil di-seed.");
    }

    // =========================================================
    // Migration (untuk upgrade versi database di masa depan)
    // =========================================================

    // Contoh migration dari versi 1 ke 2 (uncomment saat dibutuhkan):
    //
    // static final Migration MIGRATION_1_2 = new Migration(1, 2) {
    //     @Override
    //     public void migrate(@NonNull SupportSQLiteDatabase database) {
    //         // Contoh: tambah kolom baru di tabel songs
    //         database.execSQL("ALTER TABLE songs ADD COLUMN cue_sheet_path TEXT");
    //     }
    // };
}

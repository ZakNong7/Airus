package com.zaknong.airus.database.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Entity: EqPreset
 *
 * Menyimpan satu konfigurasi Parametric Equalizer.
 * Setiap preset berisi hingga 10 band EQ + pengaturan global.
 *
 * Format penyimpanan band menggunakan JSON string di kolom bands_json
 * agar fleksibel (bisa 5 band, 10 band, atau jumlah lainnya).
 *
 * Contoh bands_json:
 * [
 *   {"freq": 32,   "gain": 0.0, "q": 1.4, "type": "LOW_SHELF"},
 *   {"freq": 64,   "gain": 0.0, "q": 1.4, "type": "PEAK"},
 *   {"freq": 125,  "gain": 0.0, "q": 1.4, "type": "PEAK"},
 *   {"freq": 250,  "gain": 0.0, "q": 1.4, "type": "PEAK"},
 *   {"freq": 500,  "gain": 0.0, "q": 1.4, "type": "PEAK"},
 *   {"freq": 1000, "gain": 0.0, "q": 1.4, "type": "PEAK"},
 *   {"freq": 2000, "gain": 0.0, "q": 1.4, "type": "PEAK"},
 *   {"freq": 4000, "gain": 0.0, "q": 1.4, "type": "PEAK"},
 *   {"freq": 8000, "gain": 0.0, "q": 1.4, "type": "PEAK"},
 *   {"freq": 16000,"gain": 0.0, "q": 1.4, "type": "HIGH_SHELF"}
 * ]
 */
@Entity(tableName = "eq_presets")
public class EqPreset {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Nama preset, misal: "Flat", "Bass Boost", "Sennheiser HD650" */
    @ColumnInfo(name = "name")
    public String name;

    /**
     * Tipe preset:
     * "USER"   = dibuat pengguna
     * "AUTOEQ" = diimpor dari database AutoEq
     * "SYSTEM" = bawaan aplikasi (tidak bisa dihapus)
     */
    @ColumnInfo(name = "preset_type")
    public String presetType;

    /**
     * Nama headphone/IEM yang preset ini ditujukan (untuk AutoEQ preset).
     * Null untuk preset user/system.
     */
    @ColumnInfo(name = "headphone_model")
    public String headphoneModel;

    /**
     * JSON array berisi daftar EQ band.
     * Lihat format di javadoc kelas ini.
     */
    @ColumnInfo(name = "bands_json")
    public String bandsJson;

    /**
     * Global preamp gain dalam dB (-12.0 hingga +12.0).
     * Digunakan untuk mencegah clipping saat band di-boost.
     * Rekomendasi AutoEQ biasanya negatif (misal: -6.5 dB).
     */
    @ColumnInfo(name = "preamp_db")
    public float preampDb;

    /** True jika preset ini sedang aktif/terpilih */
    @ColumnInfo(name = "is_active")
    public boolean isActive;

    /** Epoch millis */
    @ColumnInfo(name = "date_created")
    public long dateCreated;

    @ColumnInfo(name = "date_modified")
    public long dateModified;

    // =========================================================
    // Preset defaults bawaan aplikasi
    // =========================================================

    public static final String FLAT_PRESET_JSON =
            "[{\"freq\":32,\"gain\":0.0,\"q\":1.4,\"type\":\"LOW_SHELF\"}," +
                    "{\"freq\":64,\"gain\":0.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                    "{\"freq\":125,\"gain\":0.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                    "{\"freq\":250,\"gain\":0.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                    "{\"freq\":500,\"gain\":0.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                    "{\"freq\":1000,\"gain\":0.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                    "{\"freq\":2000,\"gain\":0.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                    "{\"freq\":4000,\"gain\":0.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                    "{\"freq\":8000,\"gain\":0.0,\"q\":1.4,\"type\":\"PEAK\"}," +
                    "{\"freq\":16000,\"gain\":0.0,\"q\":1.4,\"type\":\"HIGH_SHELF\"}]";

    /** Buat preset flat default */
    public static EqPreset createFlatPreset() {
        EqPreset p = new EqPreset();
        p.name = "Flat";
        p.presetType = "SYSTEM";
        p.bandsJson = FLAT_PRESET_JSON;
        p.preampDb = 0.0f;
        p.isActive = true;
        p.dateCreated = System.currentTimeMillis();
        p.dateModified = p.dateCreated;
        return p;
    }
}

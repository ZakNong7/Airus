package com.zaknong.airus.database.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.zaknong.airus.database.entity.EqPreset;

import java.util.List;

/**
 * DAO: EqPresetDao
 */
@Dao
public interface EqPresetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertPreset(EqPreset preset);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertPresets(List<EqPreset> presets);

    @Update
    void updatePreset(EqPreset preset);

    @Delete
    void deletePreset(EqPreset preset);

    /** Tidak boleh hapus preset SYSTEM */
    @Query("DELETE FROM eq_presets WHERE id = :id AND preset_type != 'SYSTEM'")
    int deleteUserPreset(long id);

    @Query("SELECT * FROM eq_presets ORDER BY preset_type ASC, name ASC")
    LiveData<List<EqPreset>> getAllPresets();

    @Query("SELECT * FROM eq_presets WHERE preset_type = 'USER' ORDER BY name ASC")
    LiveData<List<EqPreset>> getUserPresets();

    @Query("SELECT * FROM eq_presets WHERE preset_type = 'AUTOEQ' ORDER BY name ASC")
    LiveData<List<EqPreset>> getAutoEqPresets();

    @Query("SELECT * FROM eq_presets WHERE is_active = 1 LIMIT 1")
    EqPreset getActivePreset();

    /** Set hanya satu preset yang aktif */
    @Query("UPDATE eq_presets SET is_active = 0")
    void deactivateAllPresets();

    @Query("UPDATE eq_presets SET is_active = 1 WHERE id = :id")
    void activatePreset(long id);

    @Query("SELECT * FROM eq_presets WHERE id = :id LIMIT 1")
    EqPreset getPresetById(long id);

    /** Cari preset AutoEQ berdasarkan nama headphone */
    @Query("SELECT * FROM eq_presets WHERE preset_type = 'AUTOEQ' AND headphone_model LIKE '%' || :query || '%' ORDER BY headphone_model ASC")
    LiveData<List<EqPreset>> searchAutoEqPresets(String query);
}

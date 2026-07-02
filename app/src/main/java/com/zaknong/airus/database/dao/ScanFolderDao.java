package com.zaknong.airus.database.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.zaknong.airus.database.entity.ScanFolder;

import java.util.List;

@Dao
public interface ScanFolderDao {
    @Query("SELECT * FROM scan_folders ORDER BY date_added DESC")
    LiveData<List<ScanFolder>> getAllFolders();

    @Query("SELECT * FROM scan_folders")
    List<ScanFolder> getAllFoldersSync();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(ScanFolder folder);

    @Delete
    void delete(ScanFolder folder);

    @Query("SELECT COUNT(*) FROM scan_folders")
    int getCount();
}

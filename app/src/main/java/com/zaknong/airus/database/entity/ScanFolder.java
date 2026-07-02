package com.zaknong.airus.database.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Entity: ScanFolder
 * Menyimpan daftar folder spesifik yang dipilih user untuk di-scan.
 */
@Entity(
        tableName = "scan_folders",
        indices = {@Index(value = {"path"}, unique = true)}
)
public class ScanFolder {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "path")
    public String path;

    @ColumnInfo(name = "display_name")
    public String displayName;

    @ColumnInfo(name = "date_added")
    public long dateAdded;

    public ScanFolder() {}

    public ScanFolder(String path, String displayName) {
        this.path = path;
        this.displayName = displayName;
        this.dateAdded = System.currentTimeMillis();
    }
}

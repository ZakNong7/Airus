package com.zaknong.airus.permissions;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * PermissionManager
 *
 * Menangani runtime permission untuk akses storage secara adaptif:
 *
 * - Android 13+ (API 33+) : READ_MEDIA_AUDIO
 * - Android 10-12 (API 29-32): READ_EXTERNAL_STORAGE (dengan MANAGE_EXTERNAL_STORAGE opsional)
 * - Android 6-9 (API 23-28) : READ_EXTERNAL_STORAGE
 *
 * Cara pakai di MainActivity.java:
 *
 *   // Di onCreate():
 *   permissionManager = new PermissionManager(this, granted -> {
 *       if (granted) startMediaScan();
 *       else showPermissionDeniedUI();
 *   });
 *
 *   // Saat pertama kali butuh akses:
 *   permissionManager.requestStoragePermission();
 */
public class PermissionManager {

    private static final String TAG = "PermissionManager";

    public static final int REQUEST_CODE_STORAGE = 1001;

    public interface PermissionCallback {
        void onPermissionResult(boolean granted);
    }

    private final AppCompatActivity activity;
    private final PermissionCallback callback;
    private ActivityResultLauncher<String[]> permissionLauncher;

    public PermissionManager(AppCompatActivity activity, PermissionCallback callback) {
        this.activity = activity;
        this.callback = callback;
        registerLauncher();
    }

    // =========================================================
    // Register ActivityResultLauncher
    // =========================================================

    /**
     * Launcher HARUS didaftarkan sebelum Activity onCreate() selesai.
     * Pastikan new PermissionManager() dipanggil di awal onCreate().
     */
    private void registerLauncher() {
        permissionLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean allGranted = true;
                    for (Boolean granted : result.values()) {
                        if (!granted) {
                            allGranted = false;
                            break;
                        }
                    }

                    if (allGranted) {
                        Log.d(TAG, "Storage permission granted");
                        callback.onPermissionResult(true);
                    } else {
                        Log.w(TAG, "Storage permission denied");
                        handlePermissionDenied();
                    }
                }
        );
    }

    // =========================================================
    // Public API
    // =========================================================

    /**
     * Cek apakah permission storage sudah ada.
     * Gunakan ini sebelum akses file untuk menghindari SecurityException.
     */
    public boolean hasStoragePermission() {
        return checkPermissions(activity);
    }

    /**
     * Minta permission storage.
     * Akan langsung callback onPermissionResult(true) jika sudah punya permission.
     */
    public void requestStoragePermission() {
        if (hasStoragePermission()) {
            Log.d(TAG, "Permission already granted, skipping request");
            callback.onPermissionResult(true);
            return;
        }

        String[] permissions = getRequiredPermissions();
        Log.d(TAG, "Requesting permissions: " + java.util.Arrays.toString(permissions));
        permissionLauncher.launch(permissions);
    }

    // =========================================================
    // Permission Check
    // =========================================================

    /**
     * Cek secara statis (bisa dipanggil dari mana saja).
     */
    public static boolean checkPermissions(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ — READ_MEDIA_AUDIO
            return ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_MEDIA_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;

        } else {
            // Android 6-12 — READ_EXTERNAL_STORAGE
            return ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    /**
     * Tentukan permission yang perlu diminta berdasarkan versi Android.
     */
    private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ hanya butuh READ_MEDIA_AUDIO
            return new String[]{Manifest.permission.READ_MEDIA_AUDIO};

        } else {
            // Android 6-12
            return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        }
    }

    // =========================================================
    // Handle Denied
    // =========================================================

    private void handlePermissionDenied() {
        boolean canShowRationale = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            canShowRationale = activity.shouldShowRequestPermissionRationale(
                    Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            canShowRationale = activity.shouldShowRequestPermissionRationale(
                    Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        if (canShowRationale) {
            // Pengguna pernah menolak tapi belum pilih "Don't ask again"
            // Tampilkan penjelasan kenapa permission ini dibutuhkan
            showRationaleDialog();
        } else {
            // Pengguna pilih "Don't ask again" — arahkan ke Settings
            showSettingsDialog();
        }
    }

    /**
     * Dialog penjelasan kenapa permission ini penting.
     * Ditampilkan jika pengguna menolak untuk pertama kali.
     */
    private void showRationaleDialog() {
        new AlertDialog.Builder(activity)
                .setTitle("Izin Akses Musik")
                .setMessage(
                        "Airus membutuhkan izin untuk membaca file audio di perangkatmu. " +
                                "Tanpa izin ini, Airus tidak bisa menemukan atau memutar musikmu."
                )
                .setPositiveButton("Coba Lagi", (dialog, which) -> {
                    // Minta ulang permission
                    permissionLauncher.launch(getRequiredPermissions());
                })
                .setNegativeButton("Batal", (dialog, which) -> {
                    callback.onPermissionResult(false);
                })
                .setCancelable(false)
                .show();
    }

    /**
     * Dialog arahkan ke Settings app.
     * Ditampilkan jika pengguna sudah pilih "Don't ask again".
     */
    private void showSettingsDialog() {
        new AlertDialog.Builder(activity)
                .setTitle("Izin Diperlukan")
                .setMessage(
                        "Izin akses musik dinonaktifkan secara permanen. " +
                                "Buka Pengaturan → Aplikasi → Airus → Izin, " +
                                "lalu aktifkan izin 'Musik dan Audio'."
                )
                .setPositiveButton("Buka Pengaturan", (dialog, which) -> {
                    openAppSettings();
                })
                .setNegativeButton("Nanti", (dialog, which) -> {
                    callback.onPermissionResult(false);
                })
                .setCancelable(false)
                .show();
    }

    /**
     * Buka halaman pengaturan izin aplikasi Airus.
     */
    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        Uri uri = Uri.fromParts("package", activity.getPackageName(), null);
        intent.setData(uri);
        activity.startActivity(intent);
    }
}

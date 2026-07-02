package com.zaknong.airus.scanner;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

public class ScanManager {
    private static final String TAG = "ScanManager";
    private static volatile ScanManager INSTANCE;

    public static ScanManager getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (ScanManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ScanManager(context);
                }
            }
        }
        return INSTANCE;
    }

    private final Context context;
    private final MutableLiveData<Boolean> isScanning = new MutableLiveData<>(false);
    private final MutableLiveData<String> scanProgressText = new MutableLiveData<>("");
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ScanManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public LiveData<Boolean> getIsScanning() {
        return isScanning;
    }

    public LiveData<String> getScanProgressText() {
        return scanProgressText;
    }

    public void startScan() {
        mainHandler.post(() -> {
            if (Boolean.TRUE.equals(isScanning.getValue())) {
                Log.d(TAG, "Scan sudah berjalan, abaikan permintaan scan baru.");
                return;
            }

            isScanning.setValue(true);
            scanProgressText.setValue("Memulai pemindaian...");

            MediaScanner scanner = new MediaScanner(context);
            TagEnricher enricher = new TagEnricher(context);

            scanner.getScanProgress().observeForever(new Observer<MediaScanner.ScanProgress>() {
                @Override
                public void onChanged(MediaScanner.ScanProgress progress) {
                    if (progress.isFinished) {
                        scanner.getScanProgress().removeObserver(this);
                        scanProgressText.setValue("Menganalisis metadata lagu...");
                        enricher.enrichAll();
                    } else {
                        scanProgressText.setValue("Memindai berkas audio (" + progress.newSongs + " lagu baru)...");
                    }
                }
            });

            enricher.getEnrichProgress().observeForever(new Observer<TagEnricher.EnrichProgress>() {
                @Override
                public void onChanged(TagEnricher.EnrichProgress progress) {
                    if (progress.isFinished) {
                        enricher.getEnrichProgress().removeObserver(this);
                        isScanning.setValue(false);
                        scanProgressText.setValue("");
                        Log.i(TAG, "Proses pemindaian dan pengayaan metadata selesai.");
                    } else {
                        scanProgressText.setValue("Menganalisis metadata: " + progress.enriched + "/" + progress.total + "\n" + (progress.currentFile != null ? progress.currentFile : ""));
                    }
                }
            });

            scanner.startScan();
        });
    }
}

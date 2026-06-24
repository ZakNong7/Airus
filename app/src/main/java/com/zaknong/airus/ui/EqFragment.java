package com.zaknong.airus.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.zaknong.airus.R;
import com.zaknong.airus.database.AppDatabase;
import com.zaknong.airus.database.entity.EqPreset;
import com.zaknong.airus.service.PlayerService;
import com.zaknong.airus.service.PlayerState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public class EqFragment extends BottomSheetDialogFragment {

    private static final String TAG = "EqFragment";

    // 10 band center frequencies
    private static final float[] FREQ_LABELS = {
            31f, 62f, 125f, 250f, 500f,
            1000f, 2000f, 4000f, 8000f, 16000f
    };

    // Seekbar range: 0–240 = -12dB hingga +12dB
    // Mid (120) = 0dB
    private static final int SEEKBAR_MAX  = 240;
    private static final int SEEKBAR_MID  = 120;
    private static final float DB_RANGE   = 12.0f;

    // =========================================================
    // Views
    // =========================================================
    private SwitchMaterial switchBitPerfect;
    private LinearLayout   presetChips;
    private LinearLayout   eqBandsContainer;
    private SeekBar        seekbarPreamp;
    private TextView       tvPreampValue;

    // Array seekbar per band (10 buah)
    private final SeekBar[] bandSeekbars = new SeekBar[10];
    private final TextView[] bandValues  = new TextView[10];

    // =========================================================
    // Service binding
    // =========================================================
    private PlayerService playerService;
    private boolean       serviceBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            playerService = ((PlayerService.LocalBinder) binder).getService();
            serviceBound  = true;
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    // State
    private EqPreset activePreset;
    private float[]  currentGains = new float[10];
    private float[]  currentFreqs = new float[10];
    private float[]  currentQs    = new float[10];
    private float    currentPreamp = 0f;

    // =========================================================
    // Lifecycle
    // =========================================================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_eq, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        bindViews(view);
        buildBandSliders();
        setupBitPerfectSwitch();
        setupPreampSeekbar();
        setupActionButtons(view);
        loadPresets();
        observePlayerState();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
        }
        requireContext().bindService(
                new Intent(requireContext(), PlayerService.class),
                serviceConnection, Context.BIND_AUTO_CREATE
        );
    }

    @Override
    public void onStop() {
        super.onStop();
        if (serviceBound) {
            requireContext().unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    // =========================================================
    // Bind views
    // =========================================================

    private void bindViews(View root) {
        switchBitPerfect  = root.findViewById(R.id.switch_bitperfect);
        presetChips       = root.findViewById(R.id.preset_chips);
        eqBandsContainer  = root.findViewById(R.id.eq_bands_container);
        seekbarPreamp     = root.findViewById(R.id.seekbar_preamp);
        tvPreampValue     = root.findViewById(R.id.tv_preamp_value);
    }

    // =========================================================
    // Build 10 band sliders secara programatik
    // Kenapa programatik dan bukan XML?
    // Karena 10 band identik — lebih bersih dari copy-paste XML 10x
    // =========================================================

    private void buildBandSliders() {
        eqBandsContainer.removeAllViews();

        for (int i = 0; i < 10; i++) {
            final int bandIndex = i;

            // Satu kolom per band
            LinearLayout column = new LinearLayout(requireContext());
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams colParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            column.setLayoutParams(colParams);

            // Label frekuensi di atas
            TextView freqLabel = new TextView(requireContext());
            freqLabel.setText(formatFreq(FREQ_LABELS[i]));
            freqLabel.setTextColor(getResources().getColor(
                    R.color.text_tertiary, null));
            freqLabel.setTextSize(9f);
            freqLabel.setGravity(android.view.Gravity.CENTER);
            column.addView(freqLabel);

            // Nilai dB di tengah atas slider
            TextView dbValue = new TextView(requireContext());
            dbValue.setText("0.0");
            dbValue.setTextColor(getResources().getColor(
                    R.color.accent_primary, null));
            dbValue.setTextSize(9f);
            dbValue.setGravity(android.view.Gravity.CENTER);
            bandValues[i] = dbValue;
            column.addView(dbValue);

            // SeekBar vertikal
            SeekBar seekBar = new SeekBar(requireContext());
            seekBar.setMax(SEEKBAR_MAX);
            seekBar.setProgress(SEEKBAR_MID); // 0dB
            seekBar.setRotation(270f);        // putar jadi vertikal
            LinearLayout.LayoutParams sbParams = new LinearLayout.LayoutParams(
                    180, 180); // lebar slider saat diputar = tinggi aslinya
            sbParams.gravity = android.view.Gravity.CENTER;
            seekBar.setLayoutParams(sbParams);
            seekBar.setProgressTintList(
                    android.content.res.ColorStateList.valueOf(
                            getResources().getColor(R.color.accent_primary, null)));
            seekBar.setThumb(getResources().getDrawable(
                    R.drawable.seekbar_thumb, null));

            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    float gainDb = progressToDb(progress);
                    bandValues[bandIndex].setText(
                            String.format("%.1f", gainDb));
                    currentGains[bandIndex] = gainDb;

                    if (fromUser && serviceBound) {
                        // Kirim ke engine secara realtime
                        playerService.getAudioEngine().setEqBand(
                                bandIndex,
                                currentFreqs[bandIndex],
                                gainDb,
                                currentQs[bandIndex]
                        );
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });

            bandSeekbars[i] = seekBar;
            column.addView(seekBar);
            eqBandsContainer.addView(column);

            // Init nilai dari FREQ_LABELS
            currentFreqs[i] = FREQ_LABELS[i];
            currentQs[i]    = 1.41f;
            currentGains[i] = 0f;
        }
    }

    // =========================================================
    // Bit-Perfect Switch
    // =========================================================

    private void setupBitPerfectSwitch() {
        // Sync state awal dari PlayerState
        Boolean isBP = PlayerState.getInstance()
                .isBitPerfectActive().getValue();
        switchBitPerfect.setChecked(Boolean.TRUE.equals(isBP));

        // Saat bit-perfect ON → disable semua slider EQ (greyed out)
        updateEqEnabled(!Boolean.TRUE.equals(isBP));

        switchBitPerfect.setOnCheckedChangeListener((btn, isChecked) -> {
            // Toggle via PlayerService agar konsisten dengan state global
            if (serviceBound) playerService.toggleBitPerfect();
            updateEqEnabled(!isChecked);
        });
    }

    private void updateEqEnabled(boolean enabled) {
        float alpha = enabled ? 1.0f : 0.4f;
        eqBandsContainer.setAlpha(alpha);
        seekbarPreamp.setEnabled(enabled);
        for (SeekBar sb : bandSeekbars) {
            if (sb != null) sb.setEnabled(enabled);
        }
    }

    // =========================================================
    // Preamp Seekbar
    // Range: 0–240 → -12dB hingga +12dB (mid=120 → 0dB)
    // =========================================================

    private void setupPreampSeekbar() {
        seekbarPreamp.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int progress,
                                                  boolean fromUser) {
                        float db = progressToDb(progress);
                        tvPreampValue.setText(String.format("%.1f dB", db));
                        currentPreamp = db;
                    }
                    @Override public void onStartTrackingTouch(SeekBar sb) {}
                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                        // Apply preamp ke engine saat selesai drag
                        applyCurrentEqToEngine();
                    }
                });
    }

    // =========================================================
    // Action buttons
    // =========================================================

    private void setupActionButtons(View root) {
        root.findViewById(R.id.btn_reset).setOnClickListener(v -> resetAllBands());
        root.findViewById(R.id.btn_save_preset).setOnClickListener(v -> showSavePresetDialog());
    }

    private void resetAllBands() {
        for (int i = 0; i < 10; i++) {
            bandSeekbars[i].setProgress(SEEKBAR_MID);
            currentGains[i] = 0f;
        }
        seekbarPreamp.setProgress(SEEKBAR_MID);
        currentPreamp = 0f;
        applyCurrentEqToEngine();
    }

    // =========================================================
    // Load presets dari database → tampilkan sebagai Chips
    // =========================================================

    private void loadPresets() {
        AppDatabase.getInstance(requireContext())
                .eqPresetDao()
                .getAllPresets()
                .observe(getViewLifecycleOwner(), presets -> {
                    if (presets == null) return;
                    presetChips.removeAllViews();
                    for (EqPreset preset : presets) {
                        addPresetChip(preset);
                    }
                });
    }

    private void addPresetChip(EqPreset preset) {
        Chip chip = new Chip(requireContext());
        chip.setText(preset.name);
        chip.setCheckable(true);
        chip.setChecked(preset.isActive);
        chip.setChipBackgroundColorResource(
                preset.isActive ? R.color.accent_primary : R.color.black_elevated);
        chip.setTextColor(getResources().getColor(
                preset.isActive ? R.color.black_true : R.color.text_secondary, null));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMarginEnd(8);
        chip.setLayoutParams(params);

        chip.setOnClickListener(v -> applyPreset(preset));
        presetChips.addView(chip);
    }

    private void applyPreset(EqPreset preset) {
        try {
            JSONArray bands = new JSONArray(preset.bandsJson);
            for (int i = 0; i < bands.length() && i < 10; i++) {
                JSONObject band = bands.getJSONObject(i);
                float gain = (float) band.getDouble("gain");
                float freq = (float) band.getDouble("freq");
                float q    = (float) band.getDouble("q");

                currentGains[i] = gain;
                currentFreqs[i] = freq;
                currentQs[i]    = q;

                // Update seekbar UI
                bandSeekbars[i].setProgress(dbToProgress(gain));
            }

            currentPreamp = preset.preampDb;
            seekbarPreamp.setProgress(dbToProgress(preset.preampDb));
            tvPreampValue.setText(String.format("%.1f dB", preset.preampDb));

            applyCurrentEqToEngine();

            // Simpan preset aktif ke database
            AppDatabase.databaseWriteExecutor.execute(() -> {
                AppDatabase db = AppDatabase.getInstance(requireContext());
                db.eqPresetDao().deactivateAllPresets();
                db.eqPresetDao().activatePreset(preset.id);
            });

        } catch (Exception e) {
            android.util.Log.e(TAG, "applyPreset error: " + e.getMessage());
        }
    }

    // =========================================================
    // Apply EQ ke engine
    // =========================================================

    private void applyCurrentEqToEngine() {
        if (!serviceBound || playerService.getAudioEngine() == null) return;
        playerService.getAudioEngine().setEqPreset(
                currentFreqs, currentGains, currentQs, currentPreamp);
    }

    // =========================================================
    // Save Preset Dialog
    // =========================================================

    private void showSavePresetDialog() {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_save_preset, null);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Simpan Preset")
                .setView(dialogView)
                .setPositiveButton("Simpan", (dialog, which) -> {
                    TextInputEditText input = dialogView.findViewById(R.id.et_preset_name);
                    if (input == null) return;
                    String name = input.getText() != null
                            ? input.getText().toString().trim() : "";
                    if (!name.isEmpty()) savePreset(name);
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void savePreset(String name) {
        try {
            JSONArray bandsJson = new JSONArray();
            for (int i = 0; i < 10; i++) {
                JSONObject band = new JSONObject();
                band.put("freq", currentFreqs[i]);
                band.put("gain", currentGains[i]);
                band.put("q",    currentQs[i]);
                band.put("type", i == 0 ? "LOW_SHELF"
                        : i == 9 ? "HIGH_SHELF" : "PEAK");
                bandsJson.put(band);
            }

            EqPreset preset   = new EqPreset();
            preset.name       = name;
            preset.presetType = "USER";
            preset.bandsJson  = bandsJson.toString();
            preset.preampDb   = currentPreamp;
            preset.isActive   = false;
            preset.dateCreated  = System.currentTimeMillis();
            preset.dateModified = preset.dateCreated;

            AppDatabase.databaseWriteExecutor.execute(() ->
                    AppDatabase.getInstance(requireContext())
                            .eqPresetDao()
                            .insertPreset(preset)
            );

        } catch (Exception e) {
            android.util.Log.e(TAG, "savePreset error: " + e.getMessage());
        }
    }

    // =========================================================
    // Observe PlayerState
    // =========================================================

    private void observePlayerState() {
        PlayerState.getInstance()
                .isBitPerfectActive()
                .observe(getViewLifecycleOwner(), isBP -> {
                    switchBitPerfect.setChecked(Boolean.TRUE.equals(isBP));
                    updateEqEnabled(!Boolean.TRUE.equals(isBP));
                });
    }

    // =========================================================
    // Helpers
    // =========================================================

    /** SeekBar progress (0–240) → gain dB (-12.0 hingga +12.0) */
    private float progressToDb(int progress) {
        return (progress - SEEKBAR_MID) * DB_RANGE / SEEKBAR_MID;
    }

    /** Gain dB → SeekBar progress */
    private int dbToProgress(float db) {
        int p = Math.round(db * SEEKBAR_MID / DB_RANGE) + SEEKBAR_MID;
        return Math.max(0, Math.min(SEEKBAR_MAX, p));
    }

    /** Format frekuensi: 1000 → "1k", 31 → "31" */
    private String formatFreq(float freq) {
        if (freq >= 1000f) {
            int k = (int) (freq / 1000f);
            return k + "k";
        }
        return String.valueOf((int) freq);
    }
}
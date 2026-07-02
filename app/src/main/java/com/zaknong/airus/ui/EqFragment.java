package com.zaknong.airus.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
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

    private static final int SEEKBAR_MAX  = 240;
    private static final int SEEKBAR_MID  = 120;
    private static final float DB_RANGE   = 12.0f;

    private SwitchMaterial switchBitPerfect;
    private LinearLayout   presetChips;
    private LinearLayout   eqBandsContainer;
    private SeekBar        seekbarPreamp;
    private TextView       tvPreampValue;

    private final SeekBar[] bandSeekbars = new SeekBar[10];
    private final TextView[] bandValues  = new TextView[10];

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

    private EqPreset activePreset;
    private float[]  currentGains = new float[10];
    private float[]  currentFreqs = new float[10];
    private float[]  currentQs    = new float[10];
    private float    currentPreamp = 0f;

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

    private void bindViews(View root) {
        switchBitPerfect  = root.findViewById(R.id.switch_bitperfect);
        presetChips       = root.findViewById(R.id.preset_chips);
        eqBandsContainer  = root.findViewById(R.id.eq_bands_container);
        seekbarPreamp     = root.findViewById(R.id.seekbar_preamp);
        tvPreampValue     = root.findViewById(R.id.tv_preamp_value);
    }

    private void buildBandSliders() {
        eqBandsContainer.removeAllViews();
        for (int i = 0; i < 10; i++) {
            final int bandIndex = i;
            LinearLayout column = new LinearLayout(requireContext());
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams colParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            column.setLayoutParams(colParams);

            TextView freqLabel = new TextView(requireContext());
            freqLabel.setText(formatFreq(FREQ_LABELS[i]));
            freqLabel.setTextColor(getResources().getColor(R.color.text_tertiary, null));
            freqLabel.setTextSize(9f);
            freqLabel.setGravity(android.view.Gravity.CENTER);
            column.addView(freqLabel);

            TextView dbValue = new TextView(requireContext());
            dbValue.setText("0.0");
            dbValue.setTextColor(getResources().getColor(R.color.accent_primary, null));
            dbValue.setTextSize(9f);
            dbValue.setGravity(android.view.Gravity.CENTER);
            bandValues[i] = dbValue;
            column.addView(dbValue);

            SeekBar seekBar = new SeekBar(requireContext());
            seekBar.setMax(SEEKBAR_MAX);
            seekBar.setProgress(SEEKBAR_MID);
            seekBar.setRotation(270f);
            LinearLayout.LayoutParams sbParams = new LinearLayout.LayoutParams(800, 240);
            sbParams.gravity = android.view.Gravity.CENTER;
            sbParams.setMargins(-280, 80, -280, 80);
            seekBar.setLayoutParams(sbParams);
            seekBar.setPadding(60, 0, 60, 0);

            seekBar.setOnTouchListener((v, event) -> {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return false;
            });

            seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(
                            getResources().getColor(R.color.accent_primary, null)));
            seekBar.setThumb(getResources().getDrawable(R.drawable.seekbar_thumb, null));

            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    float gainDb = progressToDb(progress);
                    bandValues[bandIndex].setText(String.format("%.1f", gainDb));
                    currentGains[bandIndex] = gainDb;
                    if (fromUser && serviceBound) {
                        playerService.getAudioEngine().setEqBand(bandIndex, currentFreqs[bandIndex], gainDb, currentQs[bandIndex]);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });

            bandSeekbars[i] = seekBar;
            column.addView(seekBar);
            eqBandsContainer.addView(column);
            currentFreqs[i] = FREQ_LABELS[i];
            currentQs[i]    = 1.41f;
            currentGains[i] = 0f;
        }
    }

    private void setupBitPerfectSwitch() {
        Boolean isBP = PlayerState.getInstance().isBitPerfectActive().getValue();
        switchBitPerfect.setChecked(Boolean.TRUE.equals(isBP));
        updateEqEnabled(!Boolean.TRUE.equals(isBP));
        switchBitPerfect.setOnCheckedChangeListener((btn, isChecked) -> {
            if (serviceBound) playerService.toggleBitPerfect();
            updateEqEnabled(!isChecked);
        });
    }

    private void updateEqEnabled(boolean enabled) {
        float alpha = enabled ? 1.0f : 0.4f;
        eqBandsContainer.setAlpha(alpha);
        seekbarPreamp.setEnabled(enabled);
        for (SeekBar sb : bandSeekbars) { if (sb != null) sb.setEnabled(enabled); }
    }

    private void setupPreampSeekbar() {
        seekbarPreamp.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                        float db = progressToDb(progress);
                        tvPreampValue.setText(String.format("%.1f dB", db));
                        currentPreamp = db;
                    }
                    @Override public void onStartTrackingTouch(SeekBar sb) {}
                    @Override public void onStopTrackingTouch(SeekBar sb) { applyCurrentEqToEngine(); }
                });
    }

    private void setupActionButtons(View root) {
        root.findViewById(R.id.btn_reset).setOnClickListener(v -> resetToCurrentPreset());
        root.findViewById(R.id.btn_save_preset).setOnClickListener(v -> saveOrUpdatePreset());
        root.findViewById(R.id.btn_add_preset).setOnClickListener(v -> showSavePresetDialog());
    }

    private void resetToCurrentPreset() {
        if (activePreset != null) {
            applyPreset(activePreset);
        } else {
            resetAllBands();
        }
    }

    private void saveOrUpdatePreset() {
        if (activePreset != null && !"SYSTEM".equals(activePreset.presetType)) {
            updatePreset(activePreset);
        } else {
            showSavePresetDialog();
        }
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

    private void loadPresets() {
        AppDatabase.getInstance(requireContext())
                .eqPresetDao()
                .getAllPresets()
                .observe(getViewLifecycleOwner(), presets -> {
                    if (presets == null || presetChips == null) return;
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
        if (preset.isActive) activePreset = preset;
        chip.setChipBackgroundColorResource(preset.isActive ? R.color.accent_primary : R.color.black_elevated);
        chip.setTextColor(getResources().getColor(preset.isActive ? R.color.black_true : R.color.text_secondary, null));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(8);
        chip.setLayoutParams(params);
        chip.setOnClickListener(v -> applyPreset(preset));
        chip.setOnLongClickListener(v -> {
            if ("SYSTEM".equals(preset.presetType)) return false;
            showDeletePresetDialog(preset);
            return true;
        });
        presetChips.addView(chip);
    }

    private void showDeletePresetDialog(EqPreset preset) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Hapus Preset")
                .setMessage("Hapus preset '" + preset.name + "'?")
                .setPositiveButton("Hapus", (dialog, which) -> {
                    AppDatabase.databaseWriteExecutor.execute(() -> AppDatabase.getInstance(requireContext()).eqPresetDao().deletePreset(preset));
                })
                .setNegativeButton("Batal", null)
                .show();
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
                bandSeekbars[i].setProgress(dbToProgress(gain));
            }
            currentPreamp = preset.preampDb;
            seekbarPreamp.setProgress(dbToProgress(preset.preampDb));
            tvPreampValue.setText(String.format("%.1f dB", preset.preampDb));
            applyCurrentEqToEngine();
            AppDatabase.databaseWriteExecutor.execute(() -> {
                AppDatabase db = AppDatabase.getInstance(requireContext());
                db.eqPresetDao().deactivateAllPresets();
                db.eqPresetDao().activatePreset(preset.id);
            });
        } catch (Exception e) {
            Log.e(TAG, "applyPreset error: " + e.getMessage());
        }
    }

    private void applyCurrentEqToEngine() {
        if (!serviceBound || playerService.getAudioEngine() == null) return;
        playerService.getAudioEngine().setEqPreset(currentFreqs, currentGains, currentQs, currentPreamp);
    }

    private void showSavePresetDialog() {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_save_preset, null);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Simpan Preset Baru")
                .setView(dialogView)
                .setPositiveButton("Simpan", (dialog, which) -> {
                    TextInputEditText input = dialogView.findViewById(R.id.et_preset_name);
                    if (input != null && input.getText() != null) {
                        String name = input.getText().toString().trim();
                        if (!name.isEmpty()) savePreset(name);
                    }
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
                band.put("type", i == 0 ? "LOW_SHELF" : i == 9 ? "HIGH_SHELF" : "PEAK");
                bandsJson.put(band);
            }
            EqPreset preset   = new EqPreset();
            preset.name       = name;
            preset.presetType = "USER";
            preset.bandsJson  = bandsJson.toString();
            preset.preampDb   = currentPreamp;
            preset.isActive   = true;
            preset.dateCreated  = System.currentTimeMillis();
            preset.dateModified = preset.dateCreated;

            AppDatabase.databaseWriteExecutor.execute(() -> {
                AppDatabase db = AppDatabase.getInstance(requireContext());
                db.eqPresetDao().deactivateAllPresets();
                db.eqPresetDao().insertPreset(preset);
            });
        } catch (Exception e) {
            Log.e(TAG, "savePreset error: " + e.getMessage());
        }
    }

    private void updatePreset(EqPreset preset) {
        try {
            JSONArray bandsJson = new JSONArray();
            for (int i = 0; i < 10; i++) {
                JSONObject band = new JSONObject();
                band.put("freq", currentFreqs[i]);
                band.put("gain", currentGains[i]);
                band.put("q",    currentQs[i]);
                band.put("type", i == 0 ? "LOW_SHELF" : i == 9 ? "HIGH_SHELF" : "PEAK");
                bandsJson.put(band);
            }
            preset.bandsJson = bandsJson.toString();
            preset.preampDb = currentPreamp;
            preset.dateModified = System.currentTimeMillis();
            AppDatabase.databaseWriteExecutor.execute(() -> AppDatabase.getInstance(requireContext()).eqPresetDao().updatePreset(preset));
        } catch (Exception e) {
            Log.e(TAG, "updatePreset error: " + e.getMessage());
        }
    }

    private void observePlayerState() {
        PlayerState.getInstance().isBitPerfectActive().observe(getViewLifecycleOwner(), isBP -> {
            if (switchBitPerfect != null) {
                switchBitPerfect.setChecked(Boolean.TRUE.equals(isBP));
                updateEqEnabled(!Boolean.TRUE.equals(isBP));
            }
        });
    }

    private float progressToDb(int progress) {
        return (progress - SEEKBAR_MID) * DB_RANGE / SEEKBAR_MID;
    }

    private int dbToProgress(float db) {
        int p = Math.round(db * SEEKBAR_MID / DB_RANGE) + SEEKBAR_MID;
        return Math.max(0, Math.min(SEEKBAR_MAX, p));
    }

    private String formatFreq(float freq) {
        if (freq >= 1000f) return ((int) (freq / 1000f)) + "k";
        return String.valueOf((int) freq);
    }
}

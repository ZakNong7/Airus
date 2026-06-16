package com.zaknong.airus.engine;

import android.util.Log;

/**
 * AudioFormatRouter
 *
 * Menentukan jalur decoding yang tepat untuk setiap format audio.
 *
 * Ada dua jalur:
 *
 * JALUR A — MediaCodec (Java/Android framework):
 *   Digunakan untuk format lossy dan ALAC.
 *   Keuntungan: hardware-accelerated, hemat baterai.
 *   Format: MP3, AAC, M4A, OGG Vorbis, OPUS, ALAC, WMA
 *
 * JALUR B — Native C++ (Oboe + custom decoders):
 *   Digunakan untuk format yang butuh bit-perfect atau tidak didukung MediaCodec.
 *   Keuntungan: bit-perfect, kontrol penuh, bypass Android audio mixer.
 *   Format: FLAC, WAV, AIFF, DSD, WavPack
 *
 * Kenapa MP3 masuk jalur MediaCodec?
 *   Karena MP3 adalah lossy — data sudah "rusak" sejak encoding.
 *   Bit-perfect output MP3 tidak relevan (tidak ada bit yang perlu dijaga).
 *   Hardware decoder jauh lebih efisien dari software decoder C++.
 *
 * Kenapa ALAC masuk jalur MediaCodec?
 *   Android punya hardware ALAC decoder sejak API 16.
 *   Meski ALAC lossless, hasil decode ke PCM identik dengan decoder mana pun.
 *   Gunakan hardware decoder untuk hemat baterai.
 *   CATATAN: Untuk bit-perfect output, sample rate tetap dijaga oleh Oboe.
 */
public class AudioFormatRouter {

    private static final String TAG = "AudioFormatRouter";

    public enum DecoderPath {
        MEDIA_CODEC,   // Android MediaCodec (hardware)
        NATIVE_FLAC,   // libFLAC via JNI
        NATIVE_WAV,    // custom PCM reader
        NATIVE_DSD,    // DSD decoder + DoP encoder
        NATIVE_WAVPACK // libWavPack
    }

    /**
     * Info tentang sebuah format audio untuk keperluan routing dan UI.
     */
    public static class FormatInfo {
        public final String extension;
        public final DecoderPath decoderPath;
        public final boolean isLossless;
        public final boolean canBeHiRes;   // apakah format ini bisa hi-res?
        public final boolean isDsd;
        public final String displayName;   // nama yang ditampilkan di UI

        public FormatInfo(String extension, DecoderPath decoderPath,
                          boolean isLossless, boolean canBeHiRes,
                          boolean isDsd, String displayName) {
            this.extension    = extension;
            this.decoderPath  = decoderPath;
            this.isLossless   = isLossless;
            this.canBeHiRes   = canBeHiRes;
            this.isDsd        = isDsd;
            this.displayName  = displayName;
        }
    }

    /**
     * Daftar semua format yang didukung Airus.
     */
    private static final FormatInfo[] SUPPORTED_FORMATS = {
            // ---- Lossless Hi-Res (Jalur Native) ----
            new FormatInfo("flac",  DecoderPath.NATIVE_FLAC,    true,  true,  false, "FLAC"),
            new FormatInfo("wav",   DecoderPath.NATIVE_WAV,     true,  true,  false, "WAV"),
            new FormatInfo("aiff",  DecoderPath.NATIVE_WAV,     true,  true,  false, "AIFF"),
            new FormatInfo("aif",   DecoderPath.NATIVE_WAV,     true,  true,  false, "AIFF"),
            new FormatInfo("dsf",   DecoderPath.NATIVE_DSD,     true,  true,  true,  "DSD"),
            new FormatInfo("dff",   DecoderPath.NATIVE_DSD,     true,  true,  true,  "DSD"),
            new FormatInfo("wv",    DecoderPath.NATIVE_WAVPACK, true,  true,  false, "WavPack"),

            // ---- Lossless (Jalur MediaCodec) ----
            new FormatInfo("alac",  DecoderPath.MEDIA_CODEC,    true,  true,  false, "ALAC"),
            new FormatInfo("m4a",   DecoderPath.MEDIA_CODEC,    false, false, false, "ALAC/AAC"), // bisa keduanya

            // ---- Lossy (Jalur MediaCodec) ----
            new FormatInfo("mp3",   DecoderPath.MEDIA_CODEC,    false, false, false, "MP3"),
            new FormatInfo("aac",   DecoderPath.MEDIA_CODEC,    false, false, false, "AAC"),
            new FormatInfo("ogg",   DecoderPath.MEDIA_CODEC,    false, false, false, "OGG"),
            new FormatInfo("oga",   DecoderPath.MEDIA_CODEC,    false, false, false, "OGG"),
            new FormatInfo("opus",  DecoderPath.MEDIA_CODEC,    false, false, false, "OPUS"),
            new FormatInfo("wma",   DecoderPath.MEDIA_CODEC,    false, false, false, "WMA"),
            new FormatInfo("mp4",   DecoderPath.MEDIA_CODEC,    false, false, false, "AAC"),
    };

    /**
     * Dapatkan FormatInfo berdasarkan ekstensi file.
     *
     * @param filePath path lengkap atau nama file
     * @return FormatInfo, atau null jika format tidak didukung
     */
    public static FormatInfo getFormatInfo(String filePath) {
        if (filePath == null) return null;
        String ext = getExtension(filePath).toLowerCase();
        for (FormatInfo info : SUPPORTED_FORMATS) {
            if (info.extension.equals(ext)) {
                return info;
            }
        }
        Log.w(TAG, "Format tidak dikenal: " + ext + " untuk file: " + filePath);
        return null;
    }

    /**
     * Cek apakah file ini didukung Airus.
     */
    public static boolean isSupported(String filePath) {
        return getFormatInfo(filePath) != null;
    }

    /**
     * Cek apakah file ini butuh decoder native C++.
     */
    public static boolean needsNativeDecoder(String filePath) {
        FormatInfo info = getFormatInfo(filePath);
        if (info == null) return false;
        return info.decoderPath != DecoderPath.MEDIA_CODEC;
    }

    /**
     * Semua ekstensi yang didukung, untuk MediaScanner.
     * Contoh penggunaan:
     *   String[] exts = AudioFormatRouter.getSupportedExtensions();
     *   // Filter file di folder dengan ekstensi ini
     */
    public static String[] getSupportedExtensions() {
        String[] exts = new String[SUPPORTED_FORMATS.length];
        for (int i = 0; i < SUPPORTED_FORMATS.length; i++) {
            exts[i] = SUPPORTED_FORMATS[i].extension;
        }
        return exts;
    }

    private static String getExtension(String path) {
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == path.length() - 1) return "";
        return path.substring(dotIndex + 1);
    }
}

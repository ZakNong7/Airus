/**
 * GaplessBuffer.h
 * Airus Audio Engine — Gapless Playback + DoP (DSD over PCM)
 *
 * File ini mendefinisikan dua komponen native C++ kritis:
 *  1. GaplessBuffer — ring buffer untuk pre-decode lagu berikutnya
 *  2. DoPEncoder  — mengemas DSD bit stream ke dalam PCM frame (DoP standard)
 */

#pragma once
#include <cstdint>
#include <cstring>
#include <memory>
#include <atomic>
#include <functional>

namespace airus {

// =============================================================
// BAGIAN 1: GAPLESS BUFFER
// =============================================================

/**
 * GaplessBuffer
 *
 * Cara kerja gapless playback:
 *
 *  Timeline audio:
 *  |=== Lagu A ===|=== Lagu B ===|=== Lagu C ===|
 *
 *  Strategi:
 *  - Saat Lagu A tersisa ~5 detik, mulai decode Lagu B ke buffer
 *  - Saat Lagu A habis, Oboe langsung ambil sample dari buffer Lagu B
 *  - Tidak ada gap, tidak ada silence, tidak ada re-init audio stream
 *
 *  Buffer dual (ping-pong):
 *  [Buffer A: Lagu sedang main] [Buffer B: Pre-decode lagu berikutnya]
 *  Saat lagu berganti, swap pointer. Buffer A jadi kosong, mulai decode lagu C.
 *
 *  PENTING: Bit depth dan sample rate Lagu A & B harus sama untuk gapless.
 *  Jika berbeda (misal 44.1kHz → 96kHz), harus ada resampling atau
 *  re-init audio stream (yang akan menyebabkan gap kecil ~100ms).
 *  Engine harus deteksi ini dan tampilkan warning di UI.
 */
    class GaplessBuffer {
    public:
        // Ukuran pre-decode buffer: 8 detik audio @ 192kHz stereo 32-bit float
        // = 192000 * 2 ch * 4 bytes * 8 detik = ~12 MB per buffer
        // Kita gunakan 5 detik untuk menghemat RAM: ~7.5 MB
        static constexpr int PRE_DECODE_SECONDS = 5;
        static constexpr int MAX_CHANNELS       = 2;
        static constexpr int MAX_SAMPLE_RATE    = 384000;
        static constexpr int FLOAT_BYTES        = 4;

        static constexpr size_t BUFFER_CAPACITY =
                PRE_DECODE_SECONDS * MAX_CHANNELS * MAX_SAMPLE_RATE * FLOAT_BYTES;

        /**
         * State buffer pre-decode lagu berikutnya.
         */
        enum class NextTrackState {
            EMPTY,       // belum ada pre-decode
            DECODING,    // sedang di-decode di background thread
            READY,       // siap diputar
            ERROR        // gagal decode
        };

        /**
         * Callback dipanggil oleh engine saat:
         * - nextTrackState berubah ke READY → swap buffer
         * - Buffer primary hampir habis → trigger decode lagu berikutnya
         */
        using OnPreDecodeNeededCallback = std::function<void(int64_t songId)>;
        using OnSwapReadyCallback       = std::function<void()>;

        explicit GaplessBuffer(int sampleRate, int channels, int bitDepth);
        ~GaplessBuffer();

        // Tidak boleh di-copy
        GaplessBuffer(const GaplessBuffer&) = delete;
        GaplessBuffer& operator=(const GaplessBuffer&) = delete;

        /**
         * Tulis decoded samples ke buffer pre-decode (dipanggil dari decoder thread).
         * Format: float 32-bit normalized [-1.0, 1.0], interleaved channels.
         *
         * @param samples  pointer ke array float
         * @param count    jumlah sample (bukan bytes)
         * @return jumlah sample yang berhasil ditulis
         */
        size_t writeNextTrack(const float* samples, size_t count);

        /**
         * Baca samples dari buffer aktif (dipanggil dari Oboe audio callback).
         * Thread-safe dengan atomic operations.
         *
         * @param output  buffer output
         * @param frames  jumlah frame yang diminta (1 frame = semua channel)
         */
        void readFrames(float* output, size_t frames);

        /**
         * Swap buffer: buffer pre-decode menjadi buffer aktif.
         * Dipanggil saat lagu sedang main hampir habis dan buffer next sudah READY.
         */
        void swapBuffers();

        /**
         * Jumlah frame tersisa di buffer aktif.
         * Ketika <= (PRE_DECODE_SECONDS * sampleRate), trigger pre-decode.
         */
        size_t framesRemaining() const;

        /**
         * Reset buffer (stop playback, clear semua data).
         */
        void reset();

        NextTrackState getNextTrackState() const { return nextTrackState.load(); }
        void setNextTrackState(NextTrackState state) { nextTrackState.store(state); }

        void setOnPreDecodeNeeded(OnPreDecodeNeededCallback cb) { onPreDecodeNeeded = cb; }
        void setOnSwapReady(OnSwapReadyCallback cb) { onSwapReady = cb; }

    private:
        int sampleRate;
        int channels;
        int bitDepth;

        // Dual buffer (ping-pong)
        std::unique_ptr<float[]> bufferA;   // buffer aktif (sedang diputar)
        std::unique_ptr<float[]> bufferB;   // buffer pre-decode lagu berikutnya

        // Read/write positions
        std::atomic<size_t> readPos{0};
        std::atomic<size_t> writePos{0};
        std::atomic<size_t> nextWritePos{0};
        std::atomic<size_t> nextWritten{0};

        std::atomic<NextTrackState> nextTrackState{NextTrackState::EMPTY};

        // Threshold: mulai pre-decode saat tersisa N frame
        size_t preDecodeThresholdFrames;

        OnPreDecodeNeededCallback onPreDecodeNeeded;
        OnSwapReadyCallback onSwapReady;
    };


// =============================================================
// BAGIAN 2: DSD OVER PCM (DoP) ENCODER
// =============================================================

/**
 * DoPEncoder — DSD over PCM v1.1
 *
 * LATAR BELAKANG:
 * Android USB Audio framework (UAC2) hanya memahami format PCM.
 * DAC eksternal yang support DSD native memerlukan sinyal DSD dikemas
 * di dalam frame PCM 32-bit dengan penanda khusus.
 *
 * CARA KERJA DoP (standar dari dsd-audio.com):
 *
 *  DSD stream: bit mentah 1-bit, 2822400 sample/detik (DSD64)
 *
 *  DSD64 → DoP via PCM 352.8kHz / 24-bit:
 *  - Setiap frame PCM 24-bit berisi 16 bit DSD (8 bit per channel)
 *  - Byte marker (0x05 atau 0xFA) ditempatkan di MSB untuk identifikasi
 *
 *  Layout satu frame PCM 32-bit (DoP):
 *  ┌─────────────────────────────────────────────┐
 *  │ Bit 31-24: Marker (0x05 atau 0xFA, alternating)│
 *  │ Bit 23-16: DSD bits sample N+1  (8 bit)     │
 *  │ Bit 15-8 : DSD bits sample N    (8 bit)     │
 *  │ Bit  7-0 : (unused/zero)                    │
 *  └─────────────────────────────────────────────┘
 *
 *  Jika DAC support DSD native via DoP, ia akan deteksi marker 0x05/0xFA
 *  dan treat data sebagai DSD bukan PCM biasa.
 *  Jika DAC tidak support DoP, ia akan memutar sinyal sebagai PCM — hasilnya
 *  noise/sampah, yang menjadi indikator bagi kita bahwa DAC tidak support DSD.
 *
 * SAMPLE RATE yang dibutuhkan:
 *  - DSD64  (2.8 MHz)  → PCM 176.4 kHz (2 * DSD rate / 32 bits)
 *  - DSD128 (5.6 MHz)  → PCM 352.8 kHz
 *  - DSD256 (11.2 MHz) → PCM 705.6 kHz (beberapa DAC tidak support ini)
 *
 * CATATAN: DAC harus support sample rate PCM 176.4kHz atau 352.8kHz.
 * Tidak semua USB DAC Android support ini. Perlu cek via AudioManager.
 */
    class DoPEncoder {
    public:
        // Marker yang bergantian di setiap frame untuk identifikasi DoP
        static constexpr uint8_t DOP_MARKER_A = 0x05;
        static constexpr uint8_t DOP_MARKER_B = 0xFA;

        /**
         * DSD rate:
         * DSD64  = 1x  (2,822,400 bit/detik per channel)
         * DSD128 = 2x
         * DSD256 = 4x
         */
        enum class DsdRate { DSD64, DSD128, DSD256 };

        explicit DoPEncoder(DsdRate rate);

        /**
         * Encode satu frame DSD (kiri + kanan) menjadi frame PCM DoP.
         *
         * @param dsdLeft   8 bit DSD untuk channel kiri
         * @param dsdRight  8 bit DSD untuk channel kanan
         * @param outLeft   output int32 untuk channel kiri (32-bit PCM frame)
         * @param outRight  output int32 untuk channel kanan
         */
        void encodeFrame(uint8_t dsdLeft, uint8_t dsdRight,
                         int32_t& outLeft, int32_t& outRight);

        /**
         * Encode batch: array DSD bytes → array PCM int32 frames.
         *
         * @param dsdData    array input DSD bytes (interleaved L/R)
         * @param dsdBytes   jumlah bytes input
         * @param pcmOut     array output PCM int32 frames
         * @param pcmFrames  kapasitas output (jumlah frames)
         * @return jumlah PCM frames yang dihasilkan
         */
        size_t encodeBatch(const uint8_t* dsdData, size_t dsdBytes,
                           int32_t* pcmOut, size_t pcmFrames);

        /**
         * Cek apakah sample rate PCM tertentu cukup untuk DSD rate ini.
         * @param pcmSampleRate sample rate dalam Hz
         */
        bool isSampleRateSufficient(int pcmSampleRate) const;

        /**
         * Sample rate PCM yang dibutuhkan untuk DSD rate ini.
         */
        int getRequiredPcmSampleRate() const;

        /**
         * Reset state encoder (marker kembali ke 0x05).
         */
        void reset();

    private:
        DsdRate rate;
        bool useMarkerA{true};   // toggle setiap frame

        static int dsdRateToBaseHz(DsdRate rate);
    };

} // namespace airus

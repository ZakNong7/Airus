// app/src/main/cpp/decoder/WavDecoder.h
#pragma once

#include <string>
#include <vector>
#include <cstdint>
#include <cstdio>
#include <android/log.h>

#define WAV_LOG_TAG "WavDecoder"
#define WAV_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  WAV_LOG_TAG, __VA_ARGS__)
#define WAV_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, WAV_LOG_TAG, __VA_ARGS__)

namespace airus {

/**
 * WavDecoder
 *
 * Membaca file WAV/AIFF dan decode ke float32 stereo.
 *
 * Support:
 * - PCM 16-bit (paling umum)
 * - PCM 24-bit (hi-res)
 * - PCM 32-bit integer
 * - Float 32-bit
 * - Mono dan stereo
 *
 * Tidak support: compressed WAV (GSM, ADPCM, dll)
 */
    class WavDecoder {
    public:

        struct WavInfo {
            int     sampleRate   = 0;
            int     bitDepth     = 0;
            int     channels     = 0;
            int     audioFormat  = 0;  // 1=PCM, 3=IEEE float
            long    totalFrames  = 0;
            long    dataOffset   = 0;  // byte offset ke data chunk
            long    dataSize     = 0;  // ukuran data chunk dalam bytes
            bool    valid        = false;
        };

        WavDecoder() = default;
        ~WavDecoder() { close(); }

        // Tidak boleh di-copy
        WavDecoder(const WavDecoder&) = delete;
        WavDecoder& operator=(const WavDecoder&) = delete;

        /**
         * Buka file WAV dan baca header.
         * @return true jika berhasil dan format valid
         */
        bool open(const std::string& filePath, int fd);
        void close();

        /**
         * Decode seluruh file ke buffer float32 stereo interleaved.
         * Dipanggil dari background thread, bukan audio thread.
         *
         * @param outBuffer  output buffer float32 [-1.0, 1.0]
         * @return jumlah sample yang didecode (frames * channels)
         */
        size_t decodeAll(std::vector<float>& outBuffer);

        /**
         * Decode sebagian — untuk streaming decode (gapless).
         * @param outBuffer  output buffer
         * @param maxFrames  jumlah frame maksimal yang didecode
         * @return jumlah frame yang didecode, 0 = EOF
         */
        size_t decodeFrames(std::vector<float>& outBuffer, size_t maxFrames);

        /**
         * Seek ke frame tertentu.
         */
        bool seekToFrame(long frameIndex);

        const WavInfo& getInfo() const { return info; }
        bool isOpen() const { return file != nullptr; }

    private:
        FILE*   file = nullptr;
        WavInfo info;

        // Posisi frame saat ini
        long currentFrame = 0;
        std::vector<uint8_t> readBuffer;

        bool parseHeader();
        bool findChunk(const char* chunkId, uint32_t& chunkSize);

        // Konversi sample ke float32
        float pcm16ToFloat(int16_t sample);
        float pcm24ToFloat(const uint8_t* bytes);
        float pcm32ToFloat(int32_t sample);

        // Read helpers — handle endianness
        uint16_t readU16LE();
        uint32_t readU32LE();
        int16_t  readI16LE();
        int32_t  readI32LE();

        float convertToFloat(const uint8_t* bytes);
    };

} // namespace airus
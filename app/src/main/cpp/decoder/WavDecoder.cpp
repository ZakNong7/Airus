// app/src/main/cpp/decoder/WavDecoder.cpp

#include "WavDecoder.h"
#include <cstring>
#include <algorithm>
#include <unistd.h>

namespace airus {

// =========================================================
// Open + Parse Header
// =========================================================

    bool WavDecoder::open(const std::string& filePath, int fd) {
        close();

        if (fd >= 0) {
            int dupFd = ::dup(fd);
            WAV_LOGI("WavDecoder::open: Membuka dupFd=%d (dari fd=%d) via fdopen", dupFd, fd);
            file = fdopen(dupFd, "rb");
        } else {
            WAV_LOGI("WavDecoder::open: Membuka path=%s via fopen", filePath.c_str());
            file = fopen(filePath.c_str(), "rb");
        }
        if (!file) {
            WAV_LOGE("Gagal buka file WAV: %s (fd=%d), errno=%d (%s)", filePath.c_str(), fd, errno, strerror(errno));
            return false;
        }

        if (!parseHeader()) {
            WAV_LOGE("Header WAV tidak valid: %s", filePath.c_str());
            close();
            return false;
        }

        readBuffer.resize(262144); // 256KB reuse buffer

        WAV_LOGI("WAV opened: %dHz / %dbit / %dch | %ld frames",
                 info.sampleRate, info.bitDepth,
                 info.channels, info.totalFrames);
        return true;
    }

    void WavDecoder::close() {
        if (file) {
            fclose(file);
            file = nullptr;
        }
        info = WavInfo{};
        readBuffer.clear();
        readBuffer.shrink_to_fit();
        currentFrame = 0;
    }

    bool WavDecoder::parseHeader() {
        // ---- RIFF header ----
        char riff[4];
        fread(riff, 1, 4, file);
        if (strncmp(riff, "RIFF", 4) != 0) {
            WAV_LOGE("Bukan file RIFF");
            return false;
        }

        readU32LE(); // file size (skip)

        char wave[4];
        fread(wave, 1, 4, file);
        if (strncmp(wave, "WAVE", 4) != 0) {
            WAV_LOGE("Bukan format WAVE");
            return false;
        }

        // ---- Cari chunk fmt  ----
        uint32_t fmtSize = 0;
        if (!findChunk("fmt ", fmtSize)) {
            WAV_LOGE("Chunk fmt tidak ditemukan");
            return false;
        }

        info.audioFormat = readU16LE(); // 1=PCM, 3=IEEE float
        info.channels    = readU16LE();
        info.sampleRate  = readU32LE();
        readU32LE();                    // byte rate (skip)
        readU16LE();                    // block align (skip)
        info.bitDepth    = readU16LE();

        // Validasi format
        if (info.audioFormat != 1 && info.audioFormat != 3) {
            WAV_LOGE("Format WAV tidak didukung: %d (hanya PCM/Float)",
                     info.audioFormat);
            return false;
        }

        if (info.channels < 1 || info.channels > 2) {
            WAV_LOGE("Jumlah channel tidak didukung: %d", info.channels);
            return false;
        }

        if (info.bitDepth != 16 && info.bitDepth != 24 &&
            info.bitDepth != 32) {
            WAV_LOGE("Bit depth tidak didukung: %d", info.bitDepth);
            return false;
        }

        // Skip sisa fmt chunk jika ada extension
        if (fmtSize > 16) {
            fseek(file, fmtSize - 16, SEEK_CUR);
        }

        // ---- Cari chunk data ----
        uint32_t dataSize = 0;
        if (!findChunk("data", dataSize)) {
            WAV_LOGE("Chunk data tidak ditemukan");
            return false;
        }

        info.dataOffset = ftell(file);
        info.dataSize   = dataSize;

        // Hitung total frames
        int bytesPerSample = info.bitDepth / 8;
        int bytesPerFrame  = bytesPerSample * info.channels;
        info.totalFrames   = dataSize / bytesPerFrame;
        info.valid         = true;

        return true;
    }

    bool WavDecoder::findChunk(const char* chunkId, uint32_t& chunkSize) {
        char id[4];
        while (fread(id, 1, 4, file) == 4) {
            chunkSize = readU32LE();
            if (strncmp(id, chunkId, 4) == 0) {
                return true;
            }
            // Skip chunk ini
            fseek(file, chunkSize, SEEK_CUR);
        }
        return false;
    }

// =========================================================
// Decode
// =========================================================

    size_t WavDecoder::decodeAll(std::vector<float>& outBuffer) {
        if (!info.valid || !file) return 0;

        // Seek ke awal data
        fseek(file, info.dataOffset, SEEK_SET);
        currentFrame = 0;

        // Reserve buffer: totalFrames * 2 channels (stereo)
        size_t totalSamples = info.totalFrames * 2;
        outBuffer.clear();
        outBuffer.reserve(totalSamples);

        return decodeFrames(outBuffer, info.totalFrames);
    }

    size_t WavDecoder::decodeFrames(std::vector<float>& outBuffer,
                                    size_t maxFrames) {
        if (!info.valid || !file || readBuffer.empty()) return 0;

        size_t framesToDecode = std::min(maxFrames, (size_t)(info.totalFrames - currentFrame));
        if (framesToDecode == 0) return 0;

        int bytesPerSample = info.bitDepth / 8;
        int bytesPerFrame = bytesPerSample * info.channels;
        size_t bytesToRead = framesToDecode * bytesPerFrame;

        if (bytesToRead > readBuffer.size()) {
            bytesToRead = readBuffer.size() - (readBuffer.size() % bytesPerFrame);
            framesToDecode = bytesToRead / bytesPerFrame;
        }

        size_t bytesRead = fread(readBuffer.data(), 1, bytesToRead, file);
        size_t actualFrames = bytesRead / bytesPerFrame;
        if (actualFrames == 0) return 0;

        size_t currentSize = outBuffer.size();
        outBuffer.resize(currentSize + actualFrames * 2);
        float* out = outBuffer.data() + currentSize;

        const uint8_t* p = readBuffer.data();

        if (info.bitDepth == 16) {
            // PCM 16-bit
            const int16_t* src = reinterpret_cast<const int16_t*>(p);
            if (info.channels == 2) {
                for (size_t f = 0; f < actualFrames; f++) {
                    *out++ = src[f * 2] / 32768.0f;
                    *out++ = src[f * 2 + 1] / 32768.0f;
                }
            } else {
                for (size_t f = 0; f < actualFrames; f++) {
                    float val = src[f] / 32768.0f;
                    *out++ = val;
                    *out++ = val;
                }
            }
        } else if (info.bitDepth == 32 && info.audioFormat == 3) {
            // IEEE Float 32-bit: Direct copy / fast unpack
            const float* src = reinterpret_cast<const float*>(p);
            if (info.channels == 2) {
                std::memcpy(out, src, actualFrames * 2 * sizeof(float));
            } else {
                for (size_t f = 0; f < actualFrames; f++) {
                    *out++ = src[f];
                    *out++ = src[f];
                }
            }
        } else if (info.bitDepth == 32) {
            // PCM 32-bit Integer
            const int32_t* src = reinterpret_cast<const int32_t*>(p);
            if (info.channels == 2) {
                for (size_t f = 0; f < actualFrames; f++) {
                    *out++ = src[f * 2] / 2147483648.0f;
                    *out++ = src[f * 2 + 1] / 2147483648.0f;
                }
            } else {
                for (size_t f = 0; f < actualFrames; f++) {
                    float val = src[f] / 2147483648.0f;
                    *out++ = val;
                    *out++ = val;
                }
            }
        } else if (info.bitDepth == 24) {
            // PCM 24-bit
            for (size_t f = 0; f < actualFrames; f++) {
                int32_t s1 = p[0] | (p[1] << 8) | (p[2] << 16);
                if (s1 & 0x800000) s1 |= 0xFF000000;
                *out++ = s1 / 8388608.0f;
                p += 3;
                if (info.channels == 2) {
                    int32_t s2 = p[0] | (p[1] << 8) | (p[2] << 16);
                    if (s2 & 0x800000) s2 |= 0xFF000000;
                    *out++ = s2 / 8388608.0f;
                    p += 3;
                } else {
                    *out++ = s1 / 8388608.0f;
                }
            }
        } else {
            // Fallback
            for (size_t f = 0; f < actualFrames; f++) {
                float left = convertToFloat(p);
                p += bytesPerSample;
                float right = (info.channels == 2) ? convertToFloat(p) : left;
                if (info.channels == 2) p += bytesPerSample;

                *out++ = left;
                *out++ = right;
            }
        }

        currentFrame += (long)actualFrames;
        return actualFrames;
    }

    float WavDecoder::convertToFloat(const uint8_t* bytes) {
        switch (info.bitDepth) {
            case 16: {
                int16_t s;
                std::memcpy(&s, bytes, 2);
                return s / 32768.0f;
            }
            case 24: {
                // 24-bit: 3 bytes little-endian, sign-extend ke 32-bit
                int32_t s = bytes[0] | (bytes[1] << 8) | (bytes[2] << 16);
                if (s & 0x800000) s |= 0xFF000000; // sign extend
                return s / 8388608.0f;
            }
            case 32: {
                if (info.audioFormat == 3) {
                    // IEEE float 32-bit — langsung copy
                    float f;
                    std::memcpy(&f, bytes, 4);
                    return f;
                } else {
                    // PCM 32-bit integer
                    int32_t s;
                    std::memcpy(&s, bytes, 4);
                    return s / 2147483648.0f;
                }
            }
            default:
                return 0.0f;
        }
    }

    bool WavDecoder::seekToFrame(long frameIndex) {
        if (!info.valid || !file) return false;
        if (frameIndex < 0 || frameIndex >= info.totalFrames) return false;

        int bytesPerSample = info.bitDepth / 8;
        int bytesPerFrame  = bytesPerSample * info.channels;
        long byteOffset    = info.dataOffset + (frameIndex * bytesPerFrame);

        if (fseek(file, byteOffset, SEEK_SET) != 0) return false;
        currentFrame = frameIndex;
        return true;
    }

// =========================================================
// Read helpers
// =========================================================

    uint16_t WavDecoder::readU16LE() {
        uint8_t b[2];
        fread(b, 1, 2, file);
        return (uint16_t)(b[0] | (b[1] << 8));
    }

    uint32_t WavDecoder::readU32LE() {
        uint8_t b[4];
        fread(b, 1, 4, file);
        return (uint32_t)(b[0] | (b[1] << 8) | (b[2] << 16) | (b[3] << 24));
    }

    int16_t WavDecoder::readI16LE() {
        return (int16_t)readU16LE();
    }

    int32_t WavDecoder::readI32LE() {
        return (int32_t)readU32LE();
    }

} // namespace airus
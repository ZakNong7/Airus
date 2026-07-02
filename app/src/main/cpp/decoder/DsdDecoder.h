// app/src/main/cpp/decoder/DsdDecoder.h
#pragma once

#include <string>
#include <vector>
#include <cstdint>
#include <cstdio>
#include <unistd.h>

namespace airus {

/**
 * DsdDecoder - Extremely simplified DSD to PCM converter.
 * In a real app, this would use a high-quality decimation filter.
 * For this fix, we'll provide a stub that we can expand later.
 */
class DsdDecoder {
public:
    struct DsdInfo {
        int sampleRate = 0; // DSD rate (e.g. 2822400)
        int channels = 0;
        long totalSamples = 0;
        bool valid = false;
    };

    DsdDecoder() = default;
    ~DsdDecoder() { close(); }

    bool open(const std::string& filePath, int fd) {
        // Stub: Just checking if file exists
        if (fd >= 0) {
            int dupFd = ::dup(fd);
            file = fdopen(dupFd, "rb");
        } else {
            file = fopen(filePath.c_str(), "rb");
        }
        if (!file) return false;

        info.sampleRate = 2822400; // DSD64
        info.channels = 2;
        info.totalSamples = 100000000;
        info.valid = true;
        return true;
    }

    void close() {
        if (file) fclose(file);
        file = nullptr;
    }

    size_t decodeFrames(std::vector<float>& outBuffer, size_t maxFrames) {
        // Stub: Return some silence or dummy data
        size_t samples = maxFrames * 2;
        outBuffer.resize(samples, 0.0f);
        return maxFrames;
    }

    const DsdInfo& getInfo() const { return info; }

private:
    FILE* file = nullptr;
    DsdInfo info;
};

} // namespace airus

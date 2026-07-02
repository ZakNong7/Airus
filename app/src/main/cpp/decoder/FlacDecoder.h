// app/src/main/cpp/decoder/FlacDecoder.h

#ifndef AIRUS_FLAC_DECODER_H
#define AIRUS_FLAC_DECODER_H

#include <FLAC/stream_decoder.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <android/log.h>

#define FLAC_LOG_TAG "FlacDecoder"
#define FLAC_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, FLAC_LOG_TAG, __VA_ARGS__)
#define FLAC_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  FLAC_LOG_TAG, __VA_ARGS__)
#define FLAC_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, FLAC_LOG_TAG, __VA_ARGS__)

namespace airus {

class FlacDecoder {
public:
    struct FlacInfo {
        int sampleRate = 0;
        int bitDepth   = 0;
        int channels   = 0;
        long totalSamples = 0;
        bool valid = false;
    };

    FlacDecoder();
    ~FlacDecoder();

    std::atomic<bool> isAborting{false};

    bool open(const std::string& filePath, int fd);
    void close();

    // Decode seluruh file sekaligus (untuk simplisitas Tahap 6)
    size_t decodeAll(std::vector<float>& outBuffer);
    size_t decodeFrames(std::vector<float>& outBuffer, size_t maxFrames);
    bool seekToSample(long sampleIndex);

    const FlacInfo& getInfo() const { return info; }
    FLAC__StreamDecoder* getInternalDecoder() { return decoder; }

private:
    FLAC__StreamDecoder* decoder = nullptr;
    FILE* file = nullptr;
    FlacInfo info;
    std::vector<float>* currentOutput = nullptr;
    std::vector<float> decodedBuffer;



    // Callbacks untuk libFLAC
    static FLAC__StreamDecoderWriteStatus write_callback(
            const FLAC__StreamDecoder *decoder,
            const FLAC__Frame *frame,
            const FLAC__int32 * const buffer[],
            void *client_data);

    static void metadata_callback(
            const FLAC__StreamDecoder *decoder,
            const FLAC__StreamMetadata *metadata,
            void *client_data);

    static void error_callback(
            const FLAC__StreamDecoder *decoder,
            FLAC__StreamDecoderErrorStatus status,
            void *client_data);
};

} // namespace airus

#endif // AIRUS_FLAC_DECODER_H

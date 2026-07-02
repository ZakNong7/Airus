// app/src/main/cpp/decoder/FlacDecoder.cpp

#include "FlacDecoder.h"
#include <cstring>
#include <unistd.h>

namespace airus {

FlacDecoder::FlacDecoder() {
    decoder = FLAC__stream_decoder_new();
}

FlacDecoder::~FlacDecoder() {
    if (decoder) {
        FLAC__stream_decoder_delete(decoder);
    }
}

bool FlacDecoder::open(const std::string& filePath, int fd) {
    close();
    isAborting = false;

    if (decoder) {
        FLAC__stream_decoder_delete(decoder);
        decoder = nullptr;
    }
    decoder = FLAC__stream_decoder_new();
    if (!decoder) return false;

    if (fd >= 0) {
        int dupFd = ::dup(fd);
        FLAC_LOGI("FlacDecoder::open: Membuka dupFd=%d (dari fd=%d) via fdopen", dupFd, fd);
        file = fdopen(dupFd, "rb");
    } else {
        FLAC_LOGI("FlacDecoder::open: Membuka path=%s via fopen", filePath.c_str());
        file = fopen(filePath.c_str(), "rb");
    }
    if (!file) {
        FLAC_LOGE("Gagal membuka file FLAC: %s (fd=%d), errno=%d (%s)", filePath.c_str(), fd, errno, strerror(errno));
        return false;
    }

    FLAC_LOGI("FlacDecoder::open: Memanggil FLAC__stream_decoder_init_FILE");
    FLAC__StreamDecoderInitStatus initStatus = FLAC__stream_decoder_init_FILE(
            decoder, file, write_callback, metadata_callback, error_callback, this);

    if (initStatus != FLAC__STREAM_DECODER_INIT_STATUS_OK) {
        FLAC_LOGE("Gagal init FLAC decoder dengan FILE*, status=%d", (int)initStatus);
        fclose(file);
        file = nullptr;
        return false;
    }

    // Proses metadata untuk ambil info stream
    FLAC_LOGI("FlacDecoder::open: Membaca metadata");
    if (!FLAC__stream_decoder_process_until_end_of_metadata(decoder)) {
        FLAC_LOGE("Gagal baca metadata FLAC, state=%d", (int)FLAC__stream_decoder_get_state(decoder));
        return false;
    }

    FLAC_LOGI("FlacDecoder::open: Metadata OK, state=%d", (int)FLAC__stream_decoder_get_state(decoder));
    return info.valid;
}

void FlacDecoder::close() {
    isAborting = true;
    if (decoder) {
        FLAC__stream_decoder_finish(decoder);
    }
    if (file) {
        fclose(file);
        file = nullptr;
    }
    info = FlacInfo{};
    decodedBuffer.clear();
    currentOutput = nullptr;
}

size_t FlacDecoder::decodeAll(std::vector<float>& outBuffer) {
    if (!decoder || !info.valid) return 0;

    currentOutput = &outBuffer;

    // Alokasikan estimasi (stereo)
    outBuffer.reserve(info.totalSamples * info.channels);

    if (!FLAC__stream_decoder_process_until_end_of_stream(decoder)) {
        FLAC_LOGE("Gagal decode FLAC stream");
        return 0;
    }

    return outBuffer.size() / info.channels;
}

size_t FlacDecoder::decodeFrames(std::vector<float>& outBuffer, size_t maxFrames) {
    if (!decoder || !info.valid) return 0;

    currentOutput = nullptr;
    size_t targetSamples = maxFrames * info.channels;

    while (decodedBuffer.size() < targetSamples) {
        if (isAborting.load()) {
            break;
        }
        FLAC__StreamDecoderState state = FLAC__stream_decoder_get_state(decoder);
        if (state == FLAC__STREAM_DECODER_END_OF_STREAM) {
            FLAC_LOGI("decodeFrames: End of stream reached");
            break;
        }
        if (!FLAC__stream_decoder_process_single(decoder)) {
            FLAC__StreamDecoderState errState = FLAC__stream_decoder_get_state(decoder);
            FLAC_LOGE("decodeFrames: Gagal memproses frame FLAC, state=%d", (int)errState);
            // Cek jika state adalah disconnected atau error fatal
            if (errState > FLAC__STREAM_DECODER_MEMORY_ALLOCATION_ERROR) {
                 break;
            }
            // Jika hanya SEARCH_FOR_METADATA atau sejenisnya, coba lagi sekali
            if (!FLAC__stream_decoder_process_single(decoder)) break;
        }
    }

    if (decodedBuffer.empty()) {
        FLAC_LOGD("decodeFrames: decodedBuffer is empty, returning 0");
        return 0;
    }

    size_t samplesToCopy = std::min(decodedBuffer.size(), targetSamples);
    if (samplesToCopy > 0) {
        outBuffer.insert(outBuffer.end(), decodedBuffer.begin(), decodedBuffer.begin() + samplesToCopy);
        decodedBuffer.erase(decodedBuffer.begin(), decodedBuffer.begin() + samplesToCopy);
    }

    return samplesToCopy / info.channels;
}

bool FlacDecoder::seekToSample(long sampleIndex) {
    if (!decoder || !info.valid) return false;
    currentOutput = nullptr;
    decodedBuffer.clear();
    return FLAC__stream_decoder_seek_absolute(decoder, sampleIndex);
}

// =========================================================
// Callbacks
// =========================================================

FLAC__StreamDecoderWriteStatus FlacDecoder::write_callback(
        const FLAC__StreamDecoder *decoder,
        const FLAC__Frame *frame,
        const FLAC__int32 * const buffer[],
        void *client_data) {

    auto* self = static_cast<FlacDecoder*>(client_data);
    if (!self) return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
    if (self->isAborting.load()) return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;

    int channels = frame->header.channels;
    if (!buffer) return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
    for (int ch = 0; ch < channels; ch++) {
        if (!buffer[ch]) return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
    }

    int blockSize = frame->header.blocksize;
    int bps = frame->header.bits_per_sample;
    float scale = 1.0f / (float)(1LL << (bps - 1));

    std::vector<float>* dest = self->currentOutput ? self->currentOutput : &self->decodedBuffer;

    size_t currentSize = dest->size();
    dest->resize(currentSize + blockSize * channels);
    float* out = dest->data() + currentSize;

    for (int i = 0; i < blockSize; i++) {
        for (int ch = 0; ch < channels; ch++) {
            *out++ = (float)buffer[ch][i] * scale;
        }
    }

    return FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE;
}


void FlacDecoder::metadata_callback(
        const FLAC__StreamDecoder *decoder,
        const FLAC__StreamMetadata *metadata,
        void *client_data) {

    auto* self = static_cast<FlacDecoder*>(client_data);
    if (metadata->type == FLAC__METADATA_TYPE_STREAMINFO) {
        self->info.sampleRate = metadata->data.stream_info.sample_rate;
        self->info.channels   = metadata->data.stream_info.channels;
        self->info.bitDepth   = metadata->data.stream_info.bits_per_sample;
        self->info.totalSamples = metadata->data.stream_info.total_samples;
        self->info.valid = true;

        FLAC_LOGI("FLAC Info: %dHz / %dbit / %dch | %ld samples",
                 self->info.sampleRate, self->info.bitDepth,
                 self->info.channels, self->info.totalSamples);
    }
}

void FlacDecoder::error_callback(
        const FLAC__StreamDecoder *decoder,
        FLAC__StreamDecoderErrorStatus status,
        void *client_data) {
    FLAC_LOGE("libFLAC Error: %s", FLAC__StreamDecoderErrorStatusString[status]);
}

} // namespace airus

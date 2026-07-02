// app/src/main/cpp/airus_engine.cpp
//
// Airus Audio Engine — Single-file native implementation
// Konvensi JNI: Java_com_zaknong_airus_engine_AudioEngine_methodName

#include <jni.h>
#include <string>
#include <memory>
#include <atomic>
#include <mutex>
#include <vector>
#include <cmath>
#include <android/log.h>
#include <oboe/Oboe.h>
#include "decoder/WavDecoder.h"
#include "decoder/FlacDecoder.h"
#include "decoder/DsdDecoder.h"
#include <thread>
#include <condition_variable>
#include <unistd.h>

#define LOG_TAG "AirusEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

// ============================================================
// BiQuad Filter — fondasi EQ
// ============================================================

class BiQuadFilter {
public:
    double b0=1,b1=0,b2=0,a1=0,a2=0;
    double x1=0,x2=0,y1=0,y2=0;

    float process(float input) {
        double out = b0*input + b1*x1 + b2*x2 - a1*y1 - a2*y2;
        x2=x1; x1=input;
        y2=y1; y1=out;
        return static_cast<float>(out);
    }

    void reset() { x1=x2=y1=y2=0.0; }

    void setPeaking(double freqHz, double gainDb, double q, double sr) {
        double A  = pow(10.0, gainDb/40.0);
        double w0 = 2.0*M_PI*freqHz/sr;
        double alpha = sin(w0)/(2.0*q);
        double a0 = 1.0 + alpha/A;
        b0 = (1.0 + alpha*A)/a0;
        b1 = (-2.0*cos(w0))/a0;
        b2 = (1.0 - alpha*A)/a0;
        a1 = (-2.0*cos(w0))/a0;
        a2 = (1.0 - alpha/A)/a0;
    }

    void setLowShelf(double freqHz, double gainDb, double sr) {
        double A    = pow(10.0, gainDb/40.0);
        double w0   = 2.0*M_PI*freqHz/sr;
        double cosW = cos(w0), sinW = sin(w0);
        double beta = sqrt(A);
        double a0   = (A+1)+(A-1)*cosW+beta*sinW;
        b0 =  A*((A+1)-(A-1)*cosW+beta*sinW)/a0;
        b1 =  2*A*((A-1)-(A+1)*cosW)/a0;
        b2 =  A*((A+1)-(A-1)*cosW-beta*sinW)/a0;
        a1 = -2*((A-1)+(A+1)*cosW)/a0;
        a2 =  ((A+1)+(A-1)*cosW-beta*sinW)/a0;
    }

    void setHighShelf(double freqHz, double gainDb, double sr) {
        double A    = pow(10.0, gainDb/40.0);
        double w0   = 2.0*M_PI*freqHz/sr;
        double cosW = cos(w0), sinW = sin(w0);
        double beta = sqrt(A);
        double a0   = (A+1)-(A-1)*cosW+beta*sinW;
        b0 =  A*((A+1)+(A-1)*cosW+beta*sinW)/a0;
        b1 = -2*A*((A-1)+(A+1)*cosW)/a0;
        b2 =  A*((A+1)-(A-1)*cosW-beta*sinW)/a0;
        a1 =  2*((A-1)-(A+1)*cosW)/a0;
        a2 =  ((A+1)+(A-1)*cosW-beta*sinW)/a0;
    }
};

// ============================================================
// 10-Band Parametric EQ
// Center: 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16kHz
// ============================================================

class ParametricEQ {
public:
    static constexpr int BANDS = 10;
    static constexpr double DEFAULT_FREQS[BANDS] = {
            31.0, 62.0, 125.0, 250.0, 500.0,
            1000.0, 2000.0, 4000.0, 8000.0, 16000.0
    };

    BiQuadFilter filtersL[BANDS];
    BiQuadFilter filtersR[BANDS];
    double gains[BANDS]  = {};
    double freqs[BANDS]  = {};
    double qVals[BANDS]  = {};
    double sampleRate    = 44100.0;
    double preampLinear  = 1.0;   // dari preampDb
    bool   enabled       = false;

    ParametricEQ() {
        for (int i = 0; i < BANDS; i++) {
            freqs[i] = DEFAULT_FREQS[i];
            gains[i] = 0.0;
            qVals[i] = 1.41;
        }
    }

    void setSampleRate(double sr) {
        sampleRate = sr;
        rebuildAll();
    }

    void setPreamp(float db) {
        preampLinear = pow(10.0, db / 20.0);
    }

    void setBand(int i, double freqHz, double gainDb, double q) {
        if (i < 0 || i >= BANDS) return;
        freqs[i] = freqHz;
        gains[i] = gainDb;
        qVals[i] = q;
        rebuildBand(i);
    }

    void rebuildBand(int i) {
        if (i == 0) {
            filtersL[i].setLowShelf(freqs[i], gains[i], sampleRate);
            filtersR[i].setLowShelf(freqs[i], gains[i], sampleRate);
        } else if (i == BANDS - 1) {
            filtersL[i].setHighShelf(freqs[i], gains[i], sampleRate);
            filtersR[i].setHighShelf(freqs[i], gains[i], sampleRate);
        } else {
            filtersL[i].setPeaking(freqs[i], gains[i], qVals[i], sampleRate);
            filtersR[i].setPeaking(freqs[i], gains[i], qVals[i], sampleRate);
        }
    }

    void rebuildAll() {
        for (int i = 0; i < BANDS; i++) rebuildBand(i);
    }

    void reset() {
        for (int i = 0; i < BANDS; i++) {
            filtersL[i].reset();
            filtersR[i].reset();
        }
    }

    void processStereo(float &left, float &right) {
        if (!enabled) return;
        // Terapkan preamp sebelum EQ untuk cegah clipping di dalam filter chain
        left  *= static_cast<float>(preampLinear);
        right *= static_cast<float>(preampLinear);
        for (int i = 0; i < BANDS; i++) {
            left  = filtersL[i].process(left);
            right = filtersR[i].process(right);
        }
    }
};

// ============================================================
// Crossfeed Processor (BS2B simplified)
// ============================================================

class CrossfeedProcessor {
public:
    bool  enabled  = false;
    float strength = 0.3f;
    float alpha    = 0.3f;
    float prevL    = 0.0f;
    float prevR    = 0.0f;

    void setParameters(float cutFreqHz, float feed) {
        // Konversi cut frequency ke alpha low-pass coefficient
        // alpha = 1 - exp(-2π * fc / sr), approx untuk sr=44100
        strength = feed;
        alpha    = 1.0f - expf(-2.0f * M_PI * cutFreqHz / 44100.0f);
    }

    void process(float &left, float &right) {
        if (!enabled) return;
        float lpL = alpha * left  + (1.0f - alpha) * prevL;
        float lpR = alpha * right + (1.0f - alpha) * prevR;
        prevL = lpL;
        prevR = lpR;
        float feed = strength * 0.45f;
        float outL = left  * (1.0f - feed) + lpR * feed;
        float outR = right * (1.0f - feed) + lpL * feed;
        left  = outL;
        right = outR;
    }

    void reset() { prevL = prevR = 0.0f; }
};

// ============================================================
// ReplayGain Processor
// ============================================================

class ReplayGainProcessor {
public:
    float trackGainLinear = 1.0f;
    float albumGainLinear = 1.0f;
    float trackPeak       = 1.0f;
    float albumPeak       = 1.0f;
    bool  albumMode       = false;
    bool  enabled         = true;

    void setTrackGain(float gainDb, float peak) {
        // NaN berarti tag tidak ada — tidak terapkan gain
        if (std::isnan(gainDb)) { trackGainLinear = 1.0f; return; }
        trackGainLinear = pow(10.0f, gainDb / 20.0f);
        trackPeak       = std::isnan(peak) ? 1.0f : peak;
        // Anti-clipping
        if (trackPeak * trackGainLinear > 1.0f) {
            trackGainLinear = 1.0f / trackPeak;
        }
    }

    void setAlbumGain(float gainDb, float peak) {
        if (std::isnan(gainDb)) { albumGainLinear = 1.0f; return; }
        albumGainLinear = pow(10.0f, gainDb / 20.0f);
        albumPeak       = std::isnan(peak) ? 1.0f : peak;
        if (albumPeak * albumGainLinear > 1.0f) {
            albumGainLinear = 1.0f / albumPeak;
        }
    }

    void setAlbumMode(bool useAlbum) { albumMode = useAlbum; }

    float getGainLinear() const {
        if (!enabled) return 1.0f;
        return albumMode ? albumGainLinear : trackGainLinear;
    }

    float process(float sample) {
        return sample * getGainLinear();
    }
};

// ============================================================
// Gapless Buffer
// ============================================================

class GaplessBuffer {
public:
    std::atomic<bool> nextReady{false};
    std::string       nextFilePath;
    std::string       nextFormat;
    int               nextSampleRate = 44100;
    int               nextBitDepth   = 16;
    std::mutex        mutex;

    void setNext(const std::string &path, const std::string &fmt,
                 int sr, int bd) {
        std::lock_guard<std::mutex> lock(mutex);
        nextFilePath   = path;
        nextFormat     = fmt;
        nextSampleRate = sr;
        nextBitDepth   = bd;
        nextReady      = true;
        LOGD("Gapless: next queued: %s [%s]", path.c_str(), fmt.c_str());
    }

    void clear() {
        std::lock_guard<std::mutex> lock(mutex);
        nextReady = false;
        nextFilePath.clear();
    }

    bool hasNext() const { return nextReady.load(); }
};

// ============================================================
// Peak Meter
// ============================================================

class PeakMeter {
public:
    std::atomic<float> peakL{0.0f};
    std::atomic<float> peakR{0.0f};
    float decayRate = 0.9995f; // decay per sample @ 44100Hz

    void update(float left, float right) {
        float absL = fabsf(left);
        float absR = fabsf(right);
        float curL = peakL.load(std::memory_order_relaxed);
        float curR = peakR.load(std::memory_order_relaxed);
        if (absL > curL) peakL.store(absL, std::memory_order_relaxed);
        else peakL.store(curL * decayRate, std::memory_order_relaxed);
        if (absR > curR) peakR.store(absR, std::memory_order_relaxed);
        else peakR.store(curR * decayRate, std::memory_order_relaxed);
    }

    float getPeakL() { return peakL.load(); }
    float getPeakR() { return peakR.load(); }
    float getPeak()  { return std::max(peakL.load(), peakR.load()); }
};

// ============================================================
// AirusAudioEngine — Main Engine
// ============================================================

class AirusAudioEngine : public oboe::AudioStreamDataCallback,
                         public oboe::AudioStreamErrorCallback {
public:

    // ---- Playback state ----
    std::atomic<bool>  isPlaying{false};
    std::atomic<bool>  isPaused{false};
    std::atomic<bool>  isEos{false}; // End of stream flag
    std::atomic<float> masterVolume{1.0f};
    std::atomic<long>  currentPositionMs{0};
    std::atomic<long>  durationMs{0};
    std::atomic<long>  totalFramesPlayed{0};

    // ---- Audio properties ----
    int         sampleRate    = 44100;
    int         bitDepth      = 16;
    int         channels      = 2;
    std::string currentFormat;
    std::string currentFilePath;

    // ---- DSP ----
    ParametricEQ        eq;
    CrossfeedProcessor  crossfeed;
    ReplayGainProcessor replayGain;
    GaplessBuffer       gapless;
    PeakMeter           peakMeter;

    // ---- Decoders ----
    std::unique_ptr<airus::WavDecoder> wavDecoder;
    std::unique_ptr<airus::FlacDecoder> flacDecoder;
    std::unique_ptr<airus::DsdDecoder> dsdDecoder;

    std::thread decoderThread;
    std::atomic<bool> isDecoding{false};
    std::condition_variable decoderCv;
    std::mutex decoderMutex;
    std::recursive_mutex threadSpawnMutex;

    // ---- Oboe ----
    std::shared_ptr<oboe::AudioStream> stream;
    std::mutex                         streamMutex;

    // ---- Decoded audio buffer (Circular-like buffer) ----
    std::vector<float>  audioBuffer;
    std::atomic<size_t> readPos{0};
    std::atomic<size_t> writePos{0};
    static constexpr size_t BUFFER_CAPACITY = 2 * 1024 * 1024; // 2M samples (~11 seconds stereo @ 96k)
    std::mutex          bufferMutex;

    // ---- JNI callbacks ----
    JavaVM*   javaVm  = nullptr;
    jobject   javaObj = nullptr;

    // Cached method IDs
    jmethodID midOnTrackCompleted   = nullptr;
    jmethodID midOnNearingEnd       = nullptr;
    jmethodID midOnError            = nullptr;
    jmethodID midOnSRChanged        = nullptr;

    // ---- Event handling ----
    struct AudioEvent {
        enum Type { TRACK_COMPLETED, NEARING_END, ERROR_MSG, SR_CHANGED };
        Type type;
        uint64_t trackId;
        long longValue;
        std::string stringValue;
        int intValue2;
    };
    std::vector<AudioEvent> pendingEvents;
    std::mutex eventMutex;
    std::condition_variable eventCv;
    std::atomic<uint64_t> currentTrackId{0};
    std::thread eventThread;
    std::atomic<bool> isEventThreadRunning{false};

    void startEventThread() {
        isEventThreadRunning = true;
        eventThread = std::thread([this]() {
            JNIEnv* env = nullptr;
            if (javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                LOGE("Gagal attach event thread ke JVM");
                return;
            }
            while (isEventThreadRunning) {
                std::vector<AudioEvent> eventsToProcess;
                {
                    std::unique_lock<std::mutex> lock(eventMutex);
                    if (pendingEvents.empty() && isEventThreadRunning) {
                        eventCv.wait_for(lock, std::chrono::milliseconds(100));
                    }
                    if (!isEventThreadRunning) break;
                    if (!pendingEvents.empty()) {
                        eventsToProcess = std::move(pendingEvents);
                        pendingEvents.clear();
                    }
                }
                for (const auto& ev : eventsToProcess) {
                    if (ev.trackId != currentTrackId.load()) {
                        LOGD("Event Thread: Mengabaikan event lama (ev.trackId=%lu, currentTrackId=%lu)",
                             (unsigned long)ev.trackId, (unsigned long)currentTrackId.load());
                        continue;
                    }
                    if (!javaObj) continue;
                    if (ev.type == AudioEvent::TRACK_COMPLETED && midOnTrackCompleted) {
                        env->CallVoidMethod(javaObj, midOnTrackCompleted);
                    } else if (ev.type == AudioEvent::NEARING_END && midOnNearingEnd) {
                        env->CallVoidMethod(javaObj, midOnNearingEnd, (jlong)ev.longValue);
                    } else if (ev.type == AudioEvent::ERROR_MSG && midOnError) {
                        jstring jmsg = env->NewStringUTF(ev.stringValue.c_str());
                        env->CallVoidMethod(javaObj, midOnError, (jint)ev.intValue2, jmsg);
                        env->DeleteLocalRef(jmsg);
                    } else if (ev.type == AudioEvent::SR_CHANGED && midOnSRChanged) {
                        env->CallVoidMethod(javaObj, midOnSRChanged, (jint)ev.longValue, (jint)ev.intValue2);
                    }
                }
            }
            javaVm->DetachCurrentThread();
        });
    }

    void stopEventThread() {
        isEventThreadRunning = false;
        eventCv.notify_all();
        if (eventThread.joinable()) {
            eventThread.join();
        }
    }

    void postEvent(AudioEvent ev) {
        ev.trackId = currentTrackId.load();
        std::lock_guard<std::mutex> lock(eventMutex);
        pendingEvents.push_back(ev);
        eventCv.notify_all();
    }

    void clearPendingEvents() {
        std::lock_guard<std::mutex> lock(eventMutex);
        pendingEvents.clear();
    }

    // Trigger preload saat sisa buffer < 5 detik
    static constexpr long NEAR_END_THRESHOLD_MS = 5000;
    std::atomic<bool> nearEndNotified{false};

    AirusAudioEngine() {
        audioBuffer.resize(BUFFER_CAPACITY);
    }


    // =========================================================
    // Init Oboe Stream
    // =========================================================

    bool openStream(int sr, int bd) {
        std::lock_guard<std::mutex> lock(streamMutex);

        if (stream) {
            stream->requestStop();
            stream->close();
            stream.reset();
        }

        sampleRate = sr;
        bitDepth   = bd;
        eq.setSampleRate(sr);

        oboe::AudioStreamBuilder builder;
        builder.setDataCallback(this)
                ->setErrorCallback(this)
                ->setDirection(oboe::Direction::Output)
                ->setSharingMode(oboe::SharingMode::Exclusive)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(oboe::ChannelCount::Stereo)
                ->setSampleRate(sr)
                ->setUsage(oboe::Usage::Media)
                ->setContentType(oboe::ContentType::Music)
                ->setSampleRateConversionQuality(
                        oboe::SampleRateConversionQuality::Best);

        if (sr > 96000) {
            builder.setFramesPerCallback(1024); // Larger chunks for very high res
        }

        oboe::Result result = builder.openStream(stream);

        if (result != oboe::Result::OK) {
            LOGE("Gagal buka Oboe stream: %s", oboe::convertToText(result));
            builder.setSharingMode(oboe::SharingMode::Shared);
            result = builder.openStream(stream);
            if (result != oboe::Result::OK) {
                LOGE("Fallback Shared mode juga gagal: %s",
                     oboe::convertToText(result));
                return false;
            }
        }

        stream->requestStart();
        LOGI("Oboe Stream dibuka: %dHz, %dch, format=%s, latency=%s, bufferSize=%d",
             stream->getSampleRate(), stream->getChannelCount(),
             oboe::convertToText(stream->getFormat()),
             oboe::convertToText(stream->getPerformanceMode()),
             stream->getBufferSizeInFrames());
        return true;
    }

    // =========================================================
    // Load + Play
    // =========================================================

    bool loadAndPlay(const std::string &path, int fd, const std::string &fmt,
                     int sr, int bd) {
        std::lock_guard<std::recursive_mutex> threadLock(threadSpawnMutex);
        currentTrackId++;
        isEos = false;

        // Stop any current decoding
        isDecoding = false;
        if (flacDecoder) {
            flacDecoder->isAborting = true;
        }
        {
            std::lock_guard<std::mutex> lock(decoderMutex);
            decoderCv.notify_all();
        }
        if (decoderThread.joinable()) decoderThread.join();
        clearPendingEvents();

        // Reset decoders to ensure new file is opened
        wavDecoder.reset();
        flacDecoder.reset();
        dsdDecoder.reset();

        currentFilePath = path;
        currentFormat   = fmt;

        if (!stream || sampleRate != sr) {
            if (!openStream(sr, bd)) {
                if (fd >= 0) ::close(fd);
                return false;
            }
            callOnSampleRateChanged(sr, bd);
        }

        // Reset state
        {
            std::lock_guard<std::mutex> lock(bufferMutex);
            readPos = 0;
            writePos = 0;
        }
        currentPositionMs = 0;
        durationMs        = 0;
        totalFramesPlayed = 0;
        nearEndNotified   = false;
        eq.reset();
        crossfeed.reset();

        if (fmt != "MEDIACODEC") {
            isPlaying = true; // Set playing before starting decoder thread
            isPaused = false;
            startDecoderThread(path, fd, fmt);
        } else {
            isPlaying = true;
            isPaused = false;
            isDecoding = false;
            if (fd >= 0) ::close(fd);
        }
        return true;
    }

    void startDecoderThread(const std::string &path, int fd, const std::string &fmt) {
        std::lock_guard<std::recursive_mutex> threadLock(threadSpawnMutex);
        // Stop and wait for the previous decoder thread to exit before starting a new one
        isDecoding = false;
        if (flacDecoder) {
            flacDecoder->isAborting = true;
        }
        {
            std::lock_guard<std::mutex> lock(decoderMutex);
            decoderCv.notify_all();
        }
        if (decoderThread.joinable()) {
            decoderThread.join();
        }

        isEos = false;
        isDecoding = true;
        decoderThread = std::thread([this, path, fd, fmt]() {
            int headerSr = 44100;
            int headerBd = 16;

            if (fmt == "WAV" || fmt == "AIFF") {
                if (!wavDecoder) wavDecoder = std::make_unique<airus::WavDecoder>();
                LOGD("startDecoderThread: membuka WAV dengan fd=%d", fd);
                if (!wavDecoder->open(path, fd)) {
                    isDecoding = false;
                    callOnError(-1, "Gagal membuka file WAV");
                    return;
                }
                headerSr = wavDecoder->getInfo().sampleRate;
                headerBd = wavDecoder->getInfo().bitDepth;
                durationMs = (long)(wavDecoder->getInfo().totalFrames * 1000L / headerSr);
                if (currentPositionMs > 0) {
                    wavDecoder->seekToFrame((long)((currentPositionMs / 1000.0) * headerSr));
                }
            } else if (fmt == "FLAC") {
                if (!flacDecoder) flacDecoder = std::make_unique<airus::FlacDecoder>();
                LOGD("startDecoderThread: membuka FLAC dengan fd=%d", fd);
                if (!flacDecoder->open(path, fd)) {
                    isDecoding = false;
                    callOnError(-10, "Gagal membuka file FLAC");
                    return;
                }
                headerSr = flacDecoder->getInfo().sampleRate;
                headerBd = flacDecoder->getInfo().bitDepth;
                durationMs = (long)(flacDecoder->getInfo().totalSamples * 1000L / headerSr);
                if (currentPositionMs > 0) {
                    flacDecoder->seekToSample((long)((currentPositionMs / 1000.0) * headerSr));
                }
            } else if (fmt == "DSD" || fmt == "DSF" || fmt == "DFF") {
                if (!dsdDecoder) dsdDecoder = std::make_unique<airus::DsdDecoder>();
                if (!dsdDecoder->open(path, fd)) {
                    isDecoding = false;
                    callOnError(-20, "Gagal membuka file DSD");
                    return;
                }
                headerSr = 44100 * 2; // Decimated
                headerBd = 24;
                durationMs = (long)(dsdDecoder->getInfo().totalSamples * 1000L / dsdDecoder->getInfo().sampleRate);
            } else {
                headerSr = sampleRate;
                headerBd = bitDepth;
            }

            if (headerSr > 0 && headerSr != sampleRate) {
                LOGI("Sample rate mismatch! Header=%d, DB=%d. Reopening stream...", headerSr, sampleRate);
                openStream(headerSr, headerBd);
                callOnSampleRateChanged(headerSr, headerBd);
            }

            // We don't force isPlaying = true here anymore.
            // isPlaying should be set by the caller (loadAndPlay or resume).

            // Streaming Decode Loop
            while (isDecoding) {
                size_t currentWrite = writePos.load();
                size_t currentRead = readPos.load();
                size_t available = (currentRead + BUFFER_CAPACITY - currentWrite - 2) % BUFFER_CAPACITY;

                if (available > 4096) {
                    std::vector<float> chunk;
                    size_t framesDecoded = 0;
                    if (wavDecoder) framesDecoded = wavDecoder->decodeFrames(chunk, 2048);
                    else if (flacDecoder) {
                        framesDecoded = flacDecoder->decodeFrames(chunk, 2048);
                    }
                    else if (dsdDecoder) framesDecoded = dsdDecoder->decodeFrames(chunk, 2048);

                    if (framesDecoded == 0) {
                        // Jika flacDecoder ada, cek state-nya
                        FLAC__StreamDecoderState fState = flacDecoder ? FLAC__stream_decoder_get_state(flacDecoder->getInternalDecoder()) : (FLAC__StreamDecoderState)0;

                        if (flacDecoder && fState < FLAC__STREAM_DECODER_END_OF_STREAM) {
                             // Belum benar-benar habis, mungkin butuh proses lagi
                             static int zeroFrameCount = 0;
                             if (++zeroFrameCount < 100) {
                                 std::this_thread::sleep_for(std::chrono::milliseconds(10));
                                 continue;
                             }
                             LOGW("DecoderThread: Terjebak di state %d (0 frames) selama 1 detik, menganggap EOS", (int)fState);
                             zeroFrameCount = 0;
                        }

                        if (isDecoding.load()) {
                            LOGD("DecoderThread: EOF reached for %s (FLAC State=%d)", path.c_str(), (int)fState);
                            isEos = true;
                        }
                        isDecoding = false;
                        break;
                    }


                    fillBuffer(chunk.data(), chunk.size());
                } else {
                    // Buffer full, wait a bit. If buffer is huge, we can wait 50ms.
                    // If buffer is small, we should wait less.
                    std::unique_lock<std::mutex> lock(decoderMutex);
                    decoderCv.wait_for(lock, std::chrono::milliseconds(20));
                }
            }
            if (fd >= 0) {
                LOGD("DecoderThread: Menutup fd=%d", fd);
                ::close(fd);
            }
        });
    }

    void fillBuffer(const float *samples, size_t count) {
        // Tunggu sampai ada ruang di buffer agar tidak overwrite data yang belum diputar
        int retry = 0;
        while (isDecoding || isPlaying) { // Selama aktif, kita tunggu
            size_t wp = writePos.load();
            size_t rp = readPos.load();
            size_t used = (wp + BUFFER_CAPACITY - rp) & (BUFFER_CAPACITY - 1);
            size_t free = BUFFER_CAPACITY - used - 1;

            if (free >= count) break;

            // Jika macet terlalu lama (misal 5 detik), break saja agar tidak ANR
            if (++retry > 500) {
                LOGW("fillBuffer: Timeout menunggu ruang buffer");
                break;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }

        std::lock_guard<std::mutex> lock(bufferMutex);
        size_t wp = writePos.load();

        size_t spaceToEnd = BUFFER_CAPACITY - wp;
        if (count <= spaceToEnd) {
            std::memcpy(&audioBuffer[wp], samples, count * sizeof(float));
            wp = (wp + count) & (BUFFER_CAPACITY - 1);
        } else {
            std::memcpy(&audioBuffer[wp], samples, spaceToEnd * sizeof(float));
            size_t remaining = count - spaceToEnd;
            std::memcpy(&audioBuffer[0], samples + spaceToEnd, remaining * sizeof(float));
            wp = remaining;
        }
        writePos.store(wp);
    }

    // =========================================================
    // Transport
    // =========================================================

    void pause() {
        isPaused  = true;
        isPlaying = false;
    }

    void resume() {
        isPaused  = false;
        isPlaying = true;
    }

    void stop() {
        isDecoding = false;
        isPlaying = false;
        isPaused  = false;
        {
            std::lock_guard<std::mutex> lock(bufferMutex);
            readPos = 0;
            writePos = 0;
        }
        currentPositionMs = 0;
        nearEndNotified   = false;
        gapless.clear();
    }

    void seekTo(long posMs, int fd) {
        std::lock_guard<std::recursive_mutex> threadLock(threadSpawnMutex);
        LOGD("seekTo: dipanggil dengan posMs=%ld, fd=%d", posMs, fd);
        currentTrackId++;
        isEos = false;
        // Simple seek: restart decoder at new position
        isDecoding = false;
        if (flacDecoder) {
            flacDecoder->isAborting = true;
        }
        if (decoderThread.joinable()) decoderThread.join();
        clearPendingEvents();

        {
            std::lock_guard<std::mutex> lock(bufferMutex);
            readPos = 0;
            writePos = 0;
        }

        // Reset existing decoders so we open a new stream with the fresh file descriptor
        if (wavDecoder) {
            wavDecoder->close();
            wavDecoder.reset();
        }
        if (flacDecoder) {
            flacDecoder->close();
            flacDecoder.reset();
        }
        if (dsdDecoder) {
            dsdDecoder->close();
            dsdDecoder.reset();
        }

        currentPositionMs = posMs;
        totalFramesPlayed = (long)((posMs / 1000.0) * sampleRate);
        nearEndNotified   = false;
        startDecoderThread(currentFilePath, fd, currentFormat);
    }

    void release() {
        stop();
        stopEventThread();
        std::lock_guard<std::mutex> lock(streamMutex);
        if (stream) {
            stream->requestStop();
            stream->close();
            stream.reset();
        }
        releaseJavaRef();
    }

    // =========================================================
    // Oboe Audio Callback
    // =========================================================

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream* /*audioStream*/,
            void* audioData,
            int32_t numFrames) override {

        auto* out = static_cast<float*>(audioData);
        if (!isPlaying || isPaused) {
            memset(out, 0, numFrames * 2 * sizeof(float));
            return oboe::DataCallbackResult::Continue;
        }

        size_t rp = readPos.load();
        size_t wp = writePos.load();
        int samplesRead = 0;

        for (int i = 0; i < numFrames; i++) {
            float left = 0.0f, right = 0.0f;
            if (rp != wp) {
                left = audioBuffer[rp];
                rp = (rp + 1) & (BUFFER_CAPACITY - 1);

                if (rp != wp) {
                    right = audioBuffer[rp];
                    rp = (rp + 1) & (BUFFER_CAPACITY - 1);
                }
                samplesRead += 2;
            } else {
                // Buffer empty
                if (isEos.load()) {
                    LOGD("onAudioReady: EOS triggered (isEos=true, buffer empty)");
                    isEos = false; // Reset to prevent multiple triggerings
                    if (gapless.hasNext()) {
                        // Logic to switch to next track for gapless
                        callOnTrackCompleted();
                    } else {
                        isPlaying = false;
                        callOnTrackCompleted();
                        memset(out + i*2, 0, (numFrames-i)*2*sizeof(float));
                        break;
                    }
                } else {
                    // Just play silence and wait for more data (buffering)
                    memset(out + i*2, 0, (numFrames-i)*2*sizeof(float));
                    break;
                }
            }
            // ... (rest of DSP)

            // DSP
            left = replayGain.process(left);
            right = replayGain.process(right);
            if (eq.enabled) eq.processStereo(left, right);
            crossfeed.process(left, right);
            float vol = masterVolume.load();
            left *= vol; right *= vol;

            if (left > 1.0f) left = 1.0f; else if (left < -1.0f) left = -1.0f;
            if (right > 1.0f) right = 1.0f; else if (right < -1.0f) right = -1.0f;
            if ((i & 0xF) == 0) peakMeter.update(left, right);

            out[i*2] = left;
            out[i*2 + 1] = right;
        }

        readPos.store(rp);
        totalFramesPlayed += (samplesRead / 2);

        if (isPlaying && !isPaused) {
            currentPositionMs = (long)(totalFramesPlayed.load() * 1000L / (sampleRate > 0 ? sampleRate : 44100));
            long remaining = durationMs.load() - currentPositionMs.load();
            if (!nearEndNotified.load() && remaining > 0 && remaining <= NEAR_END_THRESHOLD_MS) {
                nearEndNotified = true;
                callOnNearingEnd(remaining);
            }
        }

        decoderCv.notify_all();
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream* /*stream*/,
                           oboe::Result result) override {
        if (result == oboe::Result::ErrorDisconnected) {
            openStream(sampleRate, bitDepth);
        }
    }

    // JNI Setup and Callbacks
    void setupJavaCallback(JNIEnv* env, jobject obj) {
        env->GetJavaVM(&javaVm);
        if (javaObj) env->DeleteGlobalRef(javaObj);
        javaObj = env->NewGlobalRef(obj);
        jclass cls = env->GetObjectClass(obj);
        midOnTrackCompleted = env->GetMethodID(cls, "onTrackCompleted", "()V");
        midOnNearingEnd     = env->GetMethodID(cls, "onNearingEnd",     "(J)V");
        midOnError          = env->GetMethodID(cls, "onError",          "(ILjava/lang/String;)V");
        midOnSRChanged      = env->GetMethodID(cls, "onSampleRateChanged", "(II)V");
        env->DeleteLocalRef(cls);

        stopEventThread();
        startEventThread();
    }

    void releaseJavaRef() {
        stopEventThread();
        if (javaVm && javaObj) {
            JNIEnv* env;
            bool isAttached = false;
            int getEnvRes = javaVm->GetEnv((void**)&env, JNI_VERSION_1_6);
            if (getEnvRes == JNI_EDETACHED) {
                if (javaVm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                    isAttached = true;
                }
            }

            if (env) {
                env->DeleteGlobalRef(javaObj);
            }

            if (isAttached) {
                javaVm->DetachCurrentThread();
            }
            javaObj = nullptr;
        }
    }

    void callOnTrackCompleted() {
        postEvent({AudioEvent::TRACK_COMPLETED, 0, 0, "", 0});
    }
    void callOnNearingEnd(long remainingMs) {
        postEvent({AudioEvent::NEARING_END, 0, remainingMs, "", 0});
    }
    void callOnError(int code, const std::string& msg) {
        postEvent({AudioEvent::ERROR_MSG, 0, 0, msg, code});
    }
    void callOnSampleRateChanged(int sr, int bd) {
        postEvent({AudioEvent::SR_CHANGED, 0, (long)sr, "", bd});
    }

private:
    JNIEnv* attachThread() {
        if (!javaVm) return nullptr;
        JNIEnv* env = nullptr;
        if (javaVm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) javaVm->AttachCurrentThread(&env, nullptr);
        return env;
    }
};

static AirusAudioEngine* g_engine = nullptr;
static std::mutex        g_engineMutex;

#define JNI_FN(name) Java_com_zaknong_airus_engine_AudioEngine_##name

extern "C" {
JNIEXPORT jboolean JNICALL JNI_FN(initialize)(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    if (g_engine) { g_engine->release(); delete g_engine; }
    g_engine = new AirusAudioEngine();
    g_engine->setupJavaCallback(env, thiz);
    return (jboolean)g_engine->openStream(44100, 16);
}
JNIEXPORT void JNICALL JNI_FN(release)(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    if (g_engine) { g_engine->release(); delete g_engine; g_engine = nullptr; }
}
JNIEXPORT jboolean JNICALL JNI_FN(playNative)(JNIEnv* env, jobject thiz, jstring jPath, jint jFd, jstring jFormat, jint jSr, jint jBd) {
    if (!g_engine) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    const char* fmt  = env->GetStringUTFChars(jFormat, nullptr);
    LOGD("playNative: %s (fd=%d) [%s] %dHz/%dbit", path, jFd, fmt, (int)jSr, (int)jBd);
    bool ok = g_engine->loadAndPlay(path, (int)jFd, fmt, (int)jSr, (int)jBd);
    env->ReleaseStringUTFChars(jPath, path);
    env->ReleaseStringUTFChars(jFormat, fmt);
    return (jboolean)ok;
}
JNIEXPORT jboolean JNICALL JNI_FN(playMediaCodec)(JNIEnv* env, jobject thiz, jstring jPath, jint jSr, jint jBd) {
    if (!g_engine) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    LOGD("playMediaCodec: %s %dHz/%dbit", path, (int)jSr, (int)jBd);
    bool ok = g_engine->loadAndPlay(path, -1, "MEDIACODEC", (int)jSr, (int)jBd);
    env->ReleaseStringUTFChars(jPath, path);
    return (jboolean)ok;
}
JNIEXPORT void JNICALL JNI_FN(pause)(JNIEnv* env, jobject thiz) { if (g_engine) g_engine->pause(); }
JNIEXPORT void JNICALL JNI_FN(resume)(JNIEnv* env, jobject thiz) { if (g_engine) g_engine->resume(); }
JNIEXPORT void JNICALL JNI_FN(stop)(JNIEnv* env, jobject thiz) { if (g_engine) g_engine->stop(); }
JNIEXPORT void JNICALL JNI_FN(seekTo)(JNIEnv* env, jobject thiz, jlong posMs, jint fd) { if (g_engine) g_engine->seekTo((long)posMs, (int)fd); }
JNIEXPORT jlong JNICALL JNI_FN(getPositionMs)(JNIEnv* env, jobject thiz) { return g_engine ? (jlong)g_engine->currentPositionMs.load() : 0L; }
JNIEXPORT jlong JNICALL JNI_FN(getDurationMs)(JNIEnv* env, jobject thiz) { return g_engine ? (jlong)g_engine->durationMs.load() : 0L; }
JNIEXPORT void JNICALL JNI_FN(setEqEnabled)(JNIEnv* env, jobject thiz, jboolean enabled) { if (g_engine) g_engine->eq.enabled = (bool)enabled; }
JNIEXPORT void JNICALL JNI_FN(setEqBand)(JNIEnv* env, jobject thiz, jint band, jfloat freq, jfloat gainDb, jfloat q) { if (g_engine) g_engine->eq.setBand((int)band, freq, gainDb, q); }
JNIEXPORT void JNICALL JNI_FN(setEqPreset)(JNIEnv* env, jobject thiz, jfloatArray jFreqs, jfloatArray jGains, jfloatArray jQs, jfloat preampDb) {
    if (!g_engine) return;
    jfloat* freqs = env->GetFloatArrayElements(jFreqs, nullptr);
    jfloat* gains = env->GetFloatArrayElements(jGains, nullptr);
    jfloat* qs = env->GetFloatArrayElements(jQs, nullptr);
    for (int i = 0; i < 10; i++) g_engine->eq.setBand(i, freqs[i], gains[i], qs[i]);
    g_engine->eq.setPreamp((float)preampDb);
    env->ReleaseFloatArrayElements(jFreqs, freqs, JNI_ABORT);
    env->ReleaseFloatArrayElements(jGains, gains, JNI_ABORT);
    env->ReleaseFloatArrayElements(jQs, qs, JNI_ABORT);
}
JNIEXPORT void JNICALL JNI_FN(setReplayGain)(JNIEnv* env, jobject thiz, jfloat trackGain, jfloat trackPeak, jfloat albumGain, jfloat albumPeak, jboolean useAlbumMode) {
    if (!g_engine) return;
    g_engine->replayGain.setTrackGain((float)trackGain, (float)trackPeak);
    g_engine->replayGain.setAlbumGain((float)albumGain, (float)albumPeak);
    g_engine->replayGain.setAlbumMode((bool)useAlbumMode);
}
JNIEXPORT void JNICALL JNI_FN(preloadNextTrack)(JNIEnv* env, jobject thiz, jstring jPath, jstring jFormat) {
    if (g_engine) g_engine->gapless.setNext(env->GetStringUTFChars(jPath, nullptr), env->GetStringUTFChars(jFormat, nullptr), 44100, 16);
}
JNIEXPORT void JNICALL JNI_FN(cancelPreload)(JNIEnv* env, jobject thiz) { if (g_engine) g_engine->gapless.clear(); }
JNIEXPORT void JNICALL JNI_FN(setEndOfStream)(JNIEnv* env, jobject thiz, jboolean eos) { if (g_engine) g_engine->isEos.store(eos); }
JNIEXPORT void JNICALL JNI_FN(setHardwareVolume)(JNIEnv* env, jobject thiz, jfloat vol) { if (g_engine) g_engine->masterVolume.store(vol); }
JNIEXPORT void JNICALL JNI_FN(setCrossfeed)(JNIEnv* env, jobject thiz, jboolean enabled, jfloat cutFreq, jfloat feed) {
    if (g_engine) { g_engine->crossfeed.enabled = (bool)enabled; g_engine->crossfeed.setParameters(cutFreq, feed); }
}
JNIEXPORT void JNICALL JNI_FN(fillBuffer)(JNIEnv* env, jobject thiz, jfloatArray samples) {
    if (!g_engine) return;
    jfloat* data = env->GetFloatArrayElements(samples, nullptr);
    g_engine->fillBuffer(data, env->GetArrayLength(samples));
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
}
JNIEXPORT jint JNICALL JNI_FN(getActiveSampleRate)(JNIEnv* env, jobject thiz) { return g_engine ? (jint)g_engine->sampleRate : 0; }
JNIEXPORT jint JNICALL JNI_FN(getActiveBitDepth)(JNIEnv* env, jobject thiz) { return g_engine ? (jint)g_engine->bitDepth : 0; }
JNIEXPORT jfloat JNICALL JNI_FN(getPeakLevel)(JNIEnv* env, jobject thiz) { return g_engine ? g_engine->peakMeter.getPeak() : 0.0f; }
JNIEXPORT jfloat JNICALL JNI_FN(getPeakLevelL)(JNIEnv* env, jobject thiz) { return g_engine ? g_engine->peakMeter.getPeakL() : 0.0f; }
JNIEXPORT jfloat JNICALL JNI_FN(getPeakLevelR)(JNIEnv* env, jobject thiz) { return g_engine ? g_engine->peakMeter.getPeakR() : 0.0f; }
}


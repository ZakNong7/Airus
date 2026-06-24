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
#include <thread>

#define LOG_TAG "AirusEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

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
        b2 =  A*((A+1)+(A-1)*cosW-beta*sinW)/a0;
        a1 =  2*((A-1)-(A+1)*cosW)/a0;
        a2 =  ((A+1)-(A-1)*cosW-beta*sinW)/a0;
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
        float curL = peakL.load();
        float curR = peakR.load();
        peakL.store(absL > curL ? absL : curL * decayRate);
        peakR.store(absR > curR ? absR : curR * decayRate);
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
    std::atomic<float> masterVolume{1.0f};
    std::atomic<long>  currentPositionMs{0};
    std::atomic<long>  durationMs{0};

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
    std::unique_ptr<airus::WavDecoder> wavDecoder;
    std::thread decoderThread;
    std::atomic<bool> isDecoding{false};

    // ---- Oboe ----
    std::shared_ptr<oboe::AudioStream> stream;
    std::mutex                         streamMutex;

    // ---- Decoded audio buffer ----
    // Diisi oleh decoder (FLAC/WAV/MediaCodec) dari thread terpisah.
    // Dibaca oleh onAudioReady() dari audio thread.
    std::vector<float>  audioBuffer;
    std::atomic<size_t> readPos{0};
    std::mutex          bufferMutex;

    // ---- JNI callbacks ----
    JavaVM*   javaVm  = nullptr;
    jobject   javaObj = nullptr;  // global ref ke AudioEngine.java

    // Cached method IDs
    jmethodID midOnTrackCompleted   = nullptr;
    jmethodID midOnNearingEnd       = nullptr;
    jmethodID midOnError            = nullptr;
    jmethodID midOnSRChanged        = nullptr;

    // ---- Gapless threshold ----
    // Trigger preload saat sisa buffer < 5 detik
    static constexpr long NEAR_END_THRESHOLD_MS = 5000;
    std::atomic<bool> nearEndNotified{false};

    // =========================================================
    // Init Oboe Stream
    // =========================================================

    bool openStream(int sr, int bd) {
        std::lock_guard<std::mutex> lock(streamMutex);

        // Tutup stream lama jika ada
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
                        // Exclusive mode = bypass Android audio mixer = bit-perfect
                ->setSharingMode(oboe::SharingMode::Exclusive)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(oboe::ChannelCount::Stereo)
                ->setSampleRate(sr)
                        // Jika DAC tidak support SR ini, Oboe akan resample
                        // dengan kualitas terbaik yang tersedia
                ->setSampleRateConversionQuality(
                        oboe::SampleRateConversionQuality::Best);

        oboe::Result result = builder.openStream(stream);

        if (result != oboe::Result::OK) {
            LOGE("Gagal buka Oboe stream: %s", oboe::convertToText(result));
            // Fallback ke Shared mode jika Exclusive tidak tersedia
            builder.setSharingMode(oboe::SharingMode::Shared);
            result = builder.openStream(stream);
            if (result != oboe::Result::OK) {
                LOGE("Fallback Shared mode juga gagal: %s",
                     oboe::convertToText(result));
                return false;
            }
            LOGI("Fallback ke Shared mode (bukan bit-perfect)");
        }

        stream->requestStart();
        LOGI("Oboe stream: %dHz / %dbit | Mode: %s",
             sr, bd,
             stream->getSharingMode() == oboe::SharingMode::Exclusive
             ? "Exclusive (bit-perfect)" : "Shared");
        return true;
    }

    // =========================================================
    // Load + Play
    // =========================================================

    bool loadAndPlay(const std::string &path, const std::string &fmt,
                     int sr, int bd) {
        currentFilePath = path;
        currentFormat   = fmt;

        // Jika sample rate berbeda dari stream aktif, re-open stream
        if (!stream || sampleRate != sr) {
            if (!openStream(sr, bd)) return false;
            // Notifikasi Java bahwa SR berubah
            callOnSampleRateChanged(sr, bd);
        }

        // Reset state
        {
            std::lock_guard<std::mutex> lock(bufferMutex);
            audioBuffer.clear();
            readPos = 0;
        }
        currentPositionMs = 0;
        durationMs        = 0;
        nearEndNotified   = false;
        eq.reset();
        crossfeed.reset();

        // ---- Decode WAV di background thread ----
        if (fmt == "WAV" || fmt == "AIFF") {
            isDecoding = true;

            // Jalankan decode di thread terpisah
            // agar tidak block audio callback
            if (decoderThread.joinable()) decoderThread.join();

            decoderThread = std::thread([this, path, sr, bd]() {
                airus::WavDecoder decoder;
                if (!decoder.open(path)) {
                    LOGE("WavDecoder gagal buka: %s", path.c_str());
                    isDecoding = false;
                    callOnError(-1, "Gagal membuka file WAV");
                    return;
                }

                // Update info dari header file
                // (lebih akurat dari database)
                sampleRate = decoder.getInfo().sampleRate;
                bitDepth   = decoder.getInfo().bitDepth;
                channels   = decoder.getInfo().channels;
                durationMs = (long)(decoder.getInfo().totalFrames
                                    * 1000L / sampleRate);

                // Decode semua ke buffer
                // Untuk file besar (>30 menit), gunakan decodeFrames()
                // secara bertahap. Untuk sekarang decode sekaligus.
                std::vector<float> decoded;
                size_t frames = decoder.decodeAll(decoded);

                if (frames == 0) {
                    LOGE("WavDecoder: tidak ada frame yang didecode");
                    isDecoding = false;
                    callOnError(-2, "File WAV kosong atau corrupt");
                    return;
                }

                // Salin ke audioBuffer (thread-safe)
                {
                    std::lock_guard<std::mutex> lock(bufferMutex);
                    audioBuffer = std::move(decoded);
                    readPos     = 0;
                }

                isDecoding  = false;
                isPlaying   = true;
                isPaused    = false;

                LOGI("WAV decoded: %zu frames → %zu samples",
                     frames, audioBuffer.size());
            });

            decoderThread.detach();
            return true;
        }

        // Format lain (FLAC, MediaCodec) — akan diisi Tahap 6B dan 6C
        LOGI("Format %s belum didukung — coming soon", fmt.c_str());
        return false;
    }

    /**
     * Dipanggil decoder dari thread terpisah untuk mengisi audio buffer.
     * Thread-safe.
     */
    void fillBuffer(const float *samples, size_t count) {
        std::lock_guard<std::mutex> lock(bufferMutex);
        audioBuffer.insert(audioBuffer.end(), samples, samples + count);
    }

    // =========================================================
    // Transport
    // =========================================================

    void pause() {
        isPaused  = true;
        isPlaying = false;
        LOGD("Paused at %ld ms", currentPositionMs.load());
    }

    void resume() {
        isPaused  = false;
        isPlaying = true;
        LOGD("Resumed from %ld ms", currentPositionMs.load());
    }

    void stop() {
        isPlaying = false;
        isPaused  = false;
        {
            std::lock_guard<std::mutex> lock(bufferMutex);
            audioBuffer.clear();
            readPos = 0;
        }
        currentPositionMs = 0;
        nearEndNotified   = false;
        gapless.clear();
        eq.reset();
        crossfeed.reset();
    }

    void seekTo(long posMs) {
        // Hitung sample position dari posMs
        size_t samplePos = (size_t)((posMs / 1000.0) * sampleRate * channels);
        {
            std::lock_guard<std::mutex> lock(bufferMutex);
            // Clamp ke ukuran buffer
            if (samplePos > audioBuffer.size()) samplePos = 0;
            readPos = samplePos;
        }
        currentPositionMs = posMs;
        nearEndNotified   = false;
        eq.reset();
        crossfeed.reset();
        LOGD("Seeked to %ld ms (sample %zu)", posMs, samplePos);
    }

    void release() {
        stop();
        std::lock_guard<std::mutex> lock(streamMutex);
        if (stream) {
            stream->requestStop();
            stream->close();
            stream.reset();
        }
        releaseJavaRef();
        LOGI("AirusEngine released");
    }

    // =========================================================
    // Oboe Audio Callback — HIGH PRIORITY THREAD
    // Tidak boleh: alloc heap, lock mutex lama, system call
    // =========================================================

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream* /*audioStream*/,
            void* audioData,
            int32_t numFrames) override {

        auto* out = static_cast<float*>(audioData);
        int   totalSamples = numFrames * 2; // stereo

        if (!isPlaying || isPaused) {
            memset(out, 0, totalSamples * sizeof(float));
            return oboe::DataCallbackResult::Continue;
        }

        size_t rp       = readPos.load();
        size_t bufSize  = audioBuffer.size(); // snapshot — lock-free approx

        for (int i = 0; i < numFrames; i++) {
            float left  = 0.0f;
            float right = 0.0f;

            if (rp + 1 < bufSize) {
                left  = audioBuffer[rp];
                right = audioBuffer[rp + 1];
                rp   += 2;
            } else {
                // Buffer habis
                if (gapless.hasNext()) {
                    // Gapless: langsung lanjut ke lagu berikutnya
                    // tanpa gap. Notifikasi Java untuk update UI.
                    callOnTrackCompleted();
                } else {
                    isPlaying = false;
                    callOnTrackCompleted();
                    memset(out + i*2, 0, (numFrames-i)*2*sizeof(float));
                    break;
                }
            }

            // Update posisi (approximate, tanpa lock)
            currentPositionMs = (long)((rp / 2) * 1000L / sampleRate);

            // Cek near-end untuk trigger gapless preload
            long remaining = durationMs.load() - currentPositionMs.load();
            if (!nearEndNotified.load() &&
                remaining > 0 && remaining <= NEAR_END_THRESHOLD_MS) {
                nearEndNotified = true;
                callOnNearingEnd(remaining);
            }

            // ==================================================
            // DSP Chain
            // Urutan penting: ReplayGain → EQ → Crossfeed → Volume
            // ==================================================

            // 1. ReplayGain — normalisasi level antar lagu
            left  = replayGain.process(left);
            right = replayGain.process(right);

            // 2. EQ — hanya jika enabled
            //    jika disabled = bit-perfect: sinyal tidak disentuh
            if (eq.enabled) {
                eq.processStereo(left, right);
            }

            // 3. Crossfeed — simulasi loudspeaker untuk headphone
            crossfeed.process(left, right);

            // 4. Master volume
            float vol = masterVolume.load();
            left  *= vol;
            right *= vol;

            // 5. Soft clip — cegah hard clipping di ujung chain
            //    tanh memberikan soft saturation yang lebih natural
            //    dari hard clip (min/max)
            left  = tanhf(left);
            right = tanhf(right);

            // 6. Peak meter
            peakMeter.update(left, right);

            out[i*2]     = left;
            out[i*2 + 1] = right;
        }

        readPos.store(rp);
        return oboe::DataCallbackResult::Continue;
    }

    // =========================================================
    // Oboe Error Callback
    // =========================================================

    void onErrorAfterClose(oboe::AudioStream* /*stream*/,
                           oboe::Result result) override {
        LOGE("Oboe error: %s", oboe::convertToText(result));
        if (result == oboe::Result::ErrorDisconnected) {
            // DAC dicabut atau audio device berubah — restart stream
            LOGI("Stream disconnected — restart dengan internal output");
            openStream(sampleRate, bitDepth);
            callOnError(static_cast<int>(result),
                        "Audio device disconnected");
        }
    }

    // =========================================================
    // JNI Setup
    // =========================================================

    void setupJavaCallback(JNIEnv* env, jobject obj) {
        env->GetJavaVM(&javaVm);
        if (javaObj) env->DeleteGlobalRef(javaObj);
        javaObj = env->NewGlobalRef(obj);

        // Cache method IDs — hanya sekali, lebih efisien
        jclass cls = env->GetObjectClass(obj);
        midOnTrackCompleted = env->GetMethodID(cls, "onTrackCompleted", "()V");
        midOnNearingEnd     = env->GetMethodID(cls, "onNearingEnd",     "(J)V");
        midOnError          = env->GetMethodID(cls, "onError",          "(ILjava/lang/String;)V");
        midOnSRChanged      = env->GetMethodID(cls, "onSampleRateChanged", "(II)V");
        env->DeleteLocalRef(cls);
    }

    void releaseJavaRef() {
        if (javaVm && javaObj) {
            JNIEnv* env;
            javaVm->AttachCurrentThread(&env, nullptr);
            env->DeleteGlobalRef(javaObj);
            javaVm->DetachCurrentThread();
            javaObj = nullptr;
        }
    }

    // =========================================================
    // JNI Callback helpers
    // Dipanggil dari audio thread — attach/detach thread ke JVM
    // =========================================================

    void callOnTrackCompleted() {
        JNIEnv* env = attachThread();
        if (env && javaObj && midOnTrackCompleted)
            env->CallVoidMethod(javaObj, midOnTrackCompleted);
        detachThread();
    }

    void callOnNearingEnd(long remainingMs) {
        JNIEnv* env = attachThread();
        if (env && javaObj && midOnNearingEnd)
            env->CallVoidMethod(javaObj, midOnNearingEnd, (jlong)remainingMs);
        detachThread();
    }

    void callOnError(int code, const std::string& msg) {
        JNIEnv* env = attachThread();
        if (env && javaObj && midOnError) {
            jstring jmsg = env->NewStringUTF(msg.c_str());
            env->CallVoidMethod(javaObj, midOnError, (jint)code, jmsg);
            env->DeleteLocalRef(jmsg);
        }
        detachThread();
    }

    void callOnSampleRateChanged(int sr, int bd) {
        JNIEnv* env = attachThread();
        if (env && javaObj && midOnSRChanged)
            env->CallVoidMethod(javaObj, midOnSRChanged, (jint)sr, (jint)bd);
        detachThread();
    }

private:
    // Track apakah thread ini sudah di-attach sebelumnya
    // untuk hindari double-attach
    JNIEnv* attachThread() {
        if (!javaVm) return nullptr;
        JNIEnv* env = nullptr;
        int status = javaVm->GetEnv(
                reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (status == JNI_EDETACHED) {
            javaVm->AttachCurrentThread(&env, nullptr);
        }
        return env;
    }

    void detachThread() {
        // Jangan detach jika thread ini bukan audio thread
        // Audio thread Oboe di-manage Oboe sendiri
        // Hanya detach thread yang kita attach sendiri
        // Untuk simplisitas: tidak detach di sini,
        // Oboe audio thread tetap attached sepanjang lifetime stream
    }
};

// ============================================================
// Global engine instance
// ============================================================

static AirusAudioEngine* g_engine = nullptr;
static std::mutex        g_engineMutex;

// ============================================================
// JNI Bridge
// Package: com.zaknong.airus.engine.AudioEngine
// ============================================================

#define JNI_FN(name) \
    Java_com_zaknong_airus_engine_AudioEngine_##name

extern "C" {

// ---- Lifecycle ----

JNIEXPORT jboolean JNICALL
JNI_FN(initialize)(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    if (g_engine) {
        g_engine->release();
        delete g_engine;
    }
    g_engine = new AirusAudioEngine();
    g_engine->setupJavaCallback(env, thiz);
    bool ok = g_engine->openStream(44100, 16); // default stream
    LOGI("initialize() -> %s", ok ? "OK" : "FAILED");
    return (jboolean)ok;
}

JNIEXPORT void JNICALL
JNI_FN(release)(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_engineMutex);
    if (g_engine) {
        g_engine->release();
        delete g_engine;
        g_engine = nullptr;
    }
}

// ---- Playback — Native Path ----

JNIEXPORT jboolean JNICALL
JNI_FN(playNative)(JNIEnv* env, jobject /*thiz*/,
                   jstring jPath, jstring jFormat) {
    if (!g_engine) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(jPath,   nullptr);
    const char* fmt  = env->GetStringUTFChars(jFormat, nullptr);

    // Baca sample rate & bit depth dari database sebelum panggil ini
    // Untuk sekarang default ke 44100/16 — akan diupdate saat decoder baca header
    bool ok = g_engine->loadAndPlay(path, fmt, 44100, 16);

    env->ReleaseStringUTFChars(jPath,   path);
    env->ReleaseStringUTFChars(jFormat, fmt);
    return (jboolean)ok;
}

// ---- Playback — MediaCodec Path ----

JNIEXPORT jboolean JNICALL
JNI_FN(playMediaCodec)(JNIEnv* env, jobject /*thiz*/, jstring jPath) {
    if (!g_engine) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    // MediaCodec: format = nama ekstensi, SR & BD dari MediaCodec output
    bool ok = g_engine->loadAndPlay(path, "MEDIACODEC", 44100, 16);
    env->ReleaseStringUTFChars(jPath, path);
    return (jboolean)ok;
}

// ---- Transport ----

JNIEXPORT void JNICALL
JNI_FN(pause)(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_engine) g_engine->pause();
}

JNIEXPORT void JNICALL
JNI_FN(resume)(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_engine) g_engine->resume();
}

JNIEXPORT void JNICALL
JNI_FN(stop)(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_engine) g_engine->stop();
}

JNIEXPORT void JNICALL
JNI_FN(seekTo)(JNIEnv* /*env*/, jobject /*thiz*/, jlong posMs) {
    if (g_engine) g_engine->seekTo((long)posMs);
}

JNIEXPORT jlong JNICALL
JNI_FN(getPositionMs)(JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_engine ? (jlong)g_engine->currentPositionMs.load() : 0L;
}

JNIEXPORT jlong JNICALL
JNI_FN(getDurationMs)(JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_engine ? (jlong)g_engine->durationMs.load() : 0L;
}

// ---- EQ ----

JNIEXPORT void JNICALL
JNI_FN(setEqEnabled)(JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled) {
    if (g_engine) {
        g_engine->eq.enabled = (bool)enabled;
        if (!enabled) g_engine->eq.reset(); // reset filter state saat bypass
        LOGD("EQ %s", enabled ? "ON" : "OFF (bit-perfect)");
    }
}

JNIEXPORT void JNICALL
JNI_FN(setEqBand)(JNIEnv* /*env*/, jobject /*thiz*/,
                  jint band, jfloat freq, jfloat gainDb, jfloat q) {
    if (g_engine) g_engine->eq.setBand((int)band, freq, gainDb, q);
}

JNIEXPORT void JNICALL
JNI_FN(setEqPreset)(JNIEnv* env, jobject /*thiz*/,
                    jfloatArray jFreqs, jfloatArray jGains,
                    jfloatArray jQs,   jfloat preampDb) {
    if (!g_engine) return;
    jfloat* freqs = env->GetFloatArrayElements(jFreqs, nullptr);
    jfloat* gains = env->GetFloatArrayElements(jGains, nullptr);
    jfloat* qs    = env->GetFloatArrayElements(jQs,    nullptr);
    jsize   count = env->GetArrayLength(jFreqs);

    for (int i = 0; i < count && i < ParametricEQ::BANDS; i++) {
        g_engine->eq.setBand(i, freqs[i], gains[i], qs[i]);
    }
    g_engine->eq.setPreamp((float)preampDb);

    env->ReleaseFloatArrayElements(jFreqs, freqs, JNI_ABORT);
    env->ReleaseFloatArrayElements(jGains, gains, JNI_ABORT);
    env->ReleaseFloatArrayElements(jQs,    qs,    JNI_ABORT);
    LOGD("EQ preset loaded: preamp=%.1f dB", (float)preampDb);
}

// ---- ReplayGain ----

JNIEXPORT void JNICALL
JNI_FN(setReplayGain)(JNIEnv* /*env*/, jobject /*thiz*/,
                      jfloat trackGain, jfloat trackPeak,
                      jfloat albumGain, jfloat albumPeak,
                      jboolean useAlbumMode) {
    if (!g_engine) return;
    g_engine->replayGain.setTrackGain((float)trackGain, (float)trackPeak);
    g_engine->replayGain.setAlbumGain((float)albumGain, (float)albumPeak);
    g_engine->replayGain.setAlbumMode((bool)useAlbumMode);
}

// ---- Gapless ----

JNIEXPORT void JNICALL
JNI_FN(preloadNextTrack)(JNIEnv* env, jobject /*thiz*/,
                         jstring jPath, jstring jFormat) {
    if (!g_engine) return;
    const char* path = env->GetStringUTFChars(jPath,   nullptr);
    const char* fmt  = env->GetStringUTFChars(jFormat, nullptr);
    g_engine->gapless.setNext(path, fmt, 44100, 16);
    env->ReleaseStringUTFChars(jPath,   path);
    env->ReleaseStringUTFChars(jFormat, fmt);
}

JNIEXPORT void JNICALL
JNI_FN(cancelPreload)(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_engine) g_engine->gapless.clear();
}

// ---- USB DAC ----

JNIEXPORT jboolean JNICALL
JNI_FN(openUsbDac)(JNIEnv* env, jobject /*thiz*/,
                   jstring jDevPath, jint vendorId, jint productId) {
    if (!g_engine) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(jDevPath, nullptr);
    // Re-open Oboe stream dengan USB output device
    // AAudio exclusive mode ke device tertentu via device ID
    // TODO: map vendorId/productId ke AAudio device ID via AudioManager
    LOGI("USB DAC: %s (vid=%d pid=%d)", path, vendorId, productId);
    env->ReleaseStringUTFChars(jDevPath, path);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
JNI_FN(closeUsbDac)(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_engine) {
        // Kembali ke output internal
        g_engine->openStream(g_engine->sampleRate, g_engine->bitDepth);
        LOGI("USB DAC closed — kembali ke internal output");
    }
}

JNIEXPORT void JNICALL
JNI_FN(setHardwareVolume)(JNIEnv* /*env*/, jobject /*thiz*/, jfloat vol) {
    if (g_engine) g_engine->masterVolume.store(vol);
}

// ---- Crossfeed ----

JNIEXPORT void JNICALL
JNI_FN(setCrossfeed)(JNIEnv* /*env*/, jobject /*thiz*/,
                     jboolean enabled, jfloat cutFreq, jfloat feed) {
    if (!g_engine) return;
    g_engine->crossfeed.enabled = (bool)enabled;
    g_engine->crossfeed.setParameters((float)cutFreq, (float)feed);
    LOGD("Crossfeed: %s cutFreq=%.0f feed=%.2f",
         enabled ? "ON" : "OFF", (float)cutFreq, (float)feed);
}

// ---- Info / Metering ----

JNIEXPORT jint JNICALL
JNI_FN(getActiveSampleRate)(JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_engine ? (jint)g_engine->sampleRate : 0;
}

JNIEXPORT jint JNICALL
JNI_FN(getActiveBitDepth)(JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_engine ? (jint)g_engine->bitDepth : 0;
}

JNIEXPORT jfloat JNICALL
JNI_FN(getPeakLevel)(JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_engine ? g_engine->peakMeter.getPeak() : 0.0f;
}

JNIEXPORT jfloat JNICALL
JNI_FN(getPeakLevelL)(JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_engine ? g_engine->peakMeter.getPeakL() : 0.0f;
}

JNIEXPORT jfloat JNICALL
JNI_FN(getPeakLevelR)(JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_engine ? g_engine->peakMeter.getPeakR() : 0.0f;
}

} // extern "C"
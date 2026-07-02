package com.zaknong.airus.engine;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * MediaCodecDecoder
 *
 * Mendecode format lossy (MP3, AAC) dan ALAC menggunakan MediaCodec API.
 * Hasil decode (PCM 16-bit atau Float) dikirim ke native AudioEngine.
 */
public class MediaCodecDecoder {

    private static final String TAG = "MediaCodecDecoder";

    private final android.content.Context context;
    private final AudioEngine audioEngine;
    private MediaExtractor extractor;
    private MediaCodec codec;
    private boolean isDecoding = false;
    private Thread decodeThread;
    private volatile long seekPositionMs = -1;

    public void seekTo(long positionMs) {
        seekPositionMs = positionMs;
    }

    public MediaCodecDecoder(android.content.Context context, AudioEngine engine) {
        this.context = context;
        this.audioEngine = engine;
    }

    public void start(String path) {
        stop();
        isDecoding = true;
        decodeThread = new Thread(() -> decodeLoop(path));
        decodeThread.setName("MediaCodecDecoderThread");
        decodeThread.start();
    }

    public void stop() {
        isDecoding = false;
        if (decodeThread != null) {
            try {
                decodeThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            decodeThread = null;
        }
    }

    private void decodeLoop(String path) {
        extractor = new MediaExtractor();
        try {
            if (path.startsWith("content://")) {
                extractor.setDataSource(context, android.net.Uri.parse(path), null);
            } else {
                extractor.setDataSource(path);
            }
            int trackIndex = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    break;
                }
            }

            if (trackIndex < 0) {
                Log.e(TAG, "Tidak ada audio track di file: " + path);
                return;
            }

            extractor.selectTrack(trackIndex);
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean sawInputEOS = false;
            boolean sawOutputEOS = false;

            audioEngine.setEndOfStream(false);
            while (isDecoding && !sawOutputEOS) {
                long seekPos = seekPositionMs;
                if (seekPos >= 0) {
                    try {
                        extractor.seekTo(seekPos * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        codec.flush();
                        sawInputEOS = false;
                    } catch (Exception e) {
                        Log.e(TAG, "Gagal melakukan seek di MediaCodecDecoder: " + e.getMessage());
                    }
                    seekPositionMs = -1;
                }

                if (!sawInputEOS) {
                    int inputIndex = codec.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            sawInputEOS = true;
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000);
                if (outputIndex >= 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                    
                    // Convert PCM ke float[]
                    float[] floatSamples = convertToFloat(outputBuffer, bufferInfo, format);
                    if (floatSamples != null) {
                        audioEngine.fillBuffer(floatSamples);
                    }

                    codec.releaseOutputBuffer(outputIndex, false);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true;
                        audioEngine.setEndOfStream(true);
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    format = codec.getOutputFormat();
                    Log.d(TAG, "Output format changed: " + format);
                }
            }

        } catch (IOException e) {
            Log.e(TAG, "Error decoding file: " + path, e);
        } finally {
            if (codec != null) {
                codec.stop();
                codec.release();
                codec = null;
            }
            if (extractor != null) {
                extractor.release();
                extractor = null;
            }
        }
    }

    private float[] floatBuffer;

    private float[] convertToFloat(ByteBuffer buffer, MediaCodec.BufferInfo info, MediaFormat format) {
        int sampleSize = 2; // default PCM_16BIT
        int pcmEncoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING) ? 
                         format.getInteger(MediaFormat.KEY_PCM_ENCODING) : 
                         android.media.AudioFormat.ENCODING_PCM_16BIT;
        
        if (pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
            sampleSize = 4;
            int numSamples = info.size / sampleSize;
            if (floatBuffer == null || floatBuffer.length != numSamples) {
                floatBuffer = new float[numSamples];
            }
            buffer.order(ByteOrder.nativeOrder()).asFloatBuffer().get(floatBuffer);
            return floatBuffer;
        }

        int numSamples = info.size / 2;
        if (floatBuffer == null || floatBuffer.length != numSamples) {
            floatBuffer = new float[numSamples];
        }
        
        ShortBuffer sb = buffer.order(ByteOrder.nativeOrder()).asShortBuffer();
        for (int i = 0; i < numSamples; i++) {
            if (sb.hasRemaining()) {
                floatBuffer[i] = sb.get() / 32768.0f;
            }
        }
        return floatBuffer;
    }
}

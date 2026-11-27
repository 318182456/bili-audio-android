package com.biliaudio.app;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

final class AudioFiles {
    static final int ASR_RATE = 16000;
    private static final int COMPACT_BITRATE = 24_000;
    private static final int WINDOW_OVERLAP = ASR_RATE;

    interface WindowCallback {
        void accept(float[] samples, int startMillis, int durationMillis) throws IOException;
    }

    private AudioFiles() {}

    static void validateAudio(File file, int expectedDuration) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getPath());
            MediaFormat format = extractor.getTrackFormat(audioTrack(extractor));
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                String mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    throw new IOException("下载内容含有视频轨。");
                }
            }
            long duration = format.getLong(MediaFormat.KEY_DURATION) / 1000;
            if (Math.abs(duration - expectedDuration) > 4000) {
                throw new IOException("音轨时长与分 P 不符。");
            }
        } catch (RuntimeException exception) {
            throw new IOException("音轨无法读取。", exception);
        } finally {
            extractor.release();
        }
    }

    static long export(File source, File target, AudioLibrary.Segment segment) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        long firstSample = -1;
        try {
            extractor.setDataSource(source.getPath());
            int index = audioTrack(extractor);
            extractor.selectTrack(index);
            extractor.seekTo(segment.start() * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            muxer = new MediaMuxer(target.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = muxer.addTrack(extractor.getTrackFormat(index));
            muxer.start();
            ByteBuffer data = ByteBuffer.allocateDirect(256 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long timestamp;
            while ((timestamp = extractor.getSampleTime()) >= 0 && timestamp < segment.end() * 1000L) {
                BilibiliClient.checkInterrupted();
                if (timestamp >= segment.start() * 1000L) {
                    int length = extractor.readSampleData(data, 0);
                    if (length < 0) {
                        break;
                    }
                    if (firstSample < 0) {
                        firstSample = timestamp;
                    }
                    info.set(0, length, timestamp - firstSample, MediaCodec.BUFFER_FLAG_KEY_FRAME);
                    muxer.writeSampleData(track, data, info);
                }
                extractor.advance();
            }
            if (firstSample < 0) {
                throw new IOException("这个片段没有音频。");
            }
            muxer.stop();
            return firstSample;
        } catch (RuntimeException exception) {
            throw new IOException("片段导出失败。", exception);
        } finally {
            if (muxer != null) {
                muxer.release();
            }
            extractor.release();
        }
    }

    static String srt(List<AudioLibrary.Cue> cues, long firstSampleUs, int endMillis) {
        int actualStart = (int) ((firstSampleUs + 500) / 1000);
        StringBuilder result = new StringBuilder();
        int number = 1;
        List<AudioLibrary.Cue> clipped = new java.util.ArrayList<>();
        for (AudioLibrary.Cue cue : cues) {
            if (cue.end() > actualStart && cue.start() < endMillis) {
                clipped.add(cue);
            }
        }
        for (AudioLibrary.Cue cue : CaptionText.format(clipped)) {
            int start = Math.max(cue.start(), actualStart) - actualStart;
            int end = Math.min(cue.end(), endMillis) - actualStart;
            if (start < end) {
                result.append(number++).append('\n').append(srtTime(start)).append(" --> ")
                        .append(srtTime(end)).append('\n').append(cue.text().trim()).append("\n\n");
            }
        }
        return result.toString();
    }

    static long compact(File source, int duration, IntConsumer progress) throws IOException {
        return compact(source, duration, progress, new Compression());
    }

    static final class Compression {
        private boolean cancelled;
        private boolean committed;

        synchronized boolean cancel() {
            if (committed) {
                return false;
            }
            cancelled = true;
            return true;
        }

        synchronized boolean cancelled() { return cancelled; }

        synchronized void replace(File target, File source) throws IOException {
            BilibiliClient.checkInterrupted();
            if (cancelled) {
                throw new IOException("已取消压缩");
            }
            Files.move(target.toPath(), source.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            committed = true;
        }
    }

    static long compact(File source, int duration, IntConsumer progress, Compression job) throws IOException {
        long originalSize = source.length();
        if (duration <= 0 || originalSize <= (long) (COMPACT_BITRATE + 1500) * duration / 8000) {
            return 0;
        }
        File target = new File(source.getPath() + ".compact");
        try {
            try (AacWriter writer = new AacWriter(target)) {
                decode(source, (samples, start, length) -> {
                    writer.write(samples, start == 0 ? 0 : WINDOW_OVERLAP);
                    progress.accept((int) Math.min(99, ((long) start + length) * 100 / duration));
                });
                writer.finish();
            }
            validateAudio(target, duration);
            long saved = originalSize - target.length();
            if (saved <= 0) {
                return 0;
            }
            job.replace(target, source);
            progress.accept(100);
            return saved;
        } catch (RuntimeException error) {
            throw new IOException("手机音频编码失败，原文件可继续使用。", error);
        } finally {
            target.delete();
        }
    }

    private static final class AacWriter implements AutoCloseable {
        private final MediaCodec encoder;
        private final MediaMuxer muxer;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private int track = -1;
        private long frames;

        AacWriter(File target) throws IOException {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, ASR_RATE, 1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, COMPACT_BITRATE);
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            try {
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                encoder.start();
                muxer = new MediaMuxer(target.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } catch (IOException | RuntimeException error) {
                encoder.release();
                throw error;
            }
        }

        void write(float[] samples, int offset) throws IOException {
            while (offset < samples.length) {
                int input = awaitInput();
                ByteBuffer buffer = encoder.getInputBuffer(input).order(ByteOrder.nativeOrder());
                buffer.clear();
                int count = Math.min(samples.length - offset, buffer.remaining() / Short.BYTES);
                if (count <= 0) {
                    throw new IOException("音频编码缓冲区不可用。");
                }
                for (int index = 0; index < count; index++) {
                    float sample = Math.max(-1f, Math.min(1f, samples[offset + index]));
                    buffer.putShort((short) Math.round(sample * 32767));
                }
                encoder.queueInputBuffer(input, 0, count * Short.BYTES, presentationTime(), 0);
                frames += count;
                offset += count;
                drain(0);
            }
        }

        private int awaitInput() throws IOException {
            for (int times = 0; times < 500; times++) {
                BilibiliClient.checkInterrupted();
                int input = encoder.dequeueInputBuffer(10_000);
                if (input >= 0) {
                    return input;
                }
                drain(0);
            }
            throw new IOException("音频编码输入超时。");
        }

        private long presentationTime() { return frames * 1_000_000 / ASR_RATE; }

        void finish() throws IOException {
            if (frames == 0) {
                throw new IOException("没有可压缩的音频。");
            }
            encoder.queueInputBuffer(awaitInput(), 0, 0, presentationTime(), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            for (int times = 0; times < 500; times++) {
                BilibiliClient.checkInterrupted();
                if (drain(10_000)) {
                    muxer.stop();
                    return;
                }
            }
            throw new IOException("音频编码输出超时。");
        }

        private boolean drain(long wait) {
            int output = encoder.dequeueOutputBuffer(info, wait);
            while (output >= 0 || output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                } else {
                    boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    try {
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            muxer.writeSampleData(track, encoder.getOutputBuffer(output), info);
                        }
                    } finally {
                        encoder.releaseOutputBuffer(output, false);
                    }
                    if (end) {
                        return true;
                    }
                }
                output = encoder.dequeueOutputBuffer(info, 0);
            }
            return false;
        }

        @Override public void close() {
            try {
                encoder.release();
            } finally {
                muxer.release();
            }
        }
    }

    private static String srtTime(int millis) {
        return String.format(Locale.US, "%s,%03d", AudioLibrary.time(millis), millis % 1000);
    }

    static void decode(File file, WindowCallback callback) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        boolean started = false;
        try {
            extractor.setDataSource(file.getPath());
            int index = audioTrack(extractor);
            extractor.selectTrack(index);
            MediaFormat format = extractor.getTrackFormat(index);
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            decoder.configure(format, null, null, 0);
            decoder.start();
            started = true;
            Resampler resampler = new Resampler(callback);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean sentEos = false;
            boolean readEos = false;
            int idle = 0;
            while (!readEos) {
                BilibiliClient.checkInterrupted();
                if (!sentEos) {
                    int input = decoder.dequeueInputBuffer(10000);
                    if (input >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(input);
                        int size = extractor.readSampleData(buffer, 0);
                        sentEos = size < 0;
                        decoder.queueInputBuffer(input, 0, Math.max(0, size),
                                sentEos ? 0 : extractor.getSampleTime(),
                                sentEos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                        if (!sentEos) {
                            extractor.advance();
                        }
                    }
                }
                int output = decoder.dequeueOutputBuffer(info, 10000);
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    format = decoder.getOutputFormat();
                    resampler.sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    resampler.channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    resampler.floating = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            && format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
                } else if (output >= 0) {
                    idle = 0;
                    readEos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (info.size > 0) {
                        ByteBuffer buffer = decoder.getOutputBuffer(output).order(ByteOrder.nativeOrder());
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        resampler.read(buffer);
                    }
                    decoder.releaseOutputBuffer(output, false);
                } else if (sentEos && ++idle > 500) {
                    throw new IOException("音频解码超时。");
                }
            }
            resampler.finish();
        } catch (RuntimeException exception) {
            throw new IOException("音频解码失败。", exception);
        } finally {
            if (decoder != null) {
                if (started) {
                    decoder.stop();
                }
                decoder.release();
            }
            extractor.release();
        }
    }

    private static int audioTrack(MediaExtractor extractor) throws IOException {
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            String mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return index;
            }
        }
        throw new IOException("文件没有音轨。");
    }

    private static final class Resampler {
        private final float[] window = new float[ASR_RATE * 18];
        private final WindowCallback callback;
        private int sampleRate = 44100;
        private int channels = 1;
        private boolean floating;
        private int phase;
        private float sum;
        private int count;
        private int filled;
        private long windowStart;

        Resampler(WindowCallback callback) {
            this.callback = callback;
        }

        void read(ByteBuffer buffer) throws IOException {
            int frameBytes = channels * (floating ? Float.BYTES : Short.BYTES);
            while (buffer.remaining() >= frameBytes) {
                float mono = 0;
                for (int index = 0; index < channels; index++) {
                    mono += floating ? buffer.getFloat() : buffer.getShort() / 32768f;
                }
                sum += mono / channels;
                count++;
                phase += ASR_RATE;
                if (phase >= sampleRate) {
                    window[filled++] = sum / count;
                    sum = 0;
                    count = 0;
                    phase %= sampleRate;
                    if (filled == window.length) {
                        emit();
                        System.arraycopy(window, filled - WINDOW_OVERLAP, window, 0, WINDOW_OVERLAP);
                        windowStart += filled - WINDOW_OVERLAP;
                        filled = WINDOW_OVERLAP;
                    }
                }
            }
        }

        void finish() throws IOException {
            if (filled > 0 && (windowStart == 0 || filled > WINDOW_OVERLAP)) {
                emit();
            }
        }

        private void emit() throws IOException {
            callback.accept(Arrays.copyOf(window, filled), (int) (windowStart * 1000 / ASR_RATE),
                    filled * 1000 / ASR_RATE);
        }
    }
}

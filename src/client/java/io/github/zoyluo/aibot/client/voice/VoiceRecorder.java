package io.github.zoyluo.aibot.client.voice;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;

public final class VoiceRecorder {
    public static final AudioFormat FORMAT = new AudioFormat(16_000.0F, 16, 1, true, false);
    // 16kHz 16-bit mono = 32 bytes/ms
    private static final int BYTES_PER_MS = 32;

    private TargetDataLine line;
    private ByteArrayOutputStream buffer;
    private Thread worker;
    private volatile boolean recording;

    // VAD:capture 线程按 chunk 更新,tick 线程只读
    private volatile int rmsThreshold = Integer.MAX_VALUE;
    private volatile long startedAtMs;
    private volatile long lastLoudAtMs;
    private volatile long speechAccumMs;

    public synchronized boolean start() {
        return start(Integer.MAX_VALUE);
    }

    public synchronized boolean start(int rmsThreshold) {
        if (recording) {
            return false;
        }
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(FORMAT);
            line.start();
            buffer = new ByteArrayOutputStream();
            this.rmsThreshold = rmsThreshold <= 0 ? Integer.MAX_VALUE : rmsThreshold;
            startedAtMs = System.currentTimeMillis();
            lastLoudAtMs = 0L;
            speechAccumMs = 0L;
            recording = true;
            worker = new Thread(this::captureLoop, "aibot-voice-record");
            worker.setDaemon(true);
            worker.start();
            return true;
        } catch (Exception exception) {
            cleanup();
            return false;
        }
    }

    public synchronized byte[] stop() {
        if (!recording) {
            return new byte[0];
        }
        recording = false;
        if (line != null) {
            line.stop();
            line.close();
        }
        if (worker != null) {
            try {
                worker.join(500L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] audio = buffer == null ? new byte[0] : buffer.toByteArray();
        cleanup();
        return audio;
    }

    public boolean recording() {
        return recording;
    }

    /** 已累积的人声时长是否达到 minMs(避免刚开录就被静音判定截断)。 */
    public boolean hasSpeech(long minMs) {
        return recording && speechAccumMs >= minMs;
    }

    /** 距最后一次检测到人声过去了多久;从未检测到人声则返回 0(交由 hasSpeech 闸门拦住)。 */
    public long silenceMillis() {
        if (!recording || lastLoudAtMs == 0L) {
            return 0L;
        }
        return System.currentTimeMillis() - lastLoudAtMs;
    }

    private void captureLoop() {
        byte[] chunk = new byte[3200];
        while (recording && line != null) {
            int read = line.read(chunk, 0, chunk.length);
            if (read > 0 && buffer != null) {
                buffer.write(chunk, 0, read);
                trackLoudness(chunk, read);
            }
        }
    }

    private void trackLoudness(byte[] chunk, int length) {
        int samples = length / 2;
        if (samples == 0) {
            return;
        }
        long sumSquares = 0L;
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (short) ((chunk[i] & 0xFF) | (chunk[i + 1] << 8));
            sumSquares += (long) sample * sample;
        }
        long rms = (long) Math.sqrt((double) sumSquares / samples);
        if (rms >= rmsThreshold) {
            lastLoudAtMs = System.currentTimeMillis();
            speechAccumMs += length / BYTES_PER_MS;
        }
    }

    private void cleanup() {
        if (line != null) {
            line.close();
        }
        line = null;
        worker = null;
        recording = false;
    }
}

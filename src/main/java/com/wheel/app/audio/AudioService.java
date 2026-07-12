package com.wheel.app.audio;

import javax.sound.sampled.*;

public final class AudioService {
    public void tick() {
        beep(880, 35, 0.18);
    }

    public void selected() {
        beep(660, 130, 0.35);
    }

    private void beep(double hz, int ms, double volume) {
        new Thread(() -> {
            try {
                float sampleRate = 44100f;
                AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
                byte[] data = new byte[(int) (ms * sampleRate / 1000) * 2];
                for (int i = 0; i < data.length / 2; i++) {
                    double envelope = 1.0 - (double) i / (data.length / 2);
                    short value = (short) (Math.sin(2 * Math.PI * i * hz / sampleRate) * 32767 * volume * envelope);
                    data[i * 2] = (byte) (value & 0xff);
                    data[i * 2 + 1] = (byte) ((value >> 8) & 0xff);
                }
                try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
                    line.open(format);
                    line.start();
                    line.write(data, 0, data.length);
                    line.drain();
                }
            } catch (Exception ignored) {
            }
        }, "wheel-audio").start();
    }
}

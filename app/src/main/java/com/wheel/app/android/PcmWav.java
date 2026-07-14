package com.wheel.app.android;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Reads little-endian PCM16 WAV and converts it for offline mixing. */
final class PcmWav {
    final byte[] pcm;
    final int sampleRate, channels;

    private PcmWav(byte[] pcm, int sampleRate, int channels) {
        this.pcm = pcm;
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    static PcmWav read(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
        byte[] wav = bytes.toByteArray();
        if (wav.length < 44 || !ascii(wav, 0, "RIFF") || !ascii(wav, 8, "WAVE"))
            throw new IllegalArgumentException("经过音效不是有效 WAV");
        int rate = 0, channels = 0, bits = 0, format = 0, dataAt = -1, dataSize = 0;
        for (int p = 12; p + 8 <= wav.length;) {
            int size = le32(wav, p + 4);
            int start = p + 8;
            if (size < 0 || start + (long) size > wav.length) break;
            if (ascii(wav, p, "fmt ") && size >= 16) {
                format = le16(wav, start);
                channels = le16(wav, start + 2);
                rate = le32(wav, start + 4);
                bits = le16(wav, start + 14);
            } else if (ascii(wav, p, "data")) {
                dataAt = start;
                dataSize = size;
                break;
            }
            p = start + size + (size & 1);
        }
        if (format != 1 || bits != 16 || rate <= 0 || channels <= 0 || dataAt < 0)
            throw new IllegalArgumentException("经过音效必须是 PCM 16-bit WAV");
        int frameBytes = channels * 2;
        if (dataSize < frameBytes || dataSize % frameBytes != 0)
            throw new IllegalArgumentException("经过音效 WAV 数据不完整");
        byte[] pcm = new byte[dataSize];
        System.arraycopy(wav, dataAt, pcm, 0, dataSize);
        return new PcmWav(pcm, rate, channels);
    }

    AudioVideoMuxer.AudioInfo convert(int targetRate, int targetChannels) {
        int sourceFrames = pcm.length / (2 * channels);
        int targetFrames = Math.max(1, (int) Math.round(sourceFrames * targetRate / (double) sampleRate));
        byte[] out = new byte[targetFrames * targetChannels * 2];
        for (int frame = 0; frame < targetFrames; frame++) {
            double source = frame * sampleRate / (double) targetRate;
            int a = Math.min(sourceFrames - 1, (int) source);
            int b = Math.min(sourceFrames - 1, a + 1);
            double fraction = source - a;
            for (int channel = 0; channel < targetChannels; channel++) {
                int sourceChannel = channels == 1 ? 0 : Math.min(channel, channels - 1);
                int av = sample(a, sourceChannel), bv = sample(b, sourceChannel);
                int value = (int) Math.round(av + (bv - av) * fraction);
                int p = (frame * targetChannels + channel) * 2;
                out[p] = (byte) value;
                out[p + 1] = (byte) (value >> 8);
            }
        }
        return new AudioVideoMuxer.AudioInfo(out, targetRate, targetChannels);
    }

    private int sample(int frame, int channel) {
        int p = (frame * channels + channel) * 2;
        return (short) ((pcm[p] & 255) | (pcm[p + 1] << 8));
    }
    private static boolean ascii(byte[] value, int at, String expected) {
        if (at < 0 || at + expected.length() > value.length) return false;
        for (int i = 0; i < expected.length(); i++) if (value[at + i] != expected.charAt(i)) return false;
        return true;
    }
    private static int le16(byte[] value, int at) { return (value[at] & 255) | ((value[at + 1] & 255) << 8); }
    private static int le32(byte[] value, int at) { return le16(value, at) | (le16(value, at + 2) << 16); }
}

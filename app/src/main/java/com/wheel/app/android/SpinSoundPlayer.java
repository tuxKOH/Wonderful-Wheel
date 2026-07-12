package com.wheel.app.android;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.HandlerThread;

/** Serializes all tone work off the UI thread. Selection tones supersede queued ticks. */
final class SpinSoundPlayer {
    private static final int MSG_TICK = 1;
    private static final int MSG_SELECTED = 2;

    private final HandlerThread thread = new HandlerThread("wheel-spin-sound");
    private final Handler handler;
    private ToneGenerator tone;

    SpinSoundPlayer() {
        thread.start();
        handler = new Handler(thread.getLooper(), message -> {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
            if (message.what == MSG_SELECTED) {
                tone.stopTone();
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 140);
            } else if (message.what == MSG_TICK) {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 32);
            }
            return true;
        });
    }

    void tick() {
        if (handler.hasMessages(MSG_SELECTED)) return;
        handler.removeMessages(MSG_TICK);
        handler.sendEmptyMessage(MSG_TICK);
    }

    void selected() {
        handler.removeMessages(MSG_TICK);
        handler.removeMessages(MSG_SELECTED);
        handler.sendMessageAtFrontOfQueue(handler.obtainMessage(MSG_SELECTED));
    }

    void release() {
        handler.removeCallbacksAndMessages(null);
        handler.post(() -> {
            if (tone != null) {
                tone.release();
                tone = null;
            }
            thread.quitSafely();
        });
    }
}

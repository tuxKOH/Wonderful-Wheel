package com.wheel.app.android;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.FileInputStream;

public class PcmWavTest {
    @Test public void readsAndConvertsBundledTick() throws Exception {
        PcmWav wav;
        try(FileInputStream in=new FileInputStream("src/main/res/raw/spin_tick_v1.wav")){wav=PcmWav.read(in);}
        assertEquals(44100,wav.sampleRate);
        assertEquals(1,wav.channels);
        AudioVideoMuxer.AudioInfo converted=wav.convert(22050,2);
        assertEquals(22050,converted.sampleRate);
        assertEquals(2,converted.channels);
        assertTrue(converted.pcm.length>0);
        boolean audible=false;for(byte value:converted.pcm)if(value!=0){audible=true;break;}
        assertTrue(audible);
    }

    @Test public void mixingSaturatesAndUsesRealTick() {
        byte[] tts=new byte[]{0,0};
        AudioVideoMuxer.AudioInfo info=new AudioVideoMuxer.AudioInfo(tts,1000,1);
        AudioVideoMuxer.AudioInfo tick=new AudioVideoMuxer.AudioInfo(new byte[]{(byte)0xff,0x7f},1000,1);
        byte[] mixed=AudioVideoMuxer.withFeedback(info,tick,new long[]{0,0},false,1000);
        assertEquals((byte)0xff,mixed[0]);
        assertEquals((byte)0x7f,mixed[1]);
    }
}

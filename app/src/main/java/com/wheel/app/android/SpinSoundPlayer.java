package com.wheel.app.android;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;

import com.wheel.app.R;

import java.util.concurrent.atomic.AtomicInteger;

/** Serializes short wheel sounds off the UI thread. */
final class SpinSoundPlayer {
    private static final int MSG_TICK=1,MSG_SELECTED=2,MSG_CANCEL=3;
    private final HandlerThread thread=new HandlerThread("wheel-spin-sound");
    private final Handler handler;
    private final AtomicInteger generation=new AtomicInteger();
    private final int[] streams=new int[8];
    private SoundPool pool; private ToneGenerator tone; private int soundId,streamIndex,pendingTickGeneration=-1; private long lastTick;
    private boolean loaded; private volatile boolean accepting=true,released;

    SpinSoundPlayer(Context context){Context app=context.getApplicationContext();thread.start();handler=new Handler(thread.getLooper(),this::handle);handler.post(()->init(app));}
    private void init(Context context){if(!accepting)return;AudioAttributes a=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();pool=new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(a).build();pool.setOnLoadCompleteListener((p,id,status)->{if(!accepting||id!=soundId)return;loaded=status==0;if(loaded&&pendingTickGeneration==generation.get()){pendingTickGeneration=-1;playTick();}});soundId=pool.load(context,R.raw.spin_tick_v1,1);}
    private boolean handle(Message m){if(released)return true;if(m.what==MSG_TICK&&m.arg1==generation.get()){if(loaded&&pool!=null)playTick();else pendingTickGeneration=m.arg1;}else if(m.what==MSG_SELECTED){pendingTickGeneration=-1;lastTick=0;stopStreams();if(tone==null)tone=new ToneGenerator(AudioManager.STREAM_MUSIC,70);tone.stopTone();tone.startTone(ToneGenerator.TONE_PROP_ACK,140);}else if(m.what==MSG_CANCEL){pendingTickGeneration=-1;lastTick=0;stopStreams();if(tone!=null)tone.stopTone();}return true;}
    private void playTick(){long now=SystemClock.elapsedRealtime();if(now-lastTick<60||pool==null)return;lastTick=now;int id=pool.play(soundId,1f,1f,1,0,1f);if(id!=0)streams[streamIndex++%streams.length]=id;}
    private void stopStreams(){if(pool!=null)for(int i=0;i<streams.length;i++){if(streams[i]!=0)pool.stop(streams[i]);streams[i]=0;}streamIndex=0;}
    void tick(){if(!accepting||handler.hasMessages(MSG_SELECTED))return;handler.removeMessages(MSG_TICK);Message m=handler.obtainMessage(MSG_TICK);m.arg1=generation.get();handler.sendMessage(m);}
    void selected(){if(!accepting)return;generation.incrementAndGet();handler.removeMessages(MSG_TICK);handler.removeMessages(MSG_SELECTED);handler.sendMessageAtFrontOfQueue(handler.obtainMessage(MSG_SELECTED));}
    void cancelTicks(){if(!accepting)return;generation.incrementAndGet();handler.removeMessages(MSG_TICK);handler.removeMessages(MSG_SELECTED);handler.removeMessages(MSG_CANCEL);handler.sendMessageAtFrontOfQueue(handler.obtainMessage(MSG_CANCEL));}
    void release(){if(!accepting)return;accepting=false;generation.incrementAndGet();handler.removeCallbacksAndMessages(null);handler.postAtFrontOfQueue(()->{stopStreams();if(pool!=null){pool.setOnLoadCompleteListener(null);pool.release();pool=null;}if(tone!=null){tone.release();tone=null;}released=true;thread.quitSafely();});}
}

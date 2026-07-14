package com.wheel.app.android;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.wheel.app.model.Models.AppSettings;
import com.wheel.app.model.Models.Wheel;
import com.wheel.app.model.Models.WheelOption;
import com.wheel.app.spin.SpinEngine;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class OfflineMp4Renderer {
    interface Progress { void update(String message); }
    static final class Result { final Uri uri; final String name; Result(Uri u,String n){uri=u;name=n;} }

    private final Context context;
    private final AtomicBoolean cancelled;
    OfflineMp4Renderer(Context context, AtomicBoolean cancelled){this.context=context.getApplicationContext();this.cancelled=cancelled;}

    Result render(Wheel wheel, AppSettings settings, WheelOption target, double startRotation,
                  boolean dark, float fontScale, Progress progress) throws Exception {
        check();
        SpinEngine engine=new SpinEngine(); SpinEngine.SpinPlan plan=engine.createPlan(wheel,startRotation,target);
        File dir=new File(context.getCacheDir(),"offline-render");if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("无法创建渲染缓存");
        String base="wheel_"+System.currentTimeMillis();File speech=new File(dir,base+".tts"),video=new File(dir,base+".video.mp4"),finalMp4=new File(dir,base+".mp4");
        try{
            progress.update("正在合成语音…");AudioVideoMuxer.AudioInfo audio=synthesizeAndDecode(wheel,settings,target.text,speech);
            AudioVideoMuxer.AudioInfo tick=null;long[] tickTimes=new long[0];
            if(wheel.settings.tickSoundEnabled){try(java.io.InputStream in=context.getResources().openRawResource(com.wheel.app.R.raw.spin_tick_v1)){tick=PcmWav.read(in).convert(audio.sampleRate,audio.channels);}tickTimes=RenderTickTimeline.crossings(engine,wheel,plan,startRotation);}
            long feedbackUs=AudioVideoMuxer.durationUs(audio,false,wheel.settings.selectedSoundEnabled,0);
            progress.update("正在编码 720×1280 60fps 视频…");RenderFrameDrawer drawer=new RenderFrameDrawer(engine,wheel,settings,dark,fontScale);
            try(GlVideoEncoder encoder=new GlVideoEncoder(video)){encoder.encode(drawer,plan,startRotation,RenderTimeline.totalUs(plan,feedbackUs),this::check);}
            progress.update("正在写入 AAC 音轨…");AudioVideoMuxer.addAudio(video,finalMp4,audio,tick,tickTimes,wheel.settings.selectedSoundEnabled,plan.durationMs()*1_000L,RenderTimeline.INTRO_US,this::check);
            progress.update("正在保存到 Movies/wheel…");return publish(finalMp4,base+".mp4");
        }finally{speech.delete();video.delete();finalMp4.delete();}
    }

    private AudioVideoMuxer.AudioInfo synthesizeAndDecode(Wheel wheel,AppSettings settings,String text,File output)throws Exception{
        if(!wheel.settings.ttsEnabled)throw new IllegalStateException("当前转盘未启用 TTS，无法生成完整音频反馈");
        CountDownLatch init=new CountDownLatch(1),done=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();AtomicReference<TextToSpeech> ref=new AtomicReference<>();
        TextToSpeech tts=new TextToSpeech(context,status->{if(status!=TextToSpeech.SUCCESS)error.set("TTS 初始化失败");init.countDown();});ref.set(tts);
        String id="render-"+UUID.randomUUID();
        try{if(!await(init,15))throw new IllegalStateException("TTS 初始化超时");if(error.get()!=null)throw new IllegalStateException(error.get());Locale locale=TtsLanguageResolver.apply(wheel.settings.ttsLanguageTag,settings.defaultTtsLanguageTag,Locale.getDefault(),tts::setLanguage);if(locale==null)throw new IllegalStateException("TTS 不支持转盘、应用或系统语言");tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){public void onStart(String u){}public void onDone(String u){if(id.equals(u))done.countDown();}public void onError(String u){if(id.equals(u)){error.set("TTS 合成失败");done.countDown();}}public void onError(String u,int code){onError(u);}});int result=tts.synthesizeToFile(text,null,output,id);if(result!=TextToSpeech.SUCCESS)throw new IllegalStateException("TTS 无法开始合成");if(!await(done,60))throw new IllegalStateException("TTS 合成超时");if(error.get()!=null)throw new IllegalStateException(error.get());if(!output.isFile()||output.length()==0)throw new IllegalStateException("TTS 未生成音频文件");return AudioVideoMuxer.decode(output,this::check);
        }finally{try{tts.stop();}catch(Exception ignored){}tts.shutdown();}}
    private boolean await(CountDownLatch latch,int seconds)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(System.nanoTime()<end){check();if(latch.await(100,TimeUnit.MILLISECONDS))return true;}return false;}

    private Result publish(File source,String name)throws Exception{
        check();ContentResolver resolver=context.getContentResolver();
        if(Build.VERSION.SDK_INT>=29){ContentValues values=new ContentValues();values.put(MediaStore.Video.Media.DISPLAY_NAME,name);values.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");values.put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/wheel");values.put(MediaStore.Video.Media.IS_PENDING,1);Uri uri=resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IllegalStateException("无法创建媒体文件");boolean success=false;try(OutputStream out=resolver.openOutputStream(uri);FileInputStream in=new FileInputStream(source)){if(out==null)throw new IllegalStateException("无法打开媒体文件");copy(in,out);success=true;}finally{if(!success)resolver.delete(uri,null,null);}ContentValues ready=new ContentValues();ready.put(MediaStore.Video.Media.IS_PENDING,0);resolver.update(uri,ready,null,null);return new Result(uri,name);}
        File movies=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);File folder=new File(movies,"wheel");if(!folder.exists()&&!folder.mkdirs())throw new IllegalStateException("无法创建 Movies/wheel");File output=new File(folder,name);boolean copied=false;try(FileInputStream in=new FileInputStream(source);java.io.FileOutputStream out=new java.io.FileOutputStream(output)){copy(in,out);copied=true;}finally{if(!copied)output.delete();}AtomicReference<Uri> scanned=new AtomicReference<>(Uri.fromFile(output));CountDownLatch latch=new CountDownLatch(1);MediaScannerConnection.scanFile(context,new String[]{output.getAbsolutePath()},new String[]{"video/mp4"},(p,u)->{if(u!=null)scanned.set(u);latch.countDown();});latch.await(5,TimeUnit.SECONDS);return new Result(scanned.get(),name);
    }
    private void copy(FileInputStream in,OutputStream out)throws Exception{byte[] buffer=new byte[64*1024];int n;while((n=in.read(buffer))!=-1){check();out.write(buffer,0,n);}}
    private void check()throws Exception{if(cancelled.get()||Thread.currentThread().isInterrupted())throw new InterruptedException("渲染已取消");}
}

package com.wheel.app.android;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;

/** Decodes synthesized TTS, prepends the selection tone, encodes AAC, and remuxes it with AVC. */
final class AudioVideoMuxer {
    static final class AudioInfo { final byte[] pcm; final int sampleRate, channels; AudioInfo(byte[] p,int r,int c){pcm=p;sampleRate=r;channels=c;} }
    private AudioVideoMuxer() {}

    static AudioInfo decode(File input, GlVideoEncoder.CancelCheck cancel) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            extractor.setDataSource(input.getAbsolutePath());
            int track = -1; MediaFormat source = null;
            for (int i=0;i<extractor.getTrackCount();i++) { MediaFormat f=extractor.getTrackFormat(i); String mime=f.getString(MediaFormat.KEY_MIME); if(mime!=null&&mime.startsWith("audio/")){track=i;source=f;break;} }
            if(track<0||source==null)throw new IllegalArgumentException("TTS 文件没有可解码的音轨");
            extractor.selectTrack(track);
            decoder=MediaCodec.createDecoderByType(source.getString(MediaFormat.KEY_MIME)); decoder.configure(source,null,null,0); decoder.start();
            ByteArrayOutputStream pcm=new ByteArrayOutputStream(); MediaCodec.BufferInfo info=new MediaCodec.BufferInfo(); boolean inputDone=false,outputDone=false; int rate=source.containsKey(MediaFormat.KEY_SAMPLE_RATE)?source.getInteger(MediaFormat.KEY_SAMPLE_RATE):22050; int channels=source.containsKey(MediaFormat.KEY_CHANNEL_COUNT)?source.getInteger(MediaFormat.KEY_CHANNEL_COUNT):1;
            while(!outputDone){cancel.check();if(!inputDone){int in=decoder.dequeueInputBuffer(10_000);if(in>=0){ByteBuffer b=decoder.getInputBuffer(in);int n=extractor.readSampleData(b,0);if(n<0){decoder.queueInputBuffer(in,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{decoder.queueInputBuffer(in,0,n,extractor.getSampleTime(),0);extractor.advance();}}}
                int out=decoder.dequeueOutputBuffer(info,10_000);if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat f=decoder.getOutputFormat();rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);}else if(out>=0){ByteBuffer b=decoder.getOutputBuffer(out);if(b!=null&&info.size>0){byte[] chunk=new byte[info.size];b.position(info.offset);b.limit(info.offset+info.size);b.get(chunk);pcm.write(chunk);}outputDone=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;decoder.releaseOutputBuffer(out,false);}}
            return new AudioInfo(pcm.toByteArray(),rate,channels);
        } finally { try{extractor.release();}catch(Exception ignored){} if(decoder!=null){try{decoder.stop();}catch(Exception ignored){}try{decoder.release();}catch(Exception ignored){}} }
    }

    static long durationUs(AudioInfo info, boolean tickSound, boolean selectedTone, long spinDurationUs) {
        int toneFrames=selectedTone?info.sampleRate*180/1000:0; long feedbackFrames=toneFrames+info.pcm.length/(2L*info.channels); return (tickSound?spinDurationUs:0)+feedbackFrames*1_000_000L/info.sampleRate;
    }

    static void addAudio(File video, File output, AudioInfo info, AudioInfo tick,
                         long[] tickTimesUs, boolean selectedTone, long spinDurationUs,
                         long audioStartUs, GlVideoEncoder.CancelCheck cancel) throws Exception {
        File aac=new File(output.getParentFile(),output.getName()+".aac.tmp");
        try { encodeAac(aac,info,tick,tickTimesUs,selectedTone,spinDurationUs,cancel); remux(video,aac,output,audioStartUs,cancel); }
        finally { if(!aac.delete()&&aac.exists())aac.deleteOnExit(); }
    }

    private static void encodeAac(File output,AudioInfo info,AudioInfo tick,long[] tickTimesUs,boolean tone,long spinDurationUs,GlVideoEncoder.CancelCheck cancel)throws Exception{
        MediaCodec encoder=null;MediaMuxer muxer=null;boolean started=false;try{
            MediaFormat f=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,info.sampleRate,info.channels);f.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);f.setInteger(MediaFormat.KEY_BIT_RATE,128000);f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16384);
            encoder=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);encoder.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start();muxer=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            byte[] pcm=withFeedback(info,tick,tickTimesUs,tone,spinDurationUs);int offset=0,track=-1;long framesQueued=0;boolean inputDone=false,outputDone=false;MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();
            while(!outputDone){cancel.check();if(!inputDone){int in=encoder.dequeueInputBuffer(10_000);if(in>=0){ByteBuffer b=encoder.getInputBuffer(in);b.clear();int n=Math.min(b.remaining(),pcm.length-offset);long pts=framesQueued*1_000_000L/info.sampleRate;if(n>0){b.put(pcm,offset,n);offset+=n;framesQueued+=n/(2L*info.channels);encoder.queueInputBuffer(in,0,n,pts,0);}else{encoder.queueInputBuffer(in,0,0,pts,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}}}
                int out=encoder.dequeueOutputBuffer(bi,10_000);if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){track=muxer.addTrack(encoder.getOutputFormat());muxer.start();started=true;}else if(out>=0){ByteBuffer b=encoder.getOutputBuffer(out);if((bi.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0)bi.size=0;if(b!=null&&bi.size>0&&started){b.position(bi.offset);b.limit(bi.offset+bi.size);muxer.writeSampleData(track,b,bi);}outputDone=(bi.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;encoder.releaseOutputBuffer(out,false);}}
        }finally{if(encoder!=null){try{encoder.stop();}catch(Exception ignored){}try{encoder.release();}catch(Exception ignored){}}if(muxer!=null){try{if(started)muxer.stop();}catch(Exception ignored){}try{muxer.release();}catch(Exception ignored){}}}
    }

    static byte[] withFeedback(AudioInfo info,AudioInfo tick,long[] tickTimesUs,boolean selected,long spinDurationUs){int spinFrames=(int)(spinDurationUs*info.sampleRate/1_000_000L);int toneFrames=selected?info.sampleRate*180/1000:0;byte[] out=new byte[(spinFrames+toneFrames)*info.channels*2+info.pcm.length];if(tick!=null&&tickTimesUs!=null)for(long timeUs:tickTimesUs)mix(out,(int)(timeUs*info.sampleRate/1_000_000L),info.channels,tick);if(selected)for(int i=0;i<toneFrames;i++){double envelope=Math.min(1.0,(toneFrames-i)/(info.sampleRate*.03));short sample=(short)(Math.sin(2*Math.PI*880*i/info.sampleRate)*10000*envelope);addSample(out,spinFrames+i,info.channels,sample);}System.arraycopy(info.pcm,0,out,(spinFrames+toneFrames)*info.channels*2,info.pcm.length);return out;}
    private static void mix(byte[] out,int startFrame,int destinationChannels,AudioInfo sound){int frames=sound.pcm.length/(sound.channels*2);for(int frame=0;frame<frames;frame++)for(int channel=0;channel<destinationChannels;channel++){int sourceChannel=sound.channels==1?0:Math.min(channel,sound.channels-1);int source=(frame*sound.channels+sourceChannel)*2;short sample=(short)((sound.pcm[source]&255)|(sound.pcm[source+1]<<8));addChannelSample(out,startFrame+frame,destinationChannels,channel,sample);}}
    private static void addSample(byte[] out,int frame,int channels,short sample){for(int c=0;c<channels;c++)addChannelSample(out,frame,channels,c,sample);}
    private static void addChannelSample(byte[] out,int frame,int channels,int channel,short sample){int p=(frame*channels+channel)*2;if(p<0||p+1>=out.length)return;int current=(short)((out[p]&255)|(out[p+1]<<8));int mixed=Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,current+sample));out[p]=(byte)mixed;out[p+1]=(byte)(mixed>>8);}

    private static void remux(File video,File audio,File output,long audioStartUs,GlVideoEncoder.CancelCheck cancel)throws Exception{
        MediaExtractor v=new MediaExtractor(),a=new MediaExtractor();MediaMuxer m=null;boolean started=false;try{v.setDataSource(video.getAbsolutePath());a.setDataSource(audio.getAbsolutePath());int vt=find(v,"video/"),at=find(a,"audio/");if(vt<0||at<0)throw new IllegalStateException("编码结果缺少音视频轨");v.selectTrack(vt);a.selectTrack(at);m=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);int vo=m.addTrack(v.getTrackFormat(vt)),ao=m.addTrack(a.getTrackFormat(at));m.start();started=true;copy(v,m,vo,0,cancel);copy(a,m,ao,audioStartUs,cancel);
        }finally{v.release();a.release();if(m!=null){try{if(started)m.stop();}catch(Exception ignored){}try{m.release();}catch(Exception ignored){}}}}
    private static int find(MediaExtractor e,String prefix){for(int i=0;i<e.getTrackCount();i++){String mime=e.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith(prefix))return i;}return-1;}
    private static void copy(MediaExtractor e,MediaMuxer m,int track,long offset,GlVideoEncoder.CancelCheck cancel)throws Exception{ByteBuffer b=ByteBuffer.allocateDirect(1024*1024);MediaCodec.BufferInfo i=new MediaCodec.BufferInfo();while(true){cancel.check();b.clear();int n=e.readSampleData(b,0);if(n<0)break;i.offset=0;i.size=n;i.presentationTimeUs=e.getSampleTime()+offset;i.flags=e.getSampleFlags();m.writeSampleData(track,b,i);e.advance();}}
}

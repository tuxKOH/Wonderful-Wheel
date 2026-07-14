package com.wheel.app.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES20;
import android.view.Surface;

import com.wheel.app.spin.SpinEngine;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Encodes Canvas-rendered bitmaps through an EGL MediaCodec input surface. */
final class GlVideoEncoder implements AutoCloseable {
    interface CancelCheck { void check() throws Exception; }

    private static final int FPS = RenderTimeline.FPS;
    private final MediaCodec codec;
    private final MediaMuxer muxer;
    private final Surface surface;
    private android.opengl.EGLDisplay display;
    private android.opengl.EGLContext context;
    private android.opengl.EGLSurface eglSurface;
    private int program, texture, positionAttribute, uvAttribute;
    private FloatBuffer positions, uv;
    private int track = -1;
    private boolean muxerStarted;

    GlVideoEncoder(File output) throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                RenderFrameDrawer.WIDTH, RenderFrameDrawer.HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        surface = codec.createInputSurface();
        codec.start();
        muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        setupEgl();
    }

    void encode(RenderFrameDrawer drawer, SpinEngine.SpinPlan plan, double startRotation,
                long totalUs, CancelCheck cancel) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(RenderFrameDrawer.WIDTH, RenderFrameDrawer.HEIGHT,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        long frames=RenderTimeline.frameCount(totalUs);
        try {
            for (long frame=0;frame<frames;frame++) {
                long pts=RenderTimeline.presentationUs(frame);
                cancel.check();
                RenderTimeline.FrameState state=RenderTimeline.frame(pts,startRotation,plan,drawer.engine(),drawer.wheel());
                drawer.draw(canvas,state);
                drawBitmap(bitmap, pts);
                drain(false);
            }
            codec.signalEndOfInputStream();
            drain(true);
        } finally {
            bitmap.recycle();
        }
    }

    private void setupEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new IllegalStateException("无法初始化 EGL");
        int[] attrs = {EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                EGL14.EGL_RENDERABLE_TYPE,4, 0x3142,1, EGL14.EGL_NONE};
        android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1]; int[] count = new int[1];
        EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0);
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE}, 0);
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface,
                new int[]{EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context);
        String vertex = "attribute vec4 p;attribute vec2 u;varying vec2 v;void main(){gl_Position=p;v=u;}";
        String fragment = "precision mediump float;uniform sampler2D t;varying vec2 v;void main(){gl_FragColor=texture2D(t,v);}";
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, vertex));
        GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, fragment));
        GLES20.glLinkProgram(program);
        int[] ids = new int[1]; GLES20.glGenTextures(1, ids, 0); texture = ids[0];
        positions=floats(-1,-1,1,-1,-1,1,1,1);
        uv=floats(0,1,1,1,0,0,1,0);
        positionAttribute=GLES20.glGetAttribLocation(program,"p");
        uvAttribute=GLES20.glGetAttribLocation(program,"u");
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader); return shader;
    }

    private void drawBitmap(Bitmap bitmap, long ptsUs) {
        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context);
        GLES20.glViewport(0, 0, RenderFrameDrawer.WIDTH, RenderFrameDrawer.HEIGHT);
        GLES20.glUseProgram(program);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        positions.position(0);uv.position(0);
        GLES20.glEnableVertexAttribArray(positionAttribute); GLES20.glVertexAttribPointer(positionAttribute,2,GLES20.GL_FLOAT,false,0,positions);
        GLES20.glEnableVertexAttribArray(uvAttribute); GLES20.glVertexAttribPointer(uvAttribute,2,GLES20.GL_FLOAT,false,0,uv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsUs * 1000L);
        if (!EGL14.eglSwapBuffers(display, eglSurface)) throw new IllegalStateException("EGL 输出失败");
    }

    private FloatBuffer floats(float... values) {
        FloatBuffer out = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        out.put(values).position(0); return out;
    }

    private void drain(boolean end) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int index = codec.dequeueOutputBuffer(info, end ? 10_000 : 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) { if (end) continue; return; }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.getOutputFormat()); muxer.start(); muxerStarted = true; continue;
            }
            if (index >= 0) {
                ByteBuffer data = codec.getOutputBuffer(index);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (data != null && info.size > 0 && muxerStarted) {
                    data.position(info.offset); data.limit(info.offset + info.size); muxer.writeSampleData(track, data, info);
                }
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(index, false);
                if (eos) return;
            }
        }
    }

    public void close() {
        try { if (display != null && display != EGL14.EGL_NO_DISPLAY) { EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT); if(eglSurface!=null)EGL14.eglDestroySurface(display,eglSurface); if(context!=null)EGL14.eglDestroyContext(display,context); EGL14.eglTerminate(display); } } catch(Exception ignored){}
        try { surface.release(); } catch(Exception ignored){}
        try { codec.stop(); } catch(Exception ignored){} try { codec.release(); } catch(Exception ignored){}
        try { if(muxerStarted)muxer.stop(); } catch(Exception ignored){} try { muxer.release(); } catch(Exception ignored){}
    }
}

package com.wheel.app.android;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.wheel.app.model.Models.AppSettings;
import com.wheel.app.model.Models.Wheel;
import com.wheel.app.spin.SpinEngine;

/** Draws the main-screen region above automatic spin into a fixed 720x1280 frame. */
final class RenderFrameDrawer {
    static final int WIDTH=720,HEIGHT=1280;
    static final float SCALE=2f,LOGICAL_WIDTH=360f,LOGICAL_HEIGHT=640f;
    static final RectF WHEEL_WRAPPER=new RectF(20,126,340,446);
    static final RectF MANUAL_ROW=new RectF(20,458,340,514);

    private final SpinEngine engine;
    private final Wheel wheel;
    private final AppSettings settings;
    private final MainVisualSpec spec;
    private final float fontScale;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);

    RenderFrameDrawer(SpinEngine engine,Wheel wheel,AppSettings settings,boolean dark,float fontScale){this.engine=engine;this.wheel=wheel;this.settings=settings;this.spec=new MainVisualSpec(dark);this.fontScale=Math.max(.75f,fontScale);}

    SpinEngine engine(){return engine;}
    Wheel wheel(){return wheel;}

    void draw(Canvas canvas,RenderTimeline.FrameState state){canvas.drawColor(spec.background);canvas.save();canvas.scale(SCALE,SCALE);drawTopBar(canvas);drawTitle(canvas);drawResult(canvas,state.displayText);RectF wheelBounds=new RectF(WHEEL_WRAPPER.left+24,WHEEL_WRAPPER.top+24,WHEEL_WRAPPER.right-24,WHEEL_WRAPPER.bottom-24);WheelCanvasRenderer.draw(canvas,wheelBounds,wheel,settings,engine,state.rotation,1f,fontScale);drawManualRow(canvas,state.phase==RenderTimeline.Phase.SPIN);canvas.restore();}

    private void drawTopBar(Canvas canvas){textStyle(24,spec.plainIcon,false,Paint.Align.LEFT);canvas.drawText("⚙",20,48,paint);paint.setTextAlign(Paint.Align.RIGHT);paint.setColor(spec.dark?0xfff8fafc:0xff333333);canvas.drawText("⋮",340,48,paint);}

    private void drawTitle(Canvas canvas){textStyle(22,spec.dark?0xffaab3c2:0xffaaaaaa,false,Paint.Align.RIGHT);float nameWidth=Math.min(244,measure(wheel.name,18,true)+36),total=22+8+nameWidth,left=(LOGICAL_WIDTH-total)/2;canvas.drawText("☰",left+22,92,paint);RectF badge=new RectF(left+30,70,left+30+nameWidth,106);paint.setColor(spec.badgeFill);paint.setStyle(Paint.Style.FILL);canvas.drawRoundRect(badge,18,18,paint);textStyle(18,spec.badgeText,true,Paint.Align.CENTER);drawEllipsized(canvas,wheel.name,badge.centerX(),94,nameWidth-28);}

    private void drawResult(Canvas canvas,String value){textStyle(15,spec.secondary,false,Paint.Align.CENTER);drawEllipsized(canvas,value==null||value.isEmpty()?"准备好了":value,LOGICAL_WIDTH/2,122,320);}

    private void drawManualRow(Canvas canvas,boolean spinning){float cy=MANUAL_ROW.centerY();drawCircle(canvas,44,cy,"≡");drawCircle(canvas,316,cy,"✎");RectF button=new RectF(76,cy-24,284,cy+24);paint.setStyle(Paint.Style.FILL);paint.setColor(spinning?MainVisualSpec.DANGER:(spec.dark?0xff1f2937:Color.WHITE));canvas.drawRoundRect(button,24,24,paint);if(!spinning){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(MainVisualSpec.ACCENT);canvas.drawRoundRect(button,24,24,paint);}textStyle(16,spinning?Color.WHITE:MainVisualSpec.ACCENT,false,Paint.Align.CENTER);canvas.drawText(spinning?"终止转盘":"点击旋转",button.centerX(),cy+5,paint);}

    private void drawCircle(Canvas canvas,float cx,float cy,String value){paint.setStyle(Paint.Style.FILL);paint.setColor(spec.dark?0xff1f2937:0xfff5f6fa);canvas.drawCircle(cx,cy,24,paint);textStyle(19,spec.dark?0xfff8fafc:0xff555555,false,Paint.Align.CENTER);canvas.drawText(value,cx,cy+6,paint);}

    private void textStyle(float sp,int color,boolean bold,Paint.Align align){paint.setStyle(Paint.Style.FILL);paint.setColor(color);paint.setTextAlign(align);paint.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);paint.setTextSize(sp*fontScale);}
    private float measure(String value,float sp,boolean bold){paint.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);paint.setTextSize(sp*fontScale);return paint.measureText(value==null?"":value);}
    private void drawEllipsized(Canvas canvas,String value,float x,float baseline,float width){if(value==null)value="";if(paint.measureText(value)<=width){canvas.drawText(value,x,baseline,paint);return;}String mark="…";int end=value.length();while(end>0&&paint.measureText(value.substring(0,end)+mark)>width)end--;canvas.drawText(value.substring(0,end)+mark,x,baseline,paint);}
}

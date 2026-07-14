package com.wheel.app.android;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.wheel.app.model.Models.AppSettings;
import com.wheel.app.model.Models.Wheel;
import com.wheel.app.spin.SpinEngine;

import java.util.List;

/** Stateless wheel drawing shared by the live View and offline frames. */
final class WheelCanvasRenderer {
    private WheelCanvasRenderer() {}

    static void draw(Canvas canvas,RectF bounds,Wheel wheel,AppSettings settings,SpinEngine engine,
                     double rotation,float dp,float sp){Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);List<SpinEngine.Segment> segments=engine.segments(wheel);int[] colors=MainVisualSpec.wheelColors(wheel.settings.colorScheme);paint.setStyle(Paint.Style.FILL);for(int i=0;i<segments.size();i++){SpinEngine.Segment segment=segments.get(i);paint.setColor(colors[i%colors.length]);canvas.drawArc(bounds,(float)(segment.startAngle()+rotation),(float)segment.sweepAngle(),true,paint);}float cx=bounds.centerX(),cy=bounds.centerY(),radius=bounds.width()/2f;paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2*dp);paint.setColor(0x99ffffff);for(SpinEngine.Segment segment:segments){double angle=Math.toRadians(segment.startAngle()+rotation);canvas.drawLine(cx,cy,cx+(float)Math.cos(angle)*radius,cy+(float)Math.sin(angle)*radius,paint);}drawLabels(canvas,paint,bounds,wheel,settings,segments,rotation,dp,sp);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(4*dp);paint.setColor(Color.WHITE);canvas.drawOval(bounds,paint);drawPointerAndHub(canvas,paint,cx,cy,bounds.width(),dp);}

    private static void drawLabels(Canvas canvas,Paint paint,RectF bounds,Wheel wheel,AppSettings settings,List<SpinEngine.Segment> segments,double rotation,float dp,float sp){paint.setStyle(Paint.Style.FILL);paint.setColor(0xff111827);paint.setTypeface(Typeface.DEFAULT_BOLD);String mode=WheelTextLayout.normalizeTextDisplayMode(settings.textDisplayMode);WheelTextLayout.TextMetrics metrics=metrics(paint);for(SpinEngine.Segment segment:segments){float base=wheel.settings.fontSize*sp,radius=bounds.width()/2f,min=Math.max(8*dp,base*.58f);WheelTextLayout.LabelLayout layout=WheelTextLayout.layout(segment.option().text,base,min,radius,radius,settings.ellipsizeText,metrics);if(!layout.draw)continue;double mid=Math.toRadians(segment.startAngle()+segment.sweepAngle()/2+rotation);paint.setTextSize(layout.textSize);Paint.FontMetrics fm=paint.getFontMetrics();if("radial".equals(mode)){float start=radius*.26f,baseline=bounds.centerY()-layout.blockHeight/2-fm.ascent;paint.setTextAlign(Paint.Align.LEFT);canvas.save();canvas.rotate((float)(segment.startAngle()+segment.sweepAngle()/2+rotation),bounds.centerX(),bounds.centerY());for(String line:layout.lines){canvas.drawText(line,bounds.centerX()+start,baseline,paint);baseline+=layout.lineHeight;}canvas.restore();}else{float anchor=radius*.54f,x=(float)(bounds.centerX()+Math.cos(mid)*anchor),y=(float)(bounds.centerY()+Math.sin(mid)*anchor),baseline=y-layout.blockHeight/2-fm.ascent;paint.setTextAlign(Paint.Align.CENTER);for(String line:layout.lines){canvas.drawText(line,x,baseline,paint);baseline+=layout.lineHeight;}}}}

    private static WheelTextLayout.TextMetrics metrics(Paint paint){return new WheelTextLayout.TextMetrics(){public float measure(String text,float size){paint.setTextSize(size);return paint.measureText(text);}public float lineHeight(float size){paint.setTextSize(size);Paint.FontMetrics fm=paint.getFontMetrics();return fm.descent-fm.ascent;}};}

    private static void drawPointerAndHub(Canvas canvas,Paint paint,float cx,float cy,float diameter,float dp){paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);float hub=Math.max(14*dp,diameter*.065f),height=hub*.4f,width=hub*.35f;Path pointer=new Path();pointer.moveTo(cx,cy-hub-height);pointer.lineTo(cx-width,cy-hub+2*dp);pointer.lineTo(cx+width,cy-hub+2*dp);pointer.close();canvas.drawPath(pointer,paint);canvas.drawCircle(cx,cy,hub,paint);}
}

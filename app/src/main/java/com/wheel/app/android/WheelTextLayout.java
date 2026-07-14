package com.wheel.app.android;

import com.wheel.app.format.WheelFormats;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Pure, unit-testable wheel label layout. */
public final class WheelTextLayout {
    public interface TextMetrics {
        float measure(String text, float textSize);
        float lineHeight(float textSize);
    }

    public static final class LabelLayout {
        public final List<String> lines;
        public final float textSize, lineHeight, blockWidth, blockHeight;
        public final boolean draw, ellipsized;
        LabelLayout(List<String> lines,float size,float height,float width,boolean ellipsized){this.lines=Collections.unmodifiableList(lines);textSize=size;lineHeight=height;blockWidth=width;blockHeight=height*lines.size();draw=!lines.isEmpty();this.ellipsized=ellipsized;}
        static LabelLayout hidden(float size){return new LabelLayout(new ArrayList<>(),size,0,0,false);}
    }

    private WheelTextLayout() {}
    public static String normalizeTextDisplayMode(String value){return WheelFormats.normalizeTextDisplayMode(value);}

    public static LabelLayout layout(String text,float base,float min,float radius,float safeWidth,boolean ellipsize,TextMetrics metrics){
        if(text==null||text.isEmpty()||base<=0||radius<=0||safeWidth<=0)return LabelLayout.hidden(base);
        float width=Math.min(radius,safeWidth), maxHeight=radius/2f;
        LabelLayout atBase=make(text,base,width,metrics,false);
        if(atBase.blockHeight<=maxHeight)return atBase;
        float floor=Math.min(base,Math.max(0,min)),lo=floor,hi=base; LabelLayout best=null;
        for(int i=0;i<14;i++){float mid=(lo+hi)/2f;LabelLayout candidate=make(text,mid,width,metrics,false);if(candidate.blockHeight<=maxHeight){best=candidate;lo=mid;}else hi=mid;}
        if(best!=null)return best;
        LabelLayout smallest=make(text,floor,width,metrics,false);int maxLines=(int)Math.floor(maxHeight/Math.max(.001f,smallest.lineHeight));
        if(!ellipsize)return smallest;
        if(maxLines<=0)return LabelLayout.hidden(floor);
        ArrayList<String> visible=new ArrayList<>(smallest.lines.subList(0,Math.min(maxLines,smallest.lines.size())));
        if(smallest.lines.size()>maxLines){String tail=visible.get(visible.size()-1);String shortened=ellipsize(tail+"…",width,floor,metrics);if(shortened.isEmpty())return LabelLayout.hidden(floor);visible.set(visible.size()-1,shortened);}
        return result(visible,floor,metrics,true);
    }

    private static LabelLayout make(String text,float size,float width,TextMetrics metrics,boolean ellipsized){return result(wrap(text,width,size,metrics),size,metrics,ellipsized);}
    private static LabelLayout result(List<String> lines,float size,TextMetrics metrics,boolean ellipsized){float w=0;for(String line:lines)w=Math.max(w,metrics.measure(line,size));return new LabelLayout(new ArrayList<>(lines),size,metrics.lineHeight(size),w,ellipsized);}

    static List<String> wrap(String text,float width,float size,TextMetrics metrics){
        ArrayList<String> out=new ArrayList<>();
        for(String hard:text.split("\\R",-1)){if(hard.isEmpty()){out.add("");continue;}List<Integer>b=boundaries(hard);int start=0,lastGood=0;for(int i=1;i<b.size();i++){int end=b.get(i);String candidate=hard.substring(start,end);if(metrics.measure(candidate,size)<=width){if(isBreak(hard,end))lastGood=end;continue;}int previous=b.get(i-1);int cut=lastGood>start?lastGood:previous;if(cut<=start){cut=end;}out.add(hard.substring(start,cut).trim());start=cut;while(start<hard.length()&&Character.isWhitespace(hard.charAt(start)))start++;lastGood=start;i=indexAfter(b,start)-1;}if(start<hard.length())out.add(hard.substring(start));}
        return out;
    }
    private static int indexAfter(List<Integer>b,int value){for(int i=0;i<b.size();i++)if(b.get(i)>value)return i;return b.size();}
    private static boolean isBreak(String s,int end){if(end<=0||end>s.length())return false;int cp=s.codePointBefore(end);return Character.isWhitespace(cp)||",，。.!！？、;；:：-—".indexOf(cp)>=0;}

    public static String ellipsize(String text,float width,float size,TextMetrics metrics){
        if(text==null||text.isEmpty()||width<=0||size<=0)return "";if(metrics.measure(text,size)<=width)return text;String mark="…";if(metrics.measure(mark,size)>width)return "";List<Integer>b=boundaries(text);int lo=0,hi=b.size()-1,best=0;while(lo<=hi){int mid=(lo+hi)>>>1,end=b.get(mid);String c=text.substring(0,end)+mark;if(metrics.measure(c,size)<=width){best=end;lo=mid+1;}else hi=mid-1;}return best==0?mark:text.substring(0,best)+mark;
    }
    private static List<Integer> boundaries(String text){ArrayList<Integer>out=new ArrayList<>();out.add(0);BreakIterator it=BreakIterator.getCharacterInstance(Locale.ROOT);it.setText(text);for(int i=it.first();i!=BreakIterator.DONE;i=it.next())if(i>0)out.add(i);if(out.get(out.size()-1)!=text.length())out.add(text.length());return out;}
}

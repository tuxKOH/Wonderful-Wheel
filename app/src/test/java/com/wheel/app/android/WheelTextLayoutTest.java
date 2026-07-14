package com.wheel.app.android;

import static org.junit.Assert.*;

import org.junit.Test;

public class WheelTextLayoutTest {
    private static final WheelTextLayout.TextMetrics METRICS = new WheelTextLayout.TextMetrics() {
        public float measure(String text, float size) { return text.codePointCount(0, text.length()) * size; }
        public float lineHeight(float size) { return size; }
    };

    @Test public void widthBelowRadiusDoesNotForceEarlyWrapWhenRadiusIsProvided() {
        WheelTextLayout.LabelLayout label=WheelTextLayout.layout("abcdef",1f,.5f,6f,6f,false,METRICS);
        assertEquals(1,label.lines.size());
    }
    @Test public void widthAboveRadiusWraps() {
        WheelTextLayout.LabelLayout label=WheelTextLayout.layout("abcdef",1f,.5f,3f,3f,false,METRICS);
        assertTrue(label.lines.size()>=2); assertEquals("abcdef",String.join("",label.lines));
    }
    @Test public void heightAboveHalfRadiusShrinks() {
        WheelTextLayout.LabelLayout label=WheelTextLayout.layout("abcdefgh",4f,1f,8f,8f,true,METRICS);
        assertTrue(label.textSize<4f); assertTrue(label.blockHeight<=4.001f);
    }
    @Test public void explicitNewlineIsPreserved() {
        WheelTextLayout.LabelLayout label=WheelTextLayout.layout("A\nB",2f,1f,20f,20f,false,METRICS);
        assertEquals(2,label.lines.size()); assertEquals("B",label.lines.get(1));
    }
    @Test public void wrappingDoesNotSplitSurrogatePair() {
        WheelTextLayout.LabelLayout label=WheelTextLayout.layout("A😊B",1f,.5f,2f,2f,true,METRICS);
        for(String line:label.lines)assertFalse(line.contains("�"));
    }
    @Test public void ellipsisIsUnicodeSafe() {
        String value=WheelTextLayout.ellipsize("A😊BC",3f,1f,METRICS);
        assertFalse(value.contains("�")); assertTrue(value.endsWith("…"));
    }
    @Test public void impossibleHeightRemainsVisibleWithoutEllipsis() {
        WheelTextLayout.LabelLayout label=WheelTextLayout.layout("abcdef",2f,2f,2f,2f,false,METRICS);
        assertTrue(label.draw);
    }
}

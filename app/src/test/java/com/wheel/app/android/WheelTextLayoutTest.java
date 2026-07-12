package com.wheel.app.android;

import static org.junit.Assert.*;

import org.junit.Test;

public class WheelTextLayoutTest {
    @Test
    public void ellipsizeFitsMeasuredWidth() {
        String result = WheelTextLayout.ellipsize("abcdef", 4f, 1f, (text, size) -> text.codePointCount(0, text.length()) * size);

        assertEquals("abc…", result);
    }

    @Test
    public void ellipsizeDoesNotSplitSurrogatePair() {
        String result = WheelTextLayout.ellipsize("A😊B", 2.5f, 1f, (text, size) -> text.codePointCount(0, text.length()) * size);

        assertFalse(result.contains("�"));
        assertTrue(result.endsWith("…"));
    }

    @Test
    public void ellipsizeReturnsHiddenWhenEllipsisDoesNotFit() {
        String result = WheelTextLayout.ellipsize("abcdef", 0.5f, 1f, (text, size) -> text.length() * size);

        assertEquals("", result);
    }

    @Test
    public void radialAutoSizeShrinksForNarrowDisplaySector() {
        float size = WheelTextLayout.radialTextSize(
                "abcdef",
                20f,
                8f,
                40f,
                8.0,
                true,
                (text, textSize) -> text.length() * textSize);

        assertTrue(size < 20f);
        assertTrue(size >= 8f || size == 0f);
    }

    @Test
    public void radialAutoSizeReturnsHiddenForTinySector() {
        float size = WheelTextLayout.radialTextSize(
                "abcdef",
                20f,
                8f,
                20f,
                0.5,
                true,
                (text, textSize) -> text.length() * textSize);

        assertEquals(0f, size, 0.0001f);
    }

    @Test
    public void widerSectorAllowsAtLeastAsLargeText() {
        float narrow = WheelTextLayout.radialTextSize("abc", 20f, 8f, 60f, 12.0, true, (text, textSize) -> text.length() * textSize);
        float wide = WheelTextLayout.radialTextSize("abc", 20f, 8f, 60f, 90.0, true, (text, textSize) -> text.length() * textSize);

        assertTrue(wide >= narrow);
        assertTrue(wide <= 20f);
    }

    @Test
    public void labelsDoNotHideOrShortenForWidthWhenEllipsisOff() {
        WheelTextLayout.LabelLayout floating = WheelTextLayout.floatingLabel(
                "abcdef",
                0.5f,
                1f,
                false,
                (text, textSize) -> text.length() * textSize);
        WheelTextLayout.LabelLayout radial = WheelTextLayout.radialLabel(
                "abcdef",
                20f,
                8f,
                0.5f,
                0.5,
                true,
                false,
                (text, textSize) -> text.length() * textSize);

        assertTrue(floating.draw);
        assertEquals("abcdef", floating.text);
        assertTrue(radial.draw);
        assertEquals("abcdef", radial.text);
        assertEquals(20f, radial.textSize, 0.0001f);
    }

    @Test
    public void radialAutoSizeHonorsDisabledFlagWhenGeometryPermits() {
        float size = WheelTextLayout.radialTextSize(
                "abcdef",
                20f,
                8f,
                200f,
                90.0,
                false,
                (text, textSize) -> text.length() * textSize);

        assertEquals(20f, size, 0.0001f);
    }
}

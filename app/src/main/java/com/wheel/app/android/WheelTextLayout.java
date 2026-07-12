package com.wheel.app.android;

import com.wheel.app.format.WheelFormats;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Helpers for wheel label modes that are independent enough to unit test. */
public final class WheelTextLayout {
    public static final String MODE_FLOATING = "floating";
    public static final String MODE_RADIAL = "radial";

    public interface TextMeasurer {
        float measure(String text, float textSize);
    }

    public static final class LabelLayout {
        public final String text;
        public final float textSize;
        public final boolean draw;

        private LabelLayout(String text, float textSize, boolean draw) {
            this.text = text;
            this.textSize = textSize;
            this.draw = draw;
        }

        public static LabelLayout hidden(float textSize) {
            return new LabelLayout("", textSize, false);
        }

        public static LabelLayout visible(String text, float textSize) {
            return new LabelLayout(text, textSize, !text.isEmpty());
        }
    }

    private WheelTextLayout() {}

    public static String normalizeTextDisplayMode(String value) {
        return WheelFormats.normalizeTextDisplayMode(value);
    }

    public static LabelLayout floatingLabel(String text, float maxWidth, float textSize, boolean ellipsize, TextMeasurer measurer) {
        String rendered = renderText(text, maxWidth, textSize, ellipsize, measurer);
        return rendered.isEmpty() ? LabelLayout.hidden(textSize) : LabelLayout.visible(rendered, textSize);
    }

    public static LabelLayout floatingLabel(String text, float maxWidth, float textSize, TextMeasurer measurer) {
        return floatingLabel(text, maxWidth, textSize, true, measurer);
    }

    public static LabelLayout radialLabel(
            String text,
            float baseTextSize,
            float minTextSize,
            float maxWidth,
            double sectorSweepDegrees,
            boolean autoSize,
            boolean ellipsize,
            TextMeasurer measurer
    ) {
        float fitted = radialTextSize(text, baseTextSize, minTextSize, maxWidth, sectorSweepDegrees, autoSize, ellipsize, measurer);
        if (fitted <= 0) return LabelLayout.hidden(Math.max(0, fitted));
        String rendered = renderText(text, maxWidth, fitted, ellipsize, measurer);
        return rendered.isEmpty() ? LabelLayout.hidden(fitted) : LabelLayout.visible(rendered, fitted);
    }

    public static LabelLayout radialLabel(
            String text,
            float baseTextSize,
            float minTextSize,
            float maxWidth,
            double sectorSweepDegrees,
            boolean autoSize,
            TextMeasurer measurer
    ) {
        return radialLabel(text, baseTextSize, minTextSize, maxWidth, sectorSweepDegrees, autoSize, true, measurer);
    }

    public static String renderText(String text, float maxWidth, float textSize, boolean ellipsize, TextMeasurer measurer) {
        String safe = text == null ? "" : text;
        if (safe.isEmpty() || textSize <= 0) return "";
        if (!ellipsize) return safe;
        return ellipsize(safe, maxWidth, textSize, measurer);
    }

    public static String ellipsize(String text, float maxWidth, float textSize, TextMeasurer measurer) {
        String safe = text == null ? "" : text;
        if (safe.isEmpty() || maxWidth <= 0 || textSize <= 0) return "";
        if (measurer.measure(safe, textSize) <= maxWidth) return safe;
        String ellipsis = "…";
        if (measurer.measure(ellipsis, textSize) > maxWidth) return "";
        List<Integer> boundaries = boundaries(safe);
        int lo = 0, hi = boundaries.size() - 1, best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int end = boundaries.get(mid);
            String candidate = safe.substring(0, end) + ellipsis;
            if (measurer.measure(candidate, textSize) <= maxWidth) {
                best = end;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best <= 0 ? ellipsis : safe.substring(0, best) + ellipsis;
    }

    public static float radialTextSize(
            String text,
            float baseTextSize,
            float minTextSize,
            float maxWidth,
            double sectorSweepDegrees,
            boolean autoSize,
            boolean ellipsize,
            TextMeasurer measurer
    ) {
        if (baseTextSize <= 0 || minTextSize <= 0 || sectorSweepDegrees <= 0) return 0f;
        if (!ellipsize) return baseTextSize;
        if (maxWidth <= 0) return 0f;
        float maxSize = Math.max(minTextSize, baseTextSize);
        float angularLimit = angularTextLimit(maxWidth, sectorSweepDegrees);
        float allowedMax = Math.min(maxSize, angularLimit);
        if (allowedMax < minTextSize) return 0f;
        if (!autoSize) return Math.min(baseTextSize, allowedMax);
        float lo = minTextSize, hi = allowedMax;
        for (int i = 0; i < 12; i++) {
            float mid = (lo + hi) / 2f;
            if (measurer.measure(ellipsize(text, maxWidth, mid, measurer), mid) <= maxWidth) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    public static float radialTextSize(
            String text,
            float baseTextSize,
            float minTextSize,
            float maxWidth,
            double sectorSweepDegrees,
            boolean autoSize,
            TextMeasurer measurer
    ) {
        return radialTextSize(text, baseTextSize, minTextSize, maxWidth, sectorSweepDegrees, autoSize, true, measurer);
    }

    static float angularTextLimit(float radiusOrWidth, double sectorSweepDegrees) {
        double sweep = Math.toRadians(Math.max(0.0, sectorSweepDegrees));
        return (float) Math.max(0.0, 2.0 * radiusOrWidth * Math.sin(sweep / 2.0) * 0.72);
    }

    private static List<Integer> boundaries(String text) {
        ArrayList<Integer> out = new ArrayList<>();
        out.add(0);
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text);
        for (int i = iterator.first(); i != BreakIterator.DONE; i = iterator.next()) {
            if (i > 0) out.add(i);
        }
        if (out.get(out.size() - 1) != text.length()) out.add(text.length());
        return out;
    }
}

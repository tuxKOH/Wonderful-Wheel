package com.wheel.app.android;

import com.wheel.app.model.Models.Wheel;
import com.wheel.app.spin.SpinEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Finds audible sector crossings on the same easing timeline used by video. */
final class RenderTickTimeline {
    private static final long MIN_INTERVAL_US = 60_000L;

    private RenderTickTimeline() {}

    static long[] crossings(SpinEngine engine, Wheel wheel, SpinEngine.SpinPlan plan,
                            double startRotation) {
        double totalRotation = plan.totalRotation();
        double endRotation = startRotation + totalRotation;
        List<Long> all = new ArrayList<>();
        for (SpinEngine.Segment segment : engine.segments(wheel)) {
            double base = 270.0 - segment.startAngle();
            long turn = (long) Math.floor((startRotation - base) / 360.0) + 1;
            for (double crossing = base + turn * 360.0;
                 crossing <= endRotation + 1e-7; crossing += 360.0) {
                double eased = (crossing - startRotation) / totalRotation;
                double progress = 1.0 - Math.cbrt(Math.max(0.0, 1.0 - eased));
                all.add(Math.round(progress * plan.durationMs() * 1_000.0));
            }
        }
        Collections.sort(all);
        List<Long> audible = new ArrayList<>();
        long last = -MIN_INTERVAL_US;
        for (long time : all) if (time - last >= MIN_INTERVAL_US) {
            audible.add(time);
            last = time;
        }
        long[] result = new long[audible.size()];
        for (int i = 0; i < result.length; i++) result[i] = audible.get(i);
        return result;
    }
}

package com.wheel.app.spin;

import com.wheel.app.model.Models.Wheel;
import com.wheel.app.model.Models.WheelOption;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class SpinEngine {
    public record Segment(WheelOption option, double startAngle, double sweepAngle) {}
    public record SpinPlan(WheelOption target, double totalRotation, long durationMs) {}

    private final Random random = new Random();

    public List<Segment> segments(Wheel wheel) {
        double total = wheel.options.stream().filter(WheelOption::eligible).mapToDouble(WheelOption::displayWeight).sum();
        List<Segment> result = new ArrayList<>();
        if (total <= 0) return result;
        double angle = 0;
        for (WheelOption option : wheel.options) {
            if(!option.eligible())continue;
            double sweep = 360.0 * option.displayWeight() / total;
            result.add(new Segment(option, angle, sweep));
            angle += sweep;
        }
        return result;
    }

    public SpinPlan createPlan(Wheel wheel) {
        List<Segment> segments = segments(wheel);
        if (segments.isEmpty()) throw new IllegalArgumentException("转盘没有有效选项");
        double totalWeight = wheel.options.stream().filter(WheelOption::eligible).mapToDouble(o -> o.trueWeight).sum();
        double r = random.nextDouble() * totalWeight;
        double acc = 0;
        Segment selected = segments.get(segments.size() - 1);
        for (Segment segment : segments) {
            acc += Math.max(0, segment.option.trueWeight);
            if (r < acc) {
                selected = segment;
                break;
            }
        }
        double margin = Math.min(selected.sweepAngle * 0.15, 8.0);
        double innerSweep = Math.max(0.1, selected.sweepAngle - margin * 2);
        double targetLocalAngle = selected.startAngle + margin + random.nextDouble() * innerSweep;
        double pointerAngle = 270.0;
        double alignRotation = normalize(pointerAngle - targetLocalAngle);
        double extraTurns = 5 + random.nextInt(4);
        return new SpinPlan(selected.option, extraTurns * 360.0 + alignRotation, wheel.settings.rotationDurationMs);
    }

    public WheelOption optionAtPointer(Wheel wheel, double rotationDegrees) {
        double localAngle = normalize(270.0 - rotationDegrees);
        List<Segment> visible=segments(wheel);for (Segment segment : visible) {
            if (localAngle >= segment.startAngle && localAngle < segment.startAngle + segment.sweepAngle) return segment.option;
        }
        return visible.isEmpty()?null:visible.get(visible.size()-1).option();
    }

    private static double normalize(double a) {
        a %= 360.0;
        if (a < 0) a += 360.0;
        return a;
    }
}

package com.wheel.app.spin;

import com.wheel.app.model.Models.Wheel;
import com.wheel.app.model.Models.WheelOption;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class SpinEngine {
    public record Segment(WheelOption option, double startAngle, double sweepAngle) {}
    public record SpinPlan(WheelOption target, double totalRotation, long durationMs) {}

    private final Random random;

    public SpinEngine() {
        this(new Random());
    }

    SpinEngine(Random random) {
        this.random = random;
    }

    public List<Segment> segments(Wheel wheel) {
        double total = wheel.options.stream().filter(WheelOption::eligible).mapToDouble(this::displayWeight).sum();
        List<Segment> result = new ArrayList<>();
        if (total <= 0) return result;
        double angle = 0;
        for (WheelOption option : wheel.options) {
            if(!option.eligible())continue;
            double sweep = 360.0 * displayWeight(option) / total;
            result.add(new Segment(option, angle, sweep));
            angle += sweep;
        }
        return result;
    }

    public SpinPlan createPlan(Wheel wheel, double currentRotation) {
        List<Segment> displaySegments = segments(wheel);
        double totalWeight = wheel.options.stream().filter(WheelOption::eligible).mapToDouble(o -> o.trueWeight).sum();
        if (displaySegments.isEmpty() || totalWeight <= 0) throw new IllegalArgumentException("转盘没有有效选项");
        double r = random.nextDouble() * totalWeight, acc = 0; WheelOption target = displaySegments.get(displaySegments.size()-1).option();
        for (WheelOption option : wheel.options) { if(!option.eligible())continue;acc += option.trueWeight; if (r < acc) { target = option; break; } }
        return createPlan(wheel,currentRotation,target);
    }

    public SpinPlan createPlan(Wheel wheel,double currentRotation,WheelOption forcedTarget){
        Segment selected=null;for(Segment segment:segments(wheel))if(segment.option().id.equals(forcedTarget.id)){selected=segment;break;}
        if(selected==null||selected.sweepAngle()<=0)throw new IllegalArgumentException("指定项没有可显示扇区");
        double margin = Math.min(selected.sweepAngle() * 0.15, 8.0);
        double innerSweep = Math.max(0.1, selected.sweepAngle() - margin * 2);
        double targetLocalAngle = selected.startAngle() + margin + random.nextDouble() * innerSweep;
        double pointerAngle = 270.0;
        double alignmentDelta = normalize(pointerAngle - targetLocalAngle - normalize(currentRotation));
        double extraTurns = 5 + random.nextInt(4);
        return new SpinPlan(selected.option(), extraTurns * 360.0 + alignmentDelta, wheel.settings.rotationDurationMs);
    }

    public static double easeOutCubic(double t){double value=Math.max(0,Math.min(1,t));return 1-Math.pow(1-value,3);}
    public static double rotationAt(double start,SpinPlan plan,double elapsedMs){return start+plan.totalRotation()*easeOutCubic(elapsedMs/plan.durationMs());}

    private double displayWeight(WheelOption option) {
        double displayed = option.fakeWeight == null ? option.trueWeight : option.fakeWeight;
        return Math.max(0, displayed);
    }

    public WheelOption optionAtPointer(Wheel wheel, double rotationDegrees) {
        double localAngle = normalize(270.0 - rotationDegrees);
        List<Segment> visible=segments(wheel);
        for (Segment segment : visible) {
            if (localAngle >= segment.startAngle && localAngle < segment.startAngle + segment.sweepAngle) return segment.option;
        }
        return visible.isEmpty() ? null : visible.get(visible.size()-1).option();
    }

    private static double normalize(double a) {
        a %= 360.0;
        if (a < 0) a += 360.0;
        return a;
    }
}

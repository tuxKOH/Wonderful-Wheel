package com.wheel.app.spin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.wheel.app.model.Models.Wheel;
import com.wheel.app.model.Models.WheelOption;

import org.junit.Test;

import java.util.List;
import java.util.Random;

public class SpinEngineTest {
    @Test
    public void segmentsUseFakeWeightWithTrueWeightFallback() {
        Wheel wheel = wheel(
                new WheelOption("A", 100, 1.0),
                new WheelOption("B", 1, null));

        List<SpinEngine.Segment> segments = new SpinEngine(new Random(1)).segments(wheel);

        assertEquals(180.0, segments.get(0).sweepAngle(), 0.0001);
        assertEquals(180.0, segments.get(1).sweepAngle(), 0.0001);
    }

    @Test
    public void selectionUsesOnlyTrueWeight() {
        WheelOption never = new WheelOption("Never", 0, 1000.0);
        WheelOption always = new WheelOption("Always", 1, 1.0);
        SpinEngine engine = new SpinEngine(new Random(2));

        SpinEngine.SpinPlan plan = engine.createPlan(wheel(never, always), 0);

        assertSame(always, plan.target());
    }

    @Test
    public void planAlignsDisplayedTargetFromCurrentRotationAcrossSpins() {
        WheelOption a = new WheelOption("A", 1, 9.0);
        WheelOption b = new WheelOption("B", 9, 1.0);
        Wheel wheel = wheel(a, b);
        SpinEngine engine = new SpinEngine(new Random(7));
        double currentRotation = 287.25;

        SpinEngine.SpinPlan plan = engine.createPlan(wheel, currentRotation);
        double finalRotation = currentRotation + plan.totalRotation();

        assertSame(plan.target(), engine.optionAtPointer(wheel, finalRotation));
    }

    private static Wheel wheel(WheelOption... options) {
        Wheel wheel = new Wheel();
        wheel.options.addAll(List.of(options));
        return wheel;
    }
}

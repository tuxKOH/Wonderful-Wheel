package com.wheel.app;

import com.wheel.app.format.WheelFormats;
import com.wheel.app.model.Models.*;
import com.wheel.app.search.WheelSearch;
import com.wheel.app.spin.SpinEngine;

import java.util.List;

public final class SmokeTest {
    public static void main(String[] args) throws Exception {
        Wheel wheel = new Wheel();
        wheel.name = "Lunch";
        wheel.options.add(new WheelOption("A", 1, null));
        wheel.options.add(new WheelOption("B", 1, 99.0));
        wheel.options.add(new WheelOption("C", 2, null));

        SpinEngine engine = new SpinEngine();
        List<SpinEngine.Segment> segments = engine.segments(wheel);
        assertNear(90, segments.get(0).sweepAngle());
        assertNear(90, segments.get(1).sweepAngle());
        assertNear(180, segments.get(2).sweepAngle());

        WheelLibrary library = new WheelLibrary();
        library.wheels.add(wheel);
        byte[] wwd = WheelFormats.exportWwd(library, false);
        WheelLibrary restored = WheelFormats.importWwd(wwd);
        if (restored.wheels.size() != 1 || restored.wheels.get(0).options.get(1).fakeWeight == null) throw new AssertionError("WWD round trip failed");

        byte[] pwh = WheelFormats.exportPwh(library.wheels);
        List<Wheel> pwhRestored = WheelFormats.importPwh(pwh, null);
        if (pwhRestored.size() != 1 || pwhRestored.get(0).options.get(1).fakeWeight != null) throw new AssertionError("PWH compatibility failed");

        Wheel titleHit = new Wheel(); titleHit.name = "pizza"; titleHit.options.add(new WheelOption("x", 1, null));
        Wheel optionHit = new Wheel(); optionHit.name = "x"; optionHit.options.add(new WheelOption("pizza", 1, null));
        List<WheelSearch.Result> results = WheelSearch.search(List.of(optionHit, titleHit), "pizza");
        if (results.get(0).wheel() != titleHit) throw new AssertionError("Search title weight failed");

        System.out.println("Smoke tests passed");
    }

    private static void assertNear(double expected, double actual) {
        if (Math.abs(expected - actual) > 0.0001) throw new AssertionError("Expected " + expected + " got " + actual);
    }
}

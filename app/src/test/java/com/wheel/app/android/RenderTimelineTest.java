package com.wheel.app.android;

import com.wheel.app.model.Models.Wheel;
import com.wheel.app.model.Models.WheelOption;
import com.wheel.app.spin.SpinEngine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RenderTimelineTest {
    @Test public void hasOneSecondBookendsAndCubicSpin() {
        Wheel wheel=wheel();SpinEngine engine=new SpinEngine();WheelOption a=wheel.options.get(0);SpinEngine.SpinPlan plan=engine.createPlan(wheel,20,a);
        assertEquals(20,RenderTimeline.rotation(500_000,20,plan),.001);
        assertEquals(20,RenderTimeline.rotation(1_000_000,20,plan),.001);
        assertTrue(RenderTimeline.rotation(2_500_000,20,plan)>20+plan.totalRotation()/2);
        assertEquals(20+plan.totalRotation(),RenderTimeline.rotation(4_000_000,20,plan),.001);
        assertEquals(5_750_000,RenderTimeline.totalUs(plan,750_000));
    }

    @Test public void sixtyFpsHasExactSecondBoundariesWithoutDrift() {
        assertEquals(0,RenderTimeline.presentationUs(0));
        assertEquals(1_000_000,RenderTimeline.presentationUs(60));
        assertEquals(60,RenderTimeline.frameCount(1_000_000));
        for(int i=1;i<600;i++){long gap=RenderTimeline.presentationUs(i)-RenderTimeline.presentationUs(i-1);assertTrue(gap==16_666||gap==16_667);}
        assertEquals(10_000_000,RenderTimeline.presentationUs(600));
    }

    @Test public void frameTextTracksPointerThenLocksTarget() {
        Wheel wheel=wheel();SpinEngine engine=new SpinEngine();WheelOption target=wheel.options.get(1);SpinEngine.SpinPlan plan=engine.createPlan(wheel,17,target);
        RenderTimeline.FrameState intro=RenderTimeline.frame(999_999,17,plan,engine,wheel);
        assertEquals(RenderTimeline.Phase.INTRO,intro.phase);assertEquals("准备好了",intro.displayText);
        long during=RenderTimeline.INTRO_US+plan.durationMs()*500L;
        RenderTimeline.FrameState spin=RenderTimeline.frame(during,17,plan,engine,wheel);
        assertEquals(RenderTimeline.Phase.SPIN,spin.phase);assertEquals(engine.optionAtPointer(wheel,spin.rotation).text,spin.displayText);
        RenderTimeline.FrameState outro=RenderTimeline.frame(RenderTimeline.spinEndUs(plan),17,plan,engine,wheel);
        assertEquals(RenderTimeline.Phase.OUTRO,outro.phase);assertEquals(target.text,outro.displayText);
    }

    private static Wheel wheel(){Wheel wheel=new Wheel();wheel.settings.rotationDurationMs=3000;wheel.options.add(new WheelOption("A",1,null));wheel.options.add(new WheelOption("B",2,1.5));return wheel;}
}

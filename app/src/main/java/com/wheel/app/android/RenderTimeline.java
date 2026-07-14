package com.wheel.app.android;

import com.wheel.app.model.Models.Wheel;
import com.wheel.app.model.Models.WheelOption;
import com.wheel.app.spin.SpinEngine;

/** Timing and frame state shared by the offline video and audio encoders. */
final class RenderTimeline {
    static final int FPS=60;
    static final long INTRO_US=1_000_000L,OUTRO_US=1_000_000L;
    enum Phase { INTRO, SPIN, OUTRO }
    static final class FrameState { final Phase phase;final double rotation;final String displayText;FrameState(Phase phase,double rotation,String text){this.phase=phase;this.rotation=rotation;displayText=text;} }

    private RenderTimeline() {}
    static long spinEndUs(SpinEngine.SpinPlan plan){return INTRO_US+plan.durationMs()*1_000L;}
    static long totalUs(SpinEngine.SpinPlan plan,long feedbackUs){return spinEndUs(plan)+Math.max(0,feedbackUs)+OUTRO_US;}
    static long presentationUs(long frameIndex){return frameIndex*1_000_000L/FPS;}
    static long frameCount(long totalUs){return Math.max(1,(totalUs*FPS+999_999L)/1_000_000L);}

    static double rotation(long presentationUs,double startRotation,SpinEngine.SpinPlan plan){if(presentationUs<=INTRO_US)return startRotation;if(presentationUs>=spinEndUs(plan))return startRotation+plan.totalRotation();return SpinEngine.rotationAt(startRotation,plan,(presentationUs-INTRO_US)/1_000.0);}

    static FrameState frame(long presentationUs,double startRotation,SpinEngine.SpinPlan plan,SpinEngine engine,Wheel wheel){double angle=rotation(presentationUs,startRotation,plan);if(presentationUs<INTRO_US)return new FrameState(Phase.INTRO,angle,"准备好了");if(presentationUs>=spinEndUs(plan))return new FrameState(Phase.OUTRO,angle,plan.target().text);WheelOption current=engine.optionAtPointer(wheel,angle);return new FrameState(Phase.SPIN,angle,current==null?"准备好了":current.text);}
}

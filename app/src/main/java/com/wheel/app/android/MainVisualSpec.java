package com.wheel.app.android;

import android.graphics.Color;

import com.wheel.app.format.WheelFormats;

/** Shared visual constants for the live main screen and rendered video. */
final class MainVisualSpec {
    static final int ACCENT=0xffea4b3b,DANGER=0xffef4444;
    final boolean dark;
    final int background,panel,card,text,secondary,stroke,badgeFill,badgeText,plainIcon;

    MainVisualSpec(boolean dark){this.dark=dark;background=dark?0xff0f172a:Color.WHITE;panel=dark?0xff111827:Color.WHITE;card=dark?0xff182235:Color.WHITE;text=dark?0xfff8fafc:0xff222222;secondary=dark?0xffaab3c2:0xff888888;stroke=dark?0xff263244:0xffe5e7eb;badgeFill=dark?0xff1f2937:0xfff5f6fa;badgeText=dark?0xfff8fafc:0xff333333;plainIcon=dark?0xffaab3c2:0xff555555;}

    static int[] wheelColors(String scheme){scheme=WheelFormats.normalizeColorScheme(scheme);if("pastel".equals(scheme))return new int[]{0xffffb3ba,0xffffdfba,0xffffffba,0xffbaffc9,0xffbae1ff};if("vivid".equals(scheme))return new int[]{0xffff5252,0xffffc107,0xff22c55e,0xff06b6d4,0xffd946ef};if("mono".equals(scheme))return new int[]{0xffeeeeee,0xffbbbbbb,0xff888888,0xff555555};return new int[]{0xffffb703,0xfffb8500,0xff8ecae6,0xff219ebc,0xffff006e,0xff8338ec};}
}

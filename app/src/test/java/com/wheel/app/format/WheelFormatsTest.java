package com.wheel.app.format;

import static org.junit.Assert.*;

import com.wheel.app.model.Models.AppSettings;
import com.wheel.app.model.Models.Wheel;
import com.wheel.app.model.Models.WheelLibrary;
import com.wheel.app.model.Models.WheelOption;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class WheelFormatsTest {
    @Test
    public void wwdRoundTripsAppSettingsAndWheelTextSettings() throws Exception {
        WheelLibrary library = new WheelLibrary();
        library.settings.defaultRotationDurationMs = 7000;
        library.settings.defaultColorScheme = "pastel";
        library.settings.defaultFontSize = 22;
        library.settings.tickSoundEnabled = false;
        library.settings.selectedSoundEnabled = false;
        library.settings.ttsEnabled = false;
        library.settings.defaultTtsLanguageTag = "ja-JP";
        library.settings.textDisplayMode = "radial";
        library.settings.radialTextAutoSize = true;
        library.settings.ellipsizeText = true;

        Wheel wheel = new Wheel();
        wheel.name = "Test";
        wheel.options.add(new WheelOption("Alpha", 1, null));
        wheel.settings.textDisplayMode = "radial";
        wheel.settings.radialTextAutoSize = false;
        wheel.settings.ttsLanguageTag = "en-GB";
        library.wheels.add(wheel);

        WheelLibrary imported = WheelFormats.importWwd(WheelFormats.exportWwd(library, false));
        AppSettings settings = imported.settings;

        assertEquals(7000, settings.defaultRotationDurationMs);
        assertEquals("pastel", settings.defaultColorScheme);
        assertEquals(22, settings.defaultFontSize);
        assertFalse(settings.tickSoundEnabled);
        assertFalse(settings.selectedSoundEnabled);
        assertFalse(settings.ttsEnabled);
        assertEquals("ja-JP", settings.defaultTtsLanguageTag);
        assertEquals("radial", settings.textDisplayMode);
        assertTrue(settings.radialTextAutoSize);
        assertTrue(settings.ellipsizeText);
        assertEquals("radial", imported.wheels.get(0).settings.textDisplayMode);
        assertFalse(imported.wheels.get(0).settings.radialTextAutoSize);
        assertEquals("en-GB", imported.wheels.get(0).settings.ttsLanguageTag);
    }

    @Test
    public void textAndColorSettingsNormalizeUnknownValues() throws Exception {
        String json = "{\"format\":\"wwd\",\"version\":1,\"settings\":{\"defaultColorScheme\":\"neon\",\"textDisplayMode\":\"diagonal\"},\"wheels\":[{\"name\":\"W\",\"settings\":{\"colorScheme\":\"bad\",\"textDisplayMode\":\"diagonal\"},\"options\":[{\"text\":\"A\",\"trueWeight\":1}]}]}";

        WheelLibrary imported = WheelFormats.importWwd(json.getBytes(StandardCharsets.UTF_8));

        assertEquals("floating", imported.settings.textDisplayMode);
        assertEquals("classic", imported.settings.defaultColorScheme);
        assertEquals("classic", imported.wheels.get(0).settings.colorScheme);
        assertEquals("floating", imported.wheels.get(0).settings.textDisplayMode);
        assertEquals("floating", WheelFormats.normalizeTextDisplayMode(null));
        assertEquals("floating", WheelFormats.normalizeTextDisplayMode(""));
        assertEquals("radial", WheelFormats.normalizeTextDisplayMode("radial"));
        assertEquals("classic", WheelFormats.normalizeColorScheme(null));
        assertEquals("pastel", WheelFormats.normalizeColorScheme("pastel"));
    }

    @Test
    public void missingNewFieldsUseBackwardCompatibleDefaults() throws Exception {
        String json = "{\"format\":\"wwd\",\"version\":1,\"wheels\":[{\"name\":\"W\",\"options\":[{\"text\":\"A\",\"trueWeight\":1}]}]}";

        WheelLibrary imported = WheelFormats.importWwd(json.getBytes(StandardCharsets.UTF_8));

        assertEquals("", imported.settings.defaultTtsLanguageTag);
        assertEquals("floating", imported.settings.textDisplayMode);
        assertFalse(imported.settings.radialTextAutoSize);
        assertFalse(imported.settings.ellipsizeText);
        assertEquals("floating", imported.wheels.get(0).settings.textDisplayMode);
        assertFalse(imported.wheels.get(0).settings.radialTextAutoSize);
    }
}

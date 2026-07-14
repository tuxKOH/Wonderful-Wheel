package com.wheel.app.format;

import static org.junit.Assert.*;

import com.wheel.app.model.Models.AppSettings;
import com.wheel.app.model.Models.SpinHistoryEntry;
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

    @Test
    public void historyRoundTripsAndKeepsOrphans() throws Exception {
        WheelLibrary library = new WheelLibrary();
        Wheel wheel = new Wheel(); wheel.name = "Renamed"; wheel.options.add(new WheelOption("New", 1, null)); library.wheels.add(wheel);
        SpinHistoryEntry entry = new SpinHistoryEntry(); entry.wheelId = wheel.id; entry.wheelName = "Old wheel"; entry.optionId = wheel.options.get(0).id; entry.optionText = "Old option"; entry.createdAt = 1234; library.history.add(entry);
        SpinHistoryEntry orphan = new SpinHistoryEntry(); orphan.wheelId = "deleted"; orphan.wheelName = "Deleted"; orphan.optionText = "Result"; orphan.createdAt = 5678; library.history.add(orphan);

        WheelLibrary imported = WheelFormats.importWwd(WheelFormats.exportWwd(library, false));

        assertEquals(2, imported.history.size());
        assertEquals("Old wheel", imported.history.get(0).wheelName);
        assertEquals("Old option", imported.history.get(0).optionText);
        assertEquals(1234, imported.history.get(0).createdAt);
        assertEquals("deleted", imported.history.get(1).wheelId);
    }

    @Test
    public void missingAndInvalidHistoryStayBackwardCompatible() throws Exception {
        String json = "{\"format\":\"wwd\",\"version\":1,\"wheels\":[],\"history\":[null,{}, {\"wheelId\":\"w\",\"optionText\":\"Result\",\"createdAt\":9}]}";

        WheelLibrary imported = WheelFormats.importWwd(json.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, imported.history.size());
        assertEquals("Result", imported.history.get(0).optionText);
        assertEquals(9, imported.history.get(0).createdAt);
        assertTrue(WheelFormats.importWwd("{\"format\":\"wwd\",\"version\":1}".getBytes(StandardCharsets.UTF_8)).history.isEmpty());
    }

    @Test
    public void pwhRejectsMissingMagic() {
        try {
            WheelFormats.importPwh("not-pwh".getBytes(StandardCharsets.UTF_8), null);
            fail("Expected IOException");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("pwh"));
        }
    }

    @Test
    public void pwhExportContainsOnlyPortableWheelData() throws Exception {
        Wheel first = new Wheel(); first.name = "First"; first.groupId = "private-group";
        WheelOption hidden = new WheelOption("Hidden too", 0, 99.0); hidden.hidden = true; first.options.add(hidden);
        Wheel second = new Wheel(); second.name = "Second"; second.options.add(new WheelOption("B", 2, null));
        byte[] raw = WheelFormats.exportPwh(java.util.List.of(first, second));
        assertEquals(2, WheelFormats.importPwh(raw, null).size());
        java.io.ByteArrayInputStream bytes = new java.io.ByteArrayInputStream(raw, 3, raw.length - 3);
        String json = new String(new java.util.zip.GZIPInputStream(bytes).readAllBytes(), StandardCharsets.UTF_8);
        assertFalse(json.contains("exportTime")); assertFalse(json.contains("version")); assertFalse(json.contains("dbId"));
        assertFalse(json.contains("private-group")); assertFalse(json.contains("fakeWeight")); assertTrue(json.contains("Hidden too"));
    }
}

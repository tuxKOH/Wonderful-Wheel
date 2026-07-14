package com.wheel.app.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Models {
    private Models() {}

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static long nowMs() {
        return System.currentTimeMillis();
    }

    public static final class WheelOption {
        public String id = newId();
        public String text = "";
        public double trueWeight = 1.0;
        public Double fakeWeight;
        public boolean hidden;

        public WheelOption() {}

        public WheelOption(String text, double trueWeight, Double fakeWeight) {
            this.text = text;
            this.trueWeight = trueWeight;
            this.fakeWeight = fakeWeight;
        }

        public double displayWeight() { return fakeWeight == null ? trueWeight : fakeWeight; }
        public boolean eligible() { return !hidden && trueWeight > 0 && displayWeight() > 0; }
    }

    public static final class WheelSettings {
        public long rotationDurationMs = 5000;
        public String colorScheme = "classic";
        public int fontSize = 16;
        public boolean tickSoundEnabled = true;
        public boolean selectedSoundEnabled = true;
        public boolean ttsEnabled = true;
    }

    public static final class Wheel {
        public String id = newId();
        public String name = "Untitled";
        public String groupId;
        public List<WheelOption> options = new ArrayList<>();
        public WheelSettings settings = new WheelSettings();
        public long createdAt = nowMs();
        public long updatedAt = nowMs();
    }

    public static final class WheelGroup {
        public String id = newId();
        public String name = "Group";
        public String parentId;
        public long createdAt = nowMs();
        public long updatedAt = nowMs();
    }

    public static final class AppSettings {
        public long defaultRotationDurationMs = 5000;
        public String defaultColorScheme = "classic";
        public int defaultFontSize = 16;
        public boolean tickSoundEnabled = true;
        public boolean selectedSoundEnabled = true;
        public boolean ttsEnabled = true;
    }

    public static final class SpinHistoryEntry {
        public String id = newId();
        public String wheelId;
        public String wheelName = "";
        public String optionId;
        public String optionText = "";
        public long createdAt = nowMs();
    }

    public static final class WheelLibrary {
        public int version = 3;
        public List<Wheel> wheels = new ArrayList<>();
        public List<WheelGroup> groups = new ArrayList<>();
        public List<SpinHistoryEntry> history = new ArrayList<>();
        public AppSettings settings = new AppSettings();

        public String uniqueWheelName(String base) {
            String clean = base == null || base.isBlank() ? "Untitled" : base.trim();
            if (wheels.stream().noneMatch(w -> w.name.equals(clean))) return clean;
            int i = 2;
            while (true) {
                String candidate = clean + " (" + i + ")";
                if (wheels.stream().noneMatch(w -> w.name.equals(candidate))) return candidate;
                i++;
            }
        }
    }
}

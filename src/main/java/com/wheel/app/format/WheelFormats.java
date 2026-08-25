package com.wheel.app.format;

import com.wheel.app.model.Models.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class WheelFormats {
    private static final byte[] PWH = new byte[]{'p', 'w', 'h'};

    private WheelFormats() {}

    public static List<Wheel> importPwh(byte[] raw, String groupId) throws IOException {
        return importPwhBatch(raw, groupId, 0, Integer.MAX_VALUE);
    }

    public static int pwhWheelCount(byte[] raw) throws IOException {
        Object root = SimpleJson.parse(decodePwh(raw));
        if (!(root instanceof Map<?, ?> map) || !(map.get("wheels") instanceof List<?> wheels)) throw new IOException("PWH JSON 缺少 wheels 数组");
        return wheels.size();
    }

    public static List<Wheel> importPwhBatch(byte[] raw, String groupId, int start, int limit) throws IOException {
        String json = decodePwh(raw);
        Object root = SimpleJson.parse(json);
        if (!(root instanceof Map<?, ?> map)) throw new IOException("PWH JSON 顶层不是对象");
        Object wheelsObj = map.get("wheels");
        if (!(wheelsObj instanceof List<?> wheelList)) throw new IOException("PWH JSON 缺少 wheels 数组");
        List<Wheel> result = new ArrayList<>();
        int validIndex = 0;
        int end = Math.max(start, start + Math.max(0, limit));
        for (Object wObj : wheelList) {
            if (!(wObj instanceof Map<?, ?> wm)) continue;
            Object itemsObj = wm.get("items");
            if (!(itemsObj instanceof List<?> items)) continue;
            if (validIndex < start || validIndex >= end) { validIndex++; continue; }
            Wheel wheel = new Wheel();
            wheel.name = str(wm.get("title"), "Untitled");
            wheel.groupId = groupId;
            {
                for (Object itemObj : items) {
                    if (!(itemObj instanceof Map<?, ?> im)) continue;
                    String text = str(im.get("text"), "").trim();
                    if (text.isEmpty()) continue;
                    double weight = positive(num(im.get("weight"), 1.0), 1.0);
                    wheel.options.add(new WheelOption(text, weight, null));
                }
            }
            if (!wheel.options.isEmpty()) result.add(wheel);
            validIndex++;
        }
        return result;
    }

    private static String decodePwh(byte[] raw) throws IOException {
        if (raw.length < 4 || raw[0] != 'p' || raw[1] != 'w' || raw[2] != 'h') throw new IOException("不是有效的 PWH 文件：缺少 pwh 头部");
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw, 3, raw.length - 3))) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    public static byte[] exportPwh(List<Wheel> wheels) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> outWheels = new ArrayList<>();
        for (Wheel wheel : wheels) {
            Map<String, Object> wm = new LinkedHashMap<>();
            wm.put("title", wheel.name);
            List<Object> items = new ArrayList<>();
            for (WheelOption option : wheel.options) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("text", option.text);
                im.put("weight", option.trueWeight);
                items.add(im);
            }
            wm.put("items", items);
            outWheels.add(wm);
        }
        root.put("wheels", outWheels);
        byte[] json = SimpleJson.stringify(root).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(PWH);
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(json);
        }
        return bytes.toByteArray();
    }

    public static WheelLibrary importWwd(byte[] raw) throws IOException {
        String text = decodeMaybeCompressed(raw, "wwd");
        Object root = SimpleJson.parse(text);
        if (!(root instanceof Map<?, ?> map)) throw new IOException("WWD JSON 顶层不是对象");
        WheelLibrary library = new WheelLibrary();
        Object groupsObj = map.get("groups");
        if (groupsObj instanceof List<?> groups) {
            for (Object gObj : groups) if (gObj instanceof Map<?, ?> gm) {
                WheelGroup g = new WheelGroup();
                g.id = str(gm.get("id"), g.id);
                g.name = str(gm.get("name"), g.name);
                g.parentId = nullableStr(gm.get("parentId"));
                library.groups.add(g);
            }
        }
        Object wheelsObj = map.get("wheels");
        if (wheelsObj instanceof List<?> wheels) {
            for (Object wObj : wheels) if (wObj instanceof Map<?, ?> wm) {
                Wheel w = new Wheel();
                w.id = str(wm.get("id"), w.id);
                w.name = str(wm.get("name"), str(wm.get("title"), w.name));
                w.groupId = nullableStr(wm.get("groupId"));
                if (wm.get("settings") instanceof Map<?, ?> sm) readSettings(w.settings, sm);
                if (wm.get("options") instanceof List<?> opts) {
                    for (Object oObj : opts) if (oObj instanceof Map<?, ?> om) {
                        WheelOption opt = new WheelOption();
                        opt.id = str(om.get("id"), opt.id);
                        opt.text = str(om.get("text"), "");
                        opt.trueWeight = nonNegative(num(om.get("trueWeight"), 1.0), 1.0);
                        opt.fakeWeight = om.containsKey("fakeWeight") && om.get("fakeWeight") != null ? nonNegative(num(om.get("fakeWeight"), opt.trueWeight), opt.trueWeight) : null;
                        if(om.get("hidden") instanceof Boolean b)opt.hidden=b;if(opt.trueWeight==0||opt.displayWeight()==0)opt.hidden=true;
                        if (!opt.text.isBlank()) w.options.add(opt);
                    }
                }
                library.wheels.add(w);
            }
        }
        Object historyObj = map.get("history");
        if (historyObj instanceof List<?> history) {
            for (Object hObj : history) if (hObj instanceof Map<?, ?> hm) {
                String wheelId = nullableStr(hm.get("wheelId"));
                String optionText = str(hm.get("optionText"), "");
                if (wheelId == null || wheelId.trim().isEmpty() || optionText.trim().isEmpty()) continue;
                SpinHistoryEntry entry = new SpinHistoryEntry();
                entry.id = str(hm.get("id"), entry.id);
                entry.wheelId = wheelId;
                entry.wheelName = str(hm.get("wheelName"), "");
                entry.optionId = nullableStr(hm.get("optionId"));
                entry.optionText = optionText;
                entry.createdAt = longNum(hm.get("createdAt"), 0);
                library.history.add(entry);
            }
        }
        return library;
    }

    public static byte[] exportWwd(WheelLibrary library, boolean compressed) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "wwd");
        root.put("version", library.version);
        root.put("exportTime", System.currentTimeMillis());
        root.put("settings", appSettingsMap(library.settings));
        List<Object> groups = new ArrayList<>();
        for (WheelGroup g : library.groups) {
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("id", g.id); gm.put("name", g.name); gm.put("parentId", g.parentId);
            groups.add(gm);
        }
        root.put("groups", groups);
        List<Object> wheels = new ArrayList<>();
        for (Wheel w : library.wheels) wheels.add(wheelMap(w));
        root.put("wheels", wheels);
        List<Object> history = new ArrayList<>();
        for (SpinHistoryEntry entry : library.history) {
            Map<String, Object> hm = new LinkedHashMap<>();
            hm.put("id", entry.id); hm.put("wheelId", entry.wheelId); hm.put("wheelName", entry.wheelName);
            hm.put("optionId", entry.optionId); hm.put("optionText", entry.optionText); hm.put("createdAt", entry.createdAt);
            history.add(hm);
        }
        root.put("history", history);
        byte[] json = SimpleJson.stringify(root).getBytes(StandardCharsets.UTF_8);
        if (!compressed) return json;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[]{'w','w','d'});
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(json); }
        return bytes.toByteArray();
    }

    private static String decodeMaybeCompressed(byte[] raw, String magic) throws IOException {
        if (raw.length >= 4 && raw[0] == magic.charAt(0) && raw[1] == magic.charAt(1) && raw[2] == magic.charAt(2)) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw, 3, raw.length - 3))) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> wheelMap(Wheel w) {
        Map<String, Object> wm = new LinkedHashMap<>();
        wm.put("id", w.id); wm.put("name", w.name); wm.put("groupId", w.groupId); wm.put("settings", settingsMap(w.settings));
        List<Object> opts = new ArrayList<>();
        for (WheelOption o : w.options) {
            Map<String, Object> om = new LinkedHashMap<>();
            om.put("id", o.id); om.put("text", o.text); om.put("trueWeight", o.trueWeight); om.put("fakeWeight", o.fakeWeight);om.put("hidden",o.hidden);
            opts.add(om);
        }
        wm.put("options", opts);
        return wm;
    }

    private static Map<String, Object> settingsMap(WheelSettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rotationDurationMs", s.rotationDurationMs); m.put("colorScheme", s.colorScheme); m.put("fontSize", s.fontSize);
        m.put("tickSoundEnabled", s.tickSoundEnabled); m.put("selectedSoundEnabled", s.selectedSoundEnabled); m.put("ttsEnabled", s.ttsEnabled);
        return m;
    }

    private static Map<String, Object> appSettingsMap(AppSettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("defaultRotationDurationMs", s.defaultRotationDurationMs); m.put("defaultColorScheme", s.defaultColorScheme); m.put("defaultFontSize", s.defaultFontSize);
        m.put("tickSoundEnabled", s.tickSoundEnabled); m.put("selectedSoundEnabled", s.selectedSoundEnabled); m.put("ttsEnabled", s.ttsEnabled);
        return m;
    }

    private static void readSettings(WheelSettings s, Map<?, ?> m) {
        s.rotationDurationMs = (long) positive(num(m.get("rotationDurationMs"), s.rotationDurationMs), s.rotationDurationMs);
        s.colorScheme = str(m.get("colorScheme"), s.colorScheme);
        s.fontSize = (int) positive(num(m.get("fontSize"), s.fontSize), s.fontSize);
        if (m.get("tickSoundEnabled") instanceof Boolean b) s.tickSoundEnabled = b;
        if (m.get("selectedSoundEnabled") instanceof Boolean b) s.selectedSoundEnabled = b;
        if (m.get("ttsEnabled") instanceof Boolean b) s.ttsEnabled = b;
    }

    private static String str(Object v, String def) { return v == null ? def : String.valueOf(v); }
    private static String nullableStr(Object v) { return v == null ? null : String.valueOf(v); }
    private static double num(Object v, double def) { return v instanceof Number n ? n.doubleValue() : def; }
    private static long longNum(Object v, long def) { return v instanceof Number n ? n.longValue() : def; }
    private static double nonNegative(double v,double def){return Double.isFinite(v)&&v>=0?v:def;}
    private static double positive(double v, double def) { return v > 0 ? v : def; }
}

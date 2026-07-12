package com.wheel.app.tts;

import java.io.IOException;
import java.util.Locale;

public final class TtsService {
    public boolean isAvailable() {
        return command("say") || command("spd-say") || command("espeak") || command("powershell");
    }

    public void speak(String text) {
        if (text == null || text.isBlank()) return;
        new Thread(() -> {
            try {
                ProcessBuilder pb = processFor(text);
                if (pb != null) pb.start();
            } catch (IOException ignored) {
            }
        }, "wheel-tts").start();
    }

    private ProcessBuilder processFor(String text) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") && command("say")) return new ProcessBuilder("say", text);
        if (os.contains("win") && command("powershell")) {
            String script = "Add-Type -AssemblyName System.Speech; " +
                    "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                    "$s.Speak($args[0])";
            return new ProcessBuilder("powershell", "-NoProfile", "-Command", script, text);
        }
        if (command("spd-say")) return new ProcessBuilder("spd-say", text);
        if (command("espeak")) return new ProcessBuilder("espeak", text);
        if (command("say")) return new ProcessBuilder("say", text);
        return null;
    }

    private boolean command(String name) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        String sep = System.getProperty("path.separator");
        for (String dir : path.split(java.util.regex.Pattern.quote(sep))) {
            if (java.nio.file.Files.isExecutable(java.nio.file.Path.of(dir, name))) return true;
            if (java.nio.file.Files.isExecutable(java.nio.file.Path.of(dir, name + ".exe"))) return true;
        }
        return false;
    }
}

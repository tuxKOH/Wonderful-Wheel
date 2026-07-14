package com.wheel.app.android;

import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Resolves wheel, app, then system TTS language consistently. */
final class TtsLanguageResolver {
    interface LanguageSetter { int set(Locale locale); }

    private TtsLanguageResolver() {}

    static Locale apply(String wheelTag, String appTag, Locale system, LanguageSetter setter) {
        for (Locale locale : candidates(wheelTag, appTag, system)) {
            int support = setter.set(locale);
            if (support != TextToSpeech.LANG_MISSING_DATA
                    && support != TextToSpeech.LANG_NOT_SUPPORTED) return locale;
        }
        return null;
    }

    static List<Locale> candidates(String wheelTag, String appTag, Locale system) {
        ArrayList<Locale> result = new ArrayList<>();
        add(result, wheelTag);
        add(result, appTag);
        Locale fallback = system == null ? Locale.getDefault() : system;
        if (!contains(result, fallback)) result.add(fallback);
        return result;
    }

    private static void add(List<Locale> result, String tag) {
        if (tag == null || tag.trim().isEmpty()) return;
        String candidate = tag.trim();
        while (!candidate.isEmpty()) {
            Locale locale = Locale.forLanguageTag(candidate);
            if (!locale.getLanguage().isEmpty() && !contains(result, locale)) result.add(locale);
            int separator = candidate.lastIndexOf('-');
            if (separator < 0) break;
            candidate = candidate.substring(0, separator);
        }
    }

    private static boolean contains(List<Locale> locales, Locale candidate) {
        String tag = candidate.toLanguageTag();
        for (Locale locale : locales) if (locale.toLanguageTag().equalsIgnoreCase(tag)) return true;
        return false;
    }
}

package com.wheel.app.android;

import static org.junit.Assert.*;

import android.speech.tts.TextToSpeech;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TtsLanguageResolverTest {
    @Test public void triesWheelThenAppThenSystem() {
        List<String> tried=new ArrayList<>();
        Locale actual=TtsLanguageResolver.apply("ja-JP","zh-CN",Locale.US,locale->{tried.add(locale.toLanguageTag());return tried.size()<3?TextToSpeech.LANG_NOT_SUPPORTED:TextToSpeech.LANG_AVAILABLE;});
        assertEquals("zh-CN",actual.toLanguageTag());
        assertEquals(List.of("ja-JP","ja","zh-CN"),tried);
    }
    @Test public void skipsBlankInvalidAndDuplicateTags() {
        List<Locale> locales=TtsLanguageResolver.candidates("  ","en-US",Locale.US);
        assertEquals(2,locales.size());
        assertEquals("en-US",locales.get(0).toLanguageTag());
        assertEquals("en",locales.get(1).toLanguageTag());
    }
    @Test public void returnsNullWhenEveryLanguageIsUnsupported() {
        assertNull(TtsLanguageResolver.apply("ja","zh",Locale.US,locale->TextToSpeech.LANG_MISSING_DATA));
    }
}

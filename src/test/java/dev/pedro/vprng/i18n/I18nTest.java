package dev.pedro.vprng.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pedro.vprng.config.VprngConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class I18nTest {
    private static final Pattern SPEC = Pattern.compile("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]");
    private static final Pattern KEY_USE = Pattern.compile("I18n\\.tr\\(\\s*\"([^\"]+)\"");

    @AfterEach
    void restoreLanguage() {
        VprngConfig.get().language = "en";
    }

    private static List<String> specs(String text) {
        List<String> found = new ArrayList<>();
        Matcher m = SPEC.matcher(text.replace("%%", ""));
        while (m.find()) found.add(m.group());
        return found;
    }

    @Test
    void allLanguagesHaveExactlyTheSameKeys() {
        Set<String> en = new TreeSet<>(I18n.bundle(I18n.Lang.EN).keySet());
        assertFalse(en.isEmpty(), "English bundle must load");
        assertEquals(en, new TreeSet<>(I18n.bundle(I18n.Lang.PT).keySet()), "pt keys differ from en");
        assertEquals(en, new TreeSet<>(I18n.bundle(I18n.Lang.ES).keySet()), "es keys differ from en");
    }

    @Test
    void translationsKeepTheSameFormatSpecifiersAsEnglish() {
        Map<String, String> en = I18n.bundle(I18n.Lang.EN);
        for (I18n.Lang lang : new I18n.Lang[] {I18n.Lang.PT, I18n.Lang.ES}) {
            for (Map.Entry<String, String> e : en.entrySet()) {
                String translated = I18n.bundle(lang).get(e.getKey());
                assertEquals(specs(e.getValue()), specs(translated),
                        lang.code + ":" + e.getKey() + " must use the same %-specifiers as English");
            }
        }
    }

    @Test
    void noTranslationIsBlank() {
        for (I18n.Lang lang : I18n.Lang.values()) {
            I18n.bundle(lang).forEach((key, text) -> assertFalse(text.isBlank(), lang.code + ":" + key + " is blank"));
        }
    }

    @Test
    void everyKeyUsedInTheCodeExistsInEnglish() throws IOException {
        Set<String> known = I18n.bundle(I18n.Lang.EN).keySet();
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = KEY_USE.matcher(Files.readString(file));
                while (m.find()) {
                    if (!known.contains(m.group(1))) missing.add(file.getFileName() + ": " + m.group(1));
                }
            }
        }
        assertTrue(missing.isEmpty(), "keys used in code but missing from en.json: " + missing);
    }

    @Test
    void englishIsTheDefaultAndOthersCanBeSelected() {
        assertEquals(I18n.Lang.EN, I18n.current());
        assertEquals("Idle", I18n.tr("state.idle"));
        VprngConfig.get().language = "pt";
        assertEquals(I18n.Lang.PT, I18n.current());
        assertEquals("Parado", I18n.tr("state.idle"));
        VprngConfig.get().language = "es";
        assertEquals("Detenido", I18n.tr("state.idle"));
    }

    @Test
    void unknownKeyFallsBackToTheKeyAndMissingTranslationToEnglish() {
        assertEquals("no.such.key", I18n.tr("no.such.key"));
    }

    @Test
    void formatsArguments() {
        assertEquals("Added 5,63 (3 cells).".replace("5,63", "1, 2, 3"), I18n.tr("act.added", "1, 2, 3", 3));
        VprngConfig.get().language = "pt";
        assertEquals("1/2 válidos", I18n.tr("win.valid", 1, 2));
    }

    @Test
    void gameLanguageCodesMapToSupportedLanguages() {
        assertEquals(I18n.Lang.PT, I18n.fromGameCode("pt_br"));
        assertEquals(I18n.Lang.PT, I18n.fromGameCode("pt_PT"));
        assertEquals(I18n.Lang.ES, I18n.fromGameCode("es_mx"));
        assertEquals(I18n.Lang.ES, I18n.fromGameCode("es_es"));
        assertEquals(I18n.Lang.EN, I18n.fromGameCode("en_us"));
        assertEquals(I18n.Lang.EN, I18n.fromGameCode("fr_fr"));
        assertEquals(I18n.Lang.EN, I18n.fromGameCode(null));
    }

    @Test
    void settingCyclesThroughEveryChoiceAndValidates() {
        assertEquals("en", I18n.nextSetting("auto"));
        assertEquals("pt", I18n.nextSetting("en"));
        assertEquals("es", I18n.nextSetting("pt"));
        assertEquals("auto", I18n.nextSetting("es"));
        assertEquals("auto", I18n.nextSetting("garbage"));
        assertTrue(I18n.validSetting("auto"));
        assertTrue(I18n.validSetting("pt"));
        assertFalse(I18n.validSetting("fr"));
    }
}

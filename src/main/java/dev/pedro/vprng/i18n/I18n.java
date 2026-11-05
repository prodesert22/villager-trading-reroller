package dev.pedro.vprng.i18n;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.pedro.vprng.config.VprngConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.IllegalFormatException;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;

/**
 * The mod's own translations (English, Portuguese, Spanish), independent of the game's language so the player can
 * pick a language for this mod only. Texts live in {@code assets/villager-trading-reroller/i18n/<code>.json} and are
 * formatted with {@link String#format}. A missing key falls back to English, then to the key itself.
 *
 * <p>The setting {@code language} is {@code en} (default), {@code pt}, {@code es} or {@code auto}, which follows the
 * game's language (anything other than Portuguese or Spanish becomes English).</p>
 */
public final class I18n {
    public static final String AUTO = "auto";
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    private static final Gson GSON = new Gson();
    private static final Map<Lang, Map<String, String>> BUNDLES = new EnumMap<>(Lang.class);

    public enum Lang {
        EN("en", "English"), PT("pt", "Português"), ES("es", "Español");

        public final String code;
        public final String nativeName;

        Lang(String code, String nativeName) {
            this.code = code;
            this.nativeName = nativeName;
        }

        public static Lang byCode(String code) {
            for (Lang lang : values()) {
                if (lang.code.equals(code)) return lang;
            }
            return null;
        }
    }

    private I18n() {}

    /** The language in effect right now (resolves {@code auto} against the game's language). */
    public static Lang current() {
        String setting = VprngConfig.get().language;
        if (AUTO.equals(setting)) return fromGame();
        Lang lang = Lang.byCode(setting);
        return lang == null ? Lang.EN : lang;
    }

    /** Maps the game's language code (for example {@code pt_br}, {@code es_mx}) onto a supported language. */
    public static Lang fromGameCode(String code) {
        if (code == null) return Lang.EN;
        String lower = code.toLowerCase(Locale.ROOT);
        if (lower.startsWith("pt")) return Lang.PT;
        if (lower.startsWith("es")) return Lang.ES;
        return Lang.EN;
    }

    private static Lang fromGame() {
        try {
            return fromGameCode(Minecraft.getInstance().options.languageCode);
        } catch (RuntimeException | LinkageError e) {
            return Lang.EN;
        }
    }

    /** Next choice when cycling the setting: auto → en → pt → es → auto. */
    public static String nextSetting(String setting) {
        if (AUTO.equals(setting)) return Lang.EN.code;
        Lang lang = Lang.byCode(setting);
        if (lang == null) return AUTO;
        Lang[] all = Lang.values();
        return lang.ordinal() + 1 < all.length ? all[lang.ordinal() + 1].code : AUTO;
    }

    public static boolean validSetting(String setting) {
        return AUTO.equals(setting) || Lang.byCode(setting) != null;
    }

    /** Translates {@code key}, formatting it with {@code args} when given. */
    public static String tr(String key, Object... args) {
        String text = bundle(current()).get(key);
        if (text == null) text = bundle(Lang.EN).get(key);
        if (text == null) return key;
        if (args.length == 0) return text;
        try {
            return String.format(Locale.ROOT, text, args);
        } catch (IllegalFormatException e) {
            return text;
        }
    }

    /** All keys of a language (used by the consistency tests). */
    public static Map<String, String> bundle(Lang lang) {
        return BUNDLES.computeIfAbsent(lang, I18n::load);
    }

    private static Map<String, String> load(Lang lang) {
        String path = "/assets/villager-trading-reroller/i18n/" + lang.code + ".json";
        try (InputStream in = I18n.class.getResourceAsStream(path)) {
            if (in == null) return Map.of();
            Map<String, String> map = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), MAP_TYPE);
            return map == null ? Map.of() : map;
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }
}

package net.mcless.dev.onlinechat.i18n;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mcless.dev.onlinechat.OnlineChat;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side translation of every in-game text this mod emits.
 * <p>
 * The mod supports pure server-side deployment, so vanilla clients have no copy of our language files
 * and cannot resolve translation keys. Instead the server picks one language ({@code language} in
 * {@code onlinechat-server.toml}), renders the text itself and sends plain literal components. The
 * dictionaries live in {@code assets/onlinechat/lang/<code>.json}; {@code en_us} is always loaded as
 * a fallback for keys missing from the selected language.
 */
public final class Lang {
    private static final String FALLBACK = "en_us";

    private static volatile Map<String, String> dict = Map.of();
    private static volatile Map<String, String> fallback = Map.of();
    private static volatile String current = FALLBACK;

    private Lang() {}

    /** (Re)loads the dictionaries. Safe to call again on {@code /onlinechat reload}. */
    public static void load(String languageCode) {
        String code = normalize(languageCode);
        Map<String, String> fb = read(FALLBACK);
        Map<String, String> main = code.equals(FALLBACK) ? fb : read(code);
        if (main.isEmpty() && !code.equals(FALLBACK)) {
            OnlineChat.LOGGER.warn("[OnlineChat] Language '{}' not found in assets/onlinechat/lang; falling back to {}", code, FALLBACK);
            main = fb;
            code = FALLBACK;
        }
        fallback = fb;
        dict = main;
        current = code;
        OnlineChat.LOGGER.info("[OnlineChat] In-game language: {} ({} keys)", code, main.size());
    }

    public static String current() { return current; }

    /** Translates {@code key}, formatting positional {@code %s} placeholders with {@code args}. */
    public static String tr(String key, Object... args) {
        String pattern = raw(key);
        if (args == null || args.length == 0) return pattern;
        try {
            return String.format(pattern, args);
        } catch (RuntimeException e) {
            return pattern;
        }
    }

    /** {@link #tr} wrapped in a literal component. */
    public static MutableComponent text(String key, Object... args) {
        return Component.literal(tr(key, args));
    }

    /**
     * Builds a component from a pattern whose {@code %s} placeholders are replaced by the given
     * (already styled) components, so individual arguments can keep their own colour.
     */
    public static MutableComponent component(String key, Component... args) {
        String pattern = raw(key);
        MutableComponent out = Component.empty();
        int argIdx = 0;
        int last = 0;
        int idx;
        while ((idx = pattern.indexOf("%s", last)) >= 0) {
            if (idx > last) out.append(Component.literal(pattern.substring(last, idx)));
            if (argIdx < args.length) out.append(args[argIdx++]);
            last = idx + 2;
        }
        if (last < pattern.length()) out.append(Component.literal(pattern.substring(last)));
        return out;
    }

    /** Same as {@link #component(String, Component...)} but applies {@code style} to the literal parts. */
    public static MutableComponent component(String key, Style style, Component... args) {
        String pattern = raw(key);
        MutableComponent out = Component.empty();
        int argIdx = 0;
        int last = 0;
        int idx;
        while ((idx = pattern.indexOf("%s", last)) >= 0) {
            if (idx > last) out.append(Component.literal(pattern.substring(last, idx)).withStyle(style));
            if (argIdx < args.length) out.append(args[argIdx++]);
            last = idx + 2;
        }
        if (last < pattern.length()) out.append(Component.literal(pattern.substring(last)).withStyle(style));
        return out;
    }

    private static String raw(String key) {
        String s = dict.get(key);
        if (s == null) s = fallback.get(key);
        return s == null ? key : s;
    }

    private static String normalize(String code) {
        if (code == null || code.isBlank()) return FALLBACK;
        return code.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static Map<String, String> read(String code) {
        String resource = "/assets/onlinechat/lang/" + code + ".json";
        try (InputStream in = Lang.class.getResourceAsStream(resource)) {
            if (in == null) return Map.of();
            JsonObject obj = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> m = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                if (e.getValue().isJsonPrimitive()) m.put(e.getKey(), e.getValue().getAsString());
            }
            return m;
        } catch (Exception e) {
            OnlineChat.LOGGER.error("[OnlineChat] Failed to read language file {}", resource, e);
            return Map.of();
        }
    }
}

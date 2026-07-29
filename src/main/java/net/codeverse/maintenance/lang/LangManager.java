package net.codeverse.maintenance.lang;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Player facing text, in English and German at exact key parity.
 *
 * Bundled files are written out on first start and merged forward on upgrade,
 * so an operator's edits survive a release that adds a message. Every string is
 * MiniMessage, so colour and hover live in the file rather than in code.
 */
public final class LangManager {

    private static final Gson GSON = new Gson();
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Map<String, Map<String, String>> locales = new LinkedHashMap<>();
    private final List<String> degraded = new ArrayList<>();
    private final String defaultLocale;
    private final boolean usePlayerLocale;

    public LangManager(Path dataDirectory, String defaultLocale, boolean usePlayerLocale, List<String> bundled)
            throws IOException {
        this.defaultLocale = defaultLocale;
        this.usePlayerLocale = usePlayerLocale;

        Path langDirectory = dataDirectory.resolve("lang");
        Files.createDirectories(langDirectory);

        for (String locale : bundled) {
            try {
                locales.put(locale, loadLocale(langDirectory, locale));
            } catch (RuntimeException corrupt) {
                // An operator's edit that no longer parses must not stop the
                // plugin starting, because the plugin is what keeps the network
                // closed. The bundled text is used instead and the file is left
                // alone so the edit can be recovered. The caller reports it.
                locales.put(locale, flattenBundled(locale));
                degraded.add(locale);
            }
        }
        if (!locales.containsKey(defaultLocale)) {
            throw new IllegalStateException("The default locale '" + defaultLocale + "' is not bundled. "
                    + "Available: " + locales.keySet());
        }
    }

    private Map<String, String> loadLocale(Path langDirectory, String locale) throws IOException {
        JsonObject bundled = readBundled(locale);
        Path file = langDirectory.resolve(locale + ".json");

        JsonObject effective;
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                effective = merge(bundled, JsonParser.parseReader(reader).getAsJsonObject());
            }
        } else {
            effective = bundled;
        }
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(effective, writer);
        }

        Map<String, String> flattened = new LinkedHashMap<>();
        flatten("", effective, flattened);
        return flattened;
    }

    /** The bundled text for a locale, ignoring anything on disk. */
    private Map<String, String> flattenBundled(String locale) throws IOException {
        Map<String, String> flattened = new LinkedHashMap<>();
        flatten("", readBundled(locale), flattened);
        return flattened;
    }

    private JsonObject readBundled(String locale) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/lang/" + locale + ".json")) {
            if (in == null) {
                throw new IOException("No bundled language file for locale " + locale);
            }
            try (Reader reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    private static JsonObject merge(JsonObject defaults, JsonObject onDisk) {
        JsonObject result = new JsonObject();
        for (String key : defaults.keySet()) {
            JsonElement fallback = defaults.get(key);
            if (onDisk.has(key) && fallback.isJsonObject() && onDisk.get(key).isJsonObject()) {
                result.add(key, merge(fallback.getAsJsonObject(), onDisk.getAsJsonObject(key)));
            } else if (onDisk.has(key)) {
                result.add(key, onDisk.get(key));
            } else {
                result.add(key, fallback);
            }
        }
        return result;
    }

    private static void flatten(String prefix, JsonObject object, Map<String, String> into) {
        for (String key : object.keySet()) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            JsonElement value = object.get(key);
            if (value.isJsonObject()) {
                flatten(path, value.getAsJsonObject(), into);
            } else if (value.isJsonArray()) {
                List<String> lines = new ArrayList<>();
                value.getAsJsonArray().forEach(element -> lines.add(element.getAsString()));
                into.put(path, String.join("\n", lines));
            } else {
                into.put(path, value.getAsString());
            }
        }
    }

    private String raw(String key, Locale locale) {
        String tag = resolveLocale(locale);
        String value = locales.getOrDefault(tag, Map.of()).get(key);
        if (value == null) {
            value = locales.get(defaultLocale).get(key);
        }
        // A missing key renders as the key rather than as nothing, so the gap
        // is visible in game instead of producing a blank message nobody can
        // report usefully.
        return value == null ? key : value;
    }

    private String resolveLocale(Locale locale) {
        if (!usePlayerLocale || locale == null) {
            return defaultLocale;
        }
        String language = locale.getLanguage();
        return locales.containsKey(language) ? language : defaultLocale;
    }

    /** A rendered message. Placeholders are supplied as alternating name and value. */
    public Component get(String key, Locale locale, String... placeholders) {
        if (placeholders.length % 2 != 0) {
            throw new IllegalArgumentException("placeholders must be name and value pairs");
        }
        TagResolver.Builder resolvers = TagResolver.builder();
        for (int i = 0; i < placeholders.length; i += 2) {
            resolvers.resolver(Placeholder.unparsed(placeholders[i], placeholders[i + 1]));
        }
        return MINI.deserialize(raw(key, locale), resolvers.build());
    }

    public Component get(String key, String... placeholders) {
        return get(key, null, placeholders);
    }

    /** Locales whose file could not be read, so bundled text is in use. */
    public List<String> degradedLocales() {
        return List.copyOf(degraded);
    }

    public List<String> availableLocales() {
        return List.copyOf(locales.keySet());
    }

    /** Every key in a locale, for the parity test. */
    public Map<String, String> keys(String locale) {
        return Map.copyOf(locales.getOrDefault(locale, Map.of()));
    }
}

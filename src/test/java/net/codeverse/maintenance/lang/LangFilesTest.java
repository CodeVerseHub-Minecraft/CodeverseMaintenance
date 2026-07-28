package net.codeverse.maintenance.lang;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangFilesTest {

    private static JsonObject read(String locale) throws Exception {
        try (InputStream in = LangFilesTest.class.getResourceAsStream("/lang/" + locale + ".json")) {
            assertNotNull(in, "missing bundled language file for " + locale);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static void collect(String prefix, JsonObject object, Set<String> into) {
        for (String key : object.keySet()) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            JsonElement value = object.get(key);
            if (value.isJsonObject()) {
                collect(path, value.getAsJsonObject(), into);
            } else {
                into.add(path);
            }
        }
    }

    private static void eachString(JsonElement element, java.util.function.Consumer<String> consumer) {
        if (element.isJsonObject()) {
            element.getAsJsonObject().entrySet().forEach(e -> eachString(e.getValue(), consumer));
        } else if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(e -> eachString(e, consumer));
        } else {
            consumer.accept(element.getAsString());
        }
    }

    @Test
    void everyLocaleHasExactlyTheSameKeys() throws Exception {
        Set<String> english = new LinkedHashSet<>();
        Set<String> german = new LinkedHashSet<>();
        collect("", read("en"), english);
        collect("", read("de"), german);
        assertEquals(english, german, "a message present in one language and missing in the other "
                + "renders as its key to whoever is unlucky");
    }

    @Test
    void everyStringParsesAsMiniMessage() throws Exception {
        MiniMessage mini = MiniMessage.miniMessage();
        for (String locale : List.of("en", "de")) {
            eachString(read(locale), value -> mini.deserialize(value));
        }
    }

    /**
     * German umlauts are the one place non ASCII belongs, and stripping them
     * would leave the German messages subtly wrong rather than obviously so.
     */
    @Test
    void germanKeepsItsUmlautsAndEnglishStaysAscii() throws Exception {
        StringBuilder english = new StringBuilder();
        eachString(read("en"), english::append);
        assertTrue(english.chars().allMatch(c -> c < 128), "English should not need non ASCII");

        StringBuilder german = new StringBuilder();
        eachString(read("de"), german::append);
        assertTrue(german.chars().anyMatch(c -> c > 127), "German should keep its umlauts");
    }
}

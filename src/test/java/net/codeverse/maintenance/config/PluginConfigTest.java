package net.codeverse.maintenance.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginConfigTest {

    private static final UUID SAM = UUID.fromString("bf8ccf0d-901d-4cdd-88b6-6af144428974");

    /**
     * Mojang's API returns uuids as thirty two characters with no hyphens, so
     * that is the form anybody copying one will paste. Refusing it meant the
     * most natural thing an operator could do stopped the plugin starting, and
     * because this list is read during startup it took the gate down with it.
     */
    @Test
    void aUuidIsAcceptedInTheFormMojangActuallyReturns() {
        assertEquals(Optional.of(SAM), PluginConfig.parseUuid("bf8ccf0d901d4cdd88b66af144428974"));
        assertEquals(Optional.of(SAM), PluginConfig.parseUuid("bf8ccf0d-901d-4cdd-88b6-6af144428974"));
        assertEquals(Optional.of(SAM), PluginConfig.parseUuid("  bf8ccf0d901d4cdd88b66af144428974  "));
    }

    @Test
    void somethingThatIsNotAUuidIsStillRefused() {
        assertTrue(PluginConfig.parseUuid("not-a-uuid").isEmpty());
        assertTrue(PluginConfig.parseUuid("bf8ccf0d901d4cdd88b66af1444289").isEmpty());
        assertTrue(PluginConfig.parseUuid("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz").isEmpty());
        assertTrue(PluginConfig.parseUuid(null).isEmpty());
    }

    @Test
    void bothFormsSurviveIntoTheParsedList() {
        PluginConfig config = new PluginConfig();
        config.gate.breakGlassUuids = List.of(
                "bf8ccf0d901d4cdd88b66af144428974",
                "bf8ccf0d-901d-4cdd-88b6-6af144428974");
        config.validate();
        assertEquals(List.of(SAM, SAM), config.breakGlass());
    }

    @Test
    void aMalformedEntryStillRefusesToStart() {
        PluginConfig config = new PluginConfig();
        config.gate.breakGlassUuids = List.of("clearly-not-a-uuid");
        assertThrows(IllegalStateException.class, config::validate);
    }
}

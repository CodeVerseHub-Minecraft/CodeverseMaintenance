package net.codeverse.maintenance.state;

import net.codeverse.api.maintenance.MaintenanceMode;
import net.codeverse.api.maintenance.MaintenanceWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateStoreTest {

    private static MaintenanceWindow window(Instant start, Optional<Instant> end, Set<String> servers) {
        return new MaintenanceWindow(MaintenanceMode.MAINTENANCE, "migration", start, end,
                servers, Optional.empty());
    }

    @Test
    void aFreshDirectoryReadsAsOpen(@TempDir Path tmp) throws IOException {
        StateStore store = new StateStore(tmp);
        assertEquals(MaintenanceMode.OPEN, store.mode());
        assertTrue(store.current().isEmpty());
    }

    @Test
    void anOpenedWindowSurvivesARestart(@TempDir Path tmp) throws IOException {
        StateStore first = new StateStore(tmp);
        first.open(window(Instant.now(), Optional.empty(), Set.of()));

        // A second store over the same directory is what a proxy restart looks
        // like. The gate has to still be closed afterwards.
        StateStore second = new StateStore(tmp);
        assertEquals(MaintenanceMode.MAINTENANCE, second.mode());
        assertTrue(second.current().isPresent());
    }

    /**
     * The window is judged against the clock rather than a stored flag, so one
     * with a duration lifts itself on time even if the proxy was down when it
     * should have ended. A forgotten window is a network that stays dark.
     */
    @Test
    void aWindowWithAnExpiryLiftsItselfWithoutASweep(@TempDir Path tmp) throws IOException {
        StateStore store = new StateStore(tmp);
        Instant past = Instant.now().minus(Duration.ofHours(2));
        store.open(window(past, Optional.of(past.plus(Duration.ofMinutes(30))), Set.of()));

        assertEquals(MaintenanceMode.OPEN, store.mode(), "the expiry passed while nothing was watching");
        assertTrue(store.current().isEmpty());
    }

    @Test
    void closingReopensTheNetwork(@TempDir Path tmp) throws IOException {
        StateStore store = new StateStore(tmp);
        store.open(window(Instant.now(), Optional.empty(), Set.of()));
        store.close();
        assertEquals(MaintenanceMode.OPEN, store.mode());
    }

    @Test
    void aPerServerWindowIsNotNetworkWide(@TempDir Path tmp) throws IOException {
        StateStore store = new StateStore(tmp);
        store.open(window(Instant.now(), Optional.empty(), Set.of("smp")));
        MaintenanceWindow active = store.current().orElseThrow();
        assertFalse(active.isNetworkWide());
        assertTrue(active.affects("smp"));
        assertFalse(active.affects("lobby"));
    }

    @Test
    void theAllowlistPersists(@TempDir Path tmp) throws IOException {
        UUID identity = UUID.randomUUID();
        StateStore first = new StateStore(tmp);
        assertTrue(first.allow(identity));
        assertFalse(first.allow(identity), "adding twice is not a change");

        StateStore second = new StateStore(tmp);
        assertTrue(second.allowed().contains(identity));
        assertTrue(second.disallow(identity));
        assertFalse(new StateStore(tmp).allowed().contains(identity));
    }

    /**
     * A corrupt state file must not silently read as open. Refusing to start
     * makes the problem visible; the alternative is a proxy that quietly stops
     * enforcing a window somebody is relying on.
     */
    @Test
    void anUnreadableStateFileRefusesToStart(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("maintenance.json"), "{ this is not json",
                StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> new StateStore(tmp));
    }

    @Test
    void aScheduledWindowIsReportedUntilItBegins(@TempDir Path tmp) throws IOException {
        StateStore store = new StateStore(tmp);
        store.schedule(MaintenanceMode.PRE_LAUNCH, "launch", Instant.now().plus(Duration.ofDays(3)), null);
        assertTrue(store.upcoming().isPresent());
        assertEquals(MaintenanceMode.PRE_LAUNCH, store.upcoming().orElseThrow().mode());

        store.clearSchedule();
        assertTrue(store.upcoming().isEmpty());
    }
}

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

    /**
     * Whether a window queues arrivals or refuses them has to survive a
     * restart with the window itself, or a proxy that restarts mid window
     * would silently change how everyone arriving is treated.
     */
    @Test
    void howArrivalsAreTreatedSurvivesARestart(@TempDir Path tmp) throws IOException {
        StateStore first = new StateStore(tmp);
        first.open(window(Instant.now(), Optional.empty(), Set.of()), false);
        assertFalse(first.holdPlayers());
        assertFalse(new StateStore(tmp).holdPlayers(), "a restart must not turn a lockout into a queue");

        first.open(window(Instant.now(), Optional.empty(), Set.of()), true);
        assertTrue(new StateStore(tmp).holdPlayers());
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

    /**
     * The test that was missing. upcoming reports only windows that have not
     * begun, which is what a consumer wants, so activation cannot use it: a
     * window would only be activated once it had started, and only reported
     * while it had not, and the two conditions never overlap. dueSchedule is
     * the complement, and the pair has to cover every moment between them.
     */
    @Test
    void aScheduleWhoseMomentHasPassedIsDueRatherThanUpcoming(@TempDir Path tmp) throws IOException {
        StateStore store = new StateStore(tmp);
        store.schedule(MaintenanceMode.MAINTENANCE, "overdue",
                Instant.now().minus(Duration.ofMinutes(1)), null);

        assertTrue(store.upcoming().isEmpty(), "it has begun, so it is no longer upcoming");
        assertTrue(store.dueSchedule().isPresent(), "and it is therefore due to activate");
        assertEquals("overdue", store.dueSchedule().orElseThrow().reason());
    }

    @Test
    void aFutureScheduleIsUpcomingRatherThanDue(@TempDir Path tmp) throws IOException {
        StateStore store = new StateStore(tmp);
        store.schedule(MaintenanceMode.MAINTENANCE, "later",
                Instant.now().plus(Duration.ofHours(1)), null);

        assertTrue(store.upcoming().isPresent());
        assertTrue(store.dueSchedule().isEmpty(), "not yet, so nothing to activate");
    }

    @Test
    void aClearedScheduleIsNeitherDueNorUpcoming(@TempDir Path tmp) throws IOException {
        StateStore store = new StateStore(tmp);
        store.schedule(MaintenanceMode.MAINTENANCE, "gone",
                Instant.now().minus(Duration.ofMinutes(1)), null);
        store.clearSchedule();
        assertTrue(store.upcoming().isEmpty());
        assertTrue(store.dueSchedule().isEmpty(), "a cleared schedule must not activate again");
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

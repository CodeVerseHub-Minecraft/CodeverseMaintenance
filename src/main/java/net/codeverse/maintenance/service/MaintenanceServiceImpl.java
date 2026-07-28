package net.codeverse.maintenance.service;

import net.codeverse.api.maintenance.MaintenanceMode;
import net.codeverse.api.maintenance.MaintenanceService;
import net.codeverse.api.maintenance.MaintenanceWindow;
import net.codeverse.maintenance.allow.Allowlist;
import net.codeverse.maintenance.state.StateStore;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * What other plugins see.
 *
 * The read methods are synchronous and cheap because the state they read is a
 * field, not a query: a minigame deciding whether to start a round should not
 * have to wait on anything, and asking on a tick has to be free. The write
 * methods are asynchronous because they persist to disk, announce to Discord
 * and move players.
 */
public final class MaintenanceServiceImpl implements MaintenanceService {

    private final StateStore state;
    private final Allowlist allowlist;
    private final Executor executor;
    private final Consumer<Transition> onChange;

    /** What changed, handed to the plugin so it can move players and announce. */
    public record Transition(MaintenanceMode previous, MaintenanceMode current,
                             Optional<MaintenanceWindow> window, UUID triggeredBy) {
    }

    public MaintenanceServiceImpl(StateStore state, Allowlist allowlist, Executor executor,
                                  Consumer<Transition> onChange) {
        this.state = state;
        this.allowlist = allowlist;
        this.executor = executor;
        this.onChange = onChange;
    }

    @Override
    public MaintenanceMode mode() {
        return state.mode();
    }

    @Override
    public boolean isActive() {
        return state.current().isPresent();
    }

    @Override
    public Optional<MaintenanceWindow> current() {
        return state.current();
    }

    @Override
    public Optional<MaintenanceWindow> upcoming() {
        return state.upcoming();
    }

    @Override
    public boolean isClosed(String server) {
        return state.current().map(window -> window.affects(server)).orElse(false);
    }

    @Override
    public boolean isAllowed(UUID internalId) {
        if (internalId == null) {
            return false;
        }
        return allowlist.isExplicitlyAllowed(internalId);
    }

    @Override
    public Optional<Duration> remaining() {
        return state.current().flatMap(window -> window.remainingAt(Instant.now()));
    }

    @Override
    public CompletableFuture<MaintenanceWindow> open(MaintenanceMode mode,
                                                     String reason,
                                                     Set<String> servers,
                                                     Optional<Duration> duration,
                                                     UUID triggeredBy) {
        if (mode == null || mode == MaintenanceMode.OPEN) {
            throw new IllegalArgumentException("open requires a closed mode; use close to reopen");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a reason is required, because players are shown it");
        }
        return CompletableFuture.supplyAsync(() -> {
            MaintenanceMode previous = state.mode();
            Instant now = Instant.now();
            MaintenanceWindow window = new MaintenanceWindow(
                    mode, reason, now,
                    duration.map(now::plus),
                    servers == null ? Set.of() : servers,
                    Optional.ofNullable(triggeredBy));
            try {
                state.open(window);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
            onChange.accept(new Transition(previous, mode, Optional.of(window), triggeredBy));
            return window;
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> close(Set<String> servers, UUID triggeredBy) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<MaintenanceWindow> active = state.current();
            if (active.isEmpty()) {
                return false;
            }
            MaintenanceMode previous = active.get().mode();
            try {
                state.close();
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
            onChange.accept(new Transition(previous, MaintenanceMode.OPEN, Optional.empty(), triggeredBy));
            return true;
        }, executor);
    }
}

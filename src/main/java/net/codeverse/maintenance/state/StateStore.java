package net.codeverse.maintenance.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.codeverse.api.maintenance.MaintenanceMode;
import net.codeverse.api.maintenance.MaintenanceWindow;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The maintenance state, held on disk and nowhere else.
 *
 * Deliberately not in the database and not in Redis. The situation that most
 * often calls for maintenance is one of those two being unavailable, and state
 * that cannot be read during an outage is state that cannot gate anything
 * during an outage. A local file has no dependencies, survives a restart, and
 * is readable when everything else is down.
 *
 * Writes go to a temporary file and are moved into place atomically, so a
 * proxy killed mid write leaves the previous state rather than a truncated file
 * that fails to parse and opens the network by accident.
 */
public final class StateStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private volatile Snapshot snapshot;

    public StateStore(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        this.file = dataDirectory.resolve("maintenance.json");
        this.snapshot = read();
    }

    /** The serialised shape. Kept separate from the API types so the file format can move independently. */
    static final class Snapshot {
        String mode = MaintenanceMode.OPEN.name();
        String reason = "";
        long startedAt = 0L;
        long endsAt = 0L;
        List<String> servers = new ArrayList<>();
        String triggeredBy = null;
        List<String> allowedInternalIds = new ArrayList<>();
        long scheduledStartAt = 0L;
        String scheduledMode = null;
        String scheduledReason = "";
        long scheduledEndsAt = 0L;
    }

    private Snapshot read() throws IOException {
        if (!Files.exists(file)) {
            return new Snapshot();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Snapshot loaded = GSON.fromJson(reader, Snapshot.class);
            return loaded == null ? new Snapshot() : loaded;
        } catch (RuntimeException unreadable) {
            // An unparseable state file must not silently open the network.
            // Refusing to start is the safe direction: an operator sees the
            // problem instead of discovering the gate was never applied.
            throw new IOException("maintenance.json could not be read. Refusing to start rather than "
                    + "assuming the network is open, which is what a corrupt state file would otherwise "
                    + "mean.", unreadable);
        }
    }

    private synchronized void persist() throws IOException {
        Path temp = Files.createTempFile(file.getParent(), ".maintenance-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(snapshot, writer);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * The current mode, judged against the clock.
     *
     * A window whose expiry has passed reads as open without waiting for a
     * sweep, so a window with a duration lifts itself on time even if the proxy
     * was down when it should have ended.
     */
    public MaintenanceMode mode() {
        return current().map(MaintenanceWindow::mode).orElse(MaintenanceMode.OPEN);
    }

    /** The active window, judged against the clock rather than a stored flag. */
    public Optional<MaintenanceWindow> current() {
        MaintenanceMode parsed = parseMode(snapshot.mode);
        if (parsed == MaintenanceMode.OPEN || snapshot.startedAt <= 0L) {
            return Optional.empty();
        }
        MaintenanceWindow window = toWindow(parsed, snapshot.reason, snapshot.startedAt, snapshot.endsAt,
                snapshot.servers, snapshot.triggeredBy);
        return window.isActiveAt(Instant.now()) ? Optional.of(window) : Optional.empty();
    }

    /** The next window that has not begun, empty when nothing is scheduled. */
    public Optional<MaintenanceWindow> upcoming() {
        if (snapshot.scheduledMode == null || snapshot.scheduledStartAt <= 0L) {
            return Optional.empty();
        }
        MaintenanceMode parsed = parseMode(snapshot.scheduledMode);
        if (parsed == MaintenanceMode.OPEN) {
            return Optional.empty();
        }
        MaintenanceWindow window = toWindow(parsed, snapshot.scheduledReason, snapshot.scheduledStartAt,
                snapshot.scheduledEndsAt, List.of(), null);
        return window.startsInAt(Instant.now()).isPresent() ? Optional.of(window) : Optional.empty();
    }

    public synchronized void open(MaintenanceWindow window) throws IOException {
        snapshot.mode = window.mode().name();
        snapshot.reason = window.reason();
        snapshot.startedAt = window.startedAt().toEpochMilli();
        snapshot.endsAt = window.endsAt().map(Instant::toEpochMilli).orElse(0L);
        snapshot.servers = new ArrayList<>(window.servers());
        snapshot.triggeredBy = window.triggeredBy().map(UUID::toString).orElse(null);
        persist();
    }

    public synchronized void close() throws IOException {
        snapshot.mode = MaintenanceMode.OPEN.name();
        snapshot.reason = "";
        snapshot.startedAt = 0L;
        snapshot.endsAt = 0L;
        snapshot.servers = new ArrayList<>();
        snapshot.triggeredBy = null;
        persist();
    }

    public synchronized void schedule(MaintenanceMode mode, String reason, Instant startAt, Instant endsAt)
            throws IOException {
        snapshot.scheduledMode = mode.name();
        snapshot.scheduledReason = reason;
        snapshot.scheduledStartAt = startAt.toEpochMilli();
        snapshot.scheduledEndsAt = endsAt == null ? 0L : endsAt.toEpochMilli();
        persist();
    }

    public synchronized void clearSchedule() throws IOException {
        snapshot.scheduledMode = null;
        snapshot.scheduledReason = "";
        snapshot.scheduledStartAt = 0L;
        snapshot.scheduledEndsAt = 0L;
        persist();
    }

    /** Identities explicitly allowed through while the network is closed. */
    public Set<UUID> allowed() {
        Set<UUID> parsed = new LinkedHashSet<>();
        for (String raw : snapshot.allowedInternalIds) {
            try {
                parsed.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                // A malformed entry is skipped rather than failing the read,
                // so one bad row cannot deny everyone including the operator.
            }
        }
        return parsed;
    }

    public synchronized boolean allow(UUID internalId) throws IOException {
        String value = internalId.toString();
        if (snapshot.allowedInternalIds.contains(value)) {
            return false;
        }
        snapshot.allowedInternalIds.add(value);
        persist();
        return true;
    }

    public synchronized boolean disallow(UUID internalId) throws IOException {
        if (!snapshot.allowedInternalIds.remove(internalId.toString())) {
            return false;
        }
        persist();
        return true;
    }

    private static MaintenanceMode parseMode(String raw) {
        if (raw == null) {
            return MaintenanceMode.OPEN;
        }
        try {
            return MaintenanceMode.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            // A mode written by a newer release degrades to open rather than
            // throwing, so a downgrade cannot leave the proxy unable to start.
            return MaintenanceMode.OPEN;
        }
    }

    private static MaintenanceWindow toWindow(MaintenanceMode mode, String reason, long startedAt,
                                              long endsAt, List<String> servers, String triggeredBy) {
        Optional<UUID> by = Optional.empty();
        if (triggeredBy != null) {
            try {
                by = Optional.of(UUID.fromString(triggeredBy));
            } catch (IllegalArgumentException ignored) {
                // Audit detail only; a malformed value is not worth a failure.
            }
        }
        return new MaintenanceWindow(
                mode,
                reason == null ? "" : reason,
                Instant.ofEpochMilli(startedAt),
                endsAt > 0L ? Optional.of(Instant.ofEpochMilli(endsAt)) : Optional.empty(),
                Set.copyOf(servers == null ? List.of() : servers),
                by);
    }
}

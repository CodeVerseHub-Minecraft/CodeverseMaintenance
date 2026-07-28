package net.codeverse.maintenance.motd;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which identity last connected from an address.
 *
 * A server list ping carries no username, only an address, so greeting someone
 * by name in the MOTD is only possible by remembering. This is the whole
 * mechanism: an address seen during a successful connection is associated with
 * the identity behind it, and a later ping from that address can be answered
 * personally.
 *
 * Held in memory only and forgotten on restart, which is a deliberate limit
 * rather than a missing feature. It is a nicety for returning players, not a
 * security input: nothing is ever granted on the strength of a remembered
 * address, because addresses are shared, reassigned and spoofable. The gate
 * asks the authentication plugin who someone is; this only decides how to
 * greet them.
 */
public final class IpMemory {

    private record Remembered(UUID internalId, String username, boolean allowed, Instant at) {
    }

    private final Map<String, Remembered> byAddress = new ConcurrentHashMap<>();
    private final Duration retention;
    private final boolean enabled;

    public IpMemory(boolean enabled, int retentionHours) {
        this.enabled = enabled;
        this.retention = Duration.ofHours(retentionHours);
    }

    public void remember(InetAddress address, UUID internalId, String username, boolean allowed) {
        if (!enabled || address == null) {
            return;
        }
        byAddress.put(address.getHostAddress(),
                new Remembered(internalId, username, allowed, Instant.now()));
    }

    /** The display name last seen from an address, empty when unknown or stale. */
    public Optional<String> usernameFor(InetAddress address) {
        return lookup(address).map(Remembered::username);
    }

    /** Whether the identity last seen from an address was allowed through. */
    public boolean wasAllowed(InetAddress address) {
        return lookup(address).map(Remembered::allowed).orElse(false);
    }

    private Optional<Remembered> lookup(InetAddress address) {
        if (!enabled || address == null) {
            return Optional.empty();
        }
        Remembered remembered = byAddress.get(address.getHostAddress());
        if (remembered == null) {
            return Optional.empty();
        }
        if (Duration.between(remembered.at(), Instant.now()).compareTo(retention) > 0) {
            byAddress.remove(address.getHostAddress());
            return Optional.empty();
        }
        return Optional.of(remembered);
    }

    /** Drops entries past their retention, so the map does not grow without bound. */
    public int sweep() {
        Instant cutoff = Instant.now().minus(retention);
        int before = byAddress.size();
        byAddress.entrySet().removeIf(entry -> entry.getValue().at().isBefore(cutoff));
        return before - byAddress.size();
    }

    public int size() {
        return byAddress.size();
    }
}

package net.codeverse.maintenance.allow;

import net.codeverse.api.CodeverseApiProvider;
import net.codeverse.api.identity.Identity;
import net.codeverse.api.identity.IdentityService;
import net.codeverse.maintenance.config.PluginConfig;
import net.codeverse.maintenance.state.StateStore;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides who may pass while the network is closed.
 *
 * Three mechanisms, checked in an order chosen so the cheapest and most
 * dependable comes first.
 *
 * The break glass list is raw premium uuids from config. It needs no database,
 * no authentication plugin and no permission system, because Mojang verified
 * those accounts during the login handshake. It exists because the situation
 * that most often calls for maintenance is a database migration, and an
 * allowlist that only works when the database is up is an allowlist that fails
 * exactly when it is needed.
 *
 * The explicit list is internal ids, so an allowance follows a person across
 * their linked Java and Bedrock accounts rather than applying to one of them.
 *
 * The permission is last, and deliberately cannot be relied on alone. The
 * authentication plugin bars CRACKED accounts from elevated permissions in
 * code, independent of any permission setup, so a member of staff who plays
 * cracked and has not linked Discord can never hold the node. For them the
 * explicit list is the only mechanism that can work.
 */
public final class Allowlist {

    private final PluginConfig config;
    private final StateStore state;
    private final List<UUID> breakGlass;

    public Allowlist(PluginConfig config, StateStore state) {
        this.config = config;
        this.state = state;
        this.breakGlass = config.breakGlass();
    }

    /**
     * Whether a connection whose Minecraft uuid is known may pass, without
     * resolving an identity.
     *
     * Only meaningful for accounts Mojang verified. A cracked connection's uuid
     * is derived from a name anyone could claim, so a match here would prove
     * nothing, which is why the caller must only consult this for premium
     * connections.
     */
    public boolean isBreakGlass(UUID minecraftId) {
        return breakGlass.contains(minecraftId);
    }

    public boolean hasBreakGlassEntries() {
        return !breakGlass.isEmpty();
    }

    /** Whether an identity is on the explicit list. */
    public boolean isExplicitlyAllowed(UUID internalId) {
        return state.allowed().contains(internalId);
    }

    /**
     * The full decision for a resolved identity.
     *
     * @param internalId     the person, or null when identity could not be resolved
     * @param minecraftId    the account, used for the break glass check
     * @param identityProven whether the platform verified this account, which is
     *                       true for premium and Bedrock and false for cracked
     * @param hasPermission  whether the connection holds the bypass permission
     */
    public boolean mayPass(UUID internalId, UUID minecraftId, boolean identityProven, boolean hasPermission) {
        if (identityProven && isBreakGlass(minecraftId)) {
            return true;
        }
        if (internalId != null && isExplicitlyAllowed(internalId)) {
            return true;
        }
        // Permission last, and only for an identity that was actually resolved.
        // Granting on permission alone for an unresolved connection would mean
        // trusting a name that nothing verified.
        return internalId != null && hasPermission;
    }

    /**
     * Resolves an identity through the shared API when it is available.
     *
     * Returns empty when the authentication plugin has not registered, which
     * happens when it is absent, still starting, or failed to start because the
     * database is down. That last case is not hypothetical: it is the same
     * outage that made maintenance necessary. Callers fall back to the break
     * glass list rather than treating an unresolvable identity as a denial of
     * everyone.
     */
    public Optional<Identity> resolve(UUID minecraftId) {
        Optional<IdentityService> identity = CodeverseApiProvider.find().flatMap(api -> api.identity());
        if (identity.isEmpty()) {
            return Optional.empty();
        }
        Optional<Identity> cached = identity.get().cachedByMinecraftId(minecraftId);
        if (cached.isPresent()) {
            return cached;
        }
        try {
            return identity.get().byMinecraftId(minecraftId).join();
        } catch (RuntimeException unavailable) {
            // A storage failure is not a denial. It means the question could not
            // be answered here, and the caller has a path that does not need it.
            return Optional.empty();
        }
    }

    /** Whether identity resolution is currently possible at all. */
    public boolean isIdentityAvailable() {
        return CodeverseApiProvider.find()
                .flatMap(api -> api.identity())
                .map(IdentityService::isLinkageAvailable)
                .orElse(false);
    }

    public String bypassPermission() {
        return config.gate.bypassPermission;
    }
}

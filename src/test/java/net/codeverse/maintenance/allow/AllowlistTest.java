package net.codeverse.maintenance.allow;

import net.codeverse.maintenance.config.PluginConfig;
import net.codeverse.maintenance.state.StateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who gets through a closed network.
 *
 * The property worth protecting is that an unproven identity cannot be talked
 * through. On a network that accepts cracked players, a username is a claim
 * anyone can make, so a gate that trusts one is a gate that anybody can walk
 * past by typing a staff member's name.
 */
class AllowlistTest {

    private static final UUID PREMIUM = UUID.randomUUID();
    private static final UUID STAFF_IDENTITY = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    private PluginConfig config;
    private StateStore state;
    private Allowlist allowlist;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws IOException {
        config = new PluginConfig();
        config.gate.breakGlassUuids = List.of(PREMIUM.toString());
        state = new StateStore(tmp);
        state.allow(STAFF_IDENTITY);
        allowlist = new Allowlist(config, state);
    }

    @Test
    void aBreakGlassAccountPassesWithoutAnyIdentityLookup() {
        // No internal id supplied, standing in for the database being down,
        // which is the outage that most often prompts maintenance in the first
        // place. This is the way back in.
        assertTrue(allowlist.mayPass(null, PREMIUM, true, false));
    }

    @Test
    void aBreakGlassUuidIsOnlyHonouredForAProvenConnection() {
        // A cracked connection's uuid is derived from a name anyone can claim,
        // so matching one proves nothing and must not open the gate.
        assertFalse(allowlist.mayPass(null, PREMIUM, false, false),
                "an unproven connection cannot use a break glass entry");
    }

    @Test
    void anExplicitlyAllowedIdentityPasses() {
        assertTrue(allowlist.mayPass(STAFF_IDENTITY, UUID.randomUUID(), false, false));
    }

    /**
     * The allowance is keyed on the internal id, so it follows the person
     * across their linked Java and Bedrock accounts rather than applying to
     * whichever one happened to be added.
     */
    @Test
    void anAllowanceFollowsTheIdentityNotTheAccount() {
        UUID javaAccount = UUID.randomUUID();
        UUID bedrockAccount = UUID.randomUUID();
        assertTrue(allowlist.mayPass(STAFF_IDENTITY, javaAccount, true, false));
        assertTrue(allowlist.mayPass(STAFF_IDENTITY, bedrockAccount, false, false));
    }

    @Test
    void aStrangerIsRefused() {
        assertFalse(allowlist.mayPass(STRANGER, UUID.randomUUID(), true, false));
        assertFalse(allowlist.mayPass(null, UUID.randomUUID(), true, false));
        assertFalse(allowlist.mayPass(null, UUID.randomUUID(), false, false));
    }

    /**
     * The permission is only honoured once an identity has actually been
     * resolved. Granting on a permission attached to an unresolved connection
     * would mean trusting a name nothing verified.
     */
    @Test
    void permissionAloneDoesNotOpenTheGateForAnUnresolvedConnection() {
        assertFalse(allowlist.mayPass(null, UUID.randomUUID(), false, true));
        assertTrue(allowlist.mayPass(STRANGER, UUID.randomUUID(), true, true));
    }

    @Test
    void breakGlassEntriesAreReportedSoStartupCanWarnWhenThereAreNone() {
        assertTrue(allowlist.hasBreakGlassEntries());
        PluginConfig empty = new PluginConfig();
        assertFalse(new Allowlist(empty, state).hasBreakGlassEntries());
    }
}

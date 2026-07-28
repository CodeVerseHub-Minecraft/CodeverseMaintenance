package net.codeverse.maintenance.gate;

import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.codeverse.api.identity.Identity;
import net.codeverse.api.maintenance.MaintenanceMode;
import net.codeverse.api.maintenance.MaintenanceWindow;
import net.codeverse.maintenance.allow.Allowlist;
import net.codeverse.maintenance.config.PluginConfig;
import net.codeverse.maintenance.lang.LangManager;
import net.codeverse.maintenance.state.StateStore;
import net.codeverse.maintenance.util.Durations;
import net.kyori.adventure.text.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides who gets in while the network is closed.
 *
 * Two gates, because one is not enough and the reason is the shape of this
 * network rather than a preference.
 *
 * At login, an identity is only trustworthy if something verified it. Mojang
 * verified a premium account and Floodgate verified a Bedrock one, so those can
 * be judged immediately. A cracked connection has proven nothing: the username
 * is a claim anyone can make, which is the entire reason the authentication
 * plugin exists. Denying a cracked connection here by name would be denying a
 * name, not a person, and allowing one would let anybody in by typing a staff
 * member's username.
 *
 * So cracked connections are let through to limbo, which is where they prove
 * who they are. The gate that actually holds is the transfer out of limbo,
 * by which point the internal id is known. That gate doubles as the mechanism
 * for holding players in the waiting room, since both are the same question:
 * may this person leave limbo right now.
 */
public final class MaintenanceGate {

    private final ProxyServer proxy;
    private final PluginConfig config;
    private final StateStore state;
    private final Allowlist allowlist;
    private final LangManager lang;
    private final HoldManager holds;

    public MaintenanceGate(ProxyServer proxy, PluginConfig config, StateStore state,
                           Allowlist allowlist, LangManager lang, HoldManager holds) {
        this.proxy = proxy;
        this.config = config;
        this.state = state;
        this.allowlist = allowlist;
        this.lang = lang;
        this.holds = holds;
    }

    /**
     * The first gate. Runs late so the authentication plugin has already
     * decided whether this connection is premium, because that decision is what
     * makes the uuid worth checking at all.
     */
    // Negative priority so this runs after the authentication plugin.
    // Higher runs earlier in Velocity 4, and the identity has to be settled
    // before it is worth judging.
    @Subscribe(priority = -100)
    public void onLogin(LoginEvent event) {
        Optional<MaintenanceWindow> window = state.current();
        if (window.isEmpty() || !window.get().isNetworkWide()) {
            // A per server window does not gate the network, only transfers.
            return;
        }

        if (state.holdPlayers()) {
            // The window queues arrivals rather than refusing them, so nobody
            // is turned away here. Everyone reaches limbo and waits there, and
            // the transfer gate decides who may leave it. Refusing at login
            // would defeat the waiting room entirely.
            return;
        }

        Player player = event.getPlayer();
        // Velocity reports whether the connection completed encryption, which
        // only happens for an account Mojang verified. Floodgate connections
        // arrive already verified and carry the configured prefix.
        boolean proven = player.isOnlineMode();

        if (!proven) {
            // Cannot be judged yet. Let them reach limbo and prove it.
            return;
        }

        UUID minecraftId = player.getUniqueId();
        if (allowlist.isBreakGlass(minecraftId)) {
            return;
        }

        Optional<Identity> identity = allowlist.resolve(minecraftId);
        UUID internalId = identity.map(Identity::internalId).orElse(null);
        boolean permitted = allowlist.mayPass(internalId, minecraftId, true,
                player.hasPermission(allowlist.bypassPermission()));
        if (permitted) {
            return;
        }

        event.setResult(ResultedEvent.ComponentResult.denied(
                closedMessage(window.get(), player.getEffectiveLocale())));
    }

    /**
     * The gate that actually holds.
     *
     * Every transfer passes through here, including the one the authentication
     * plugin performs after a successful sign in, which is what makes this the
     * right place: the internal id is known by now even for a cracked player.
     */
    // Negative priority so this runs after the authentication plugin.
    // Higher runs earlier in Velocity 4, and the identity has to be settled
    // before it is worth judging.
    @Subscribe(priority = -100)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Optional<RegisteredServer> target = event.getResult().getServer();
        if (target.isEmpty()) {
            return;
        }
        String targetName = target.get().getServerInfo().getName();

        // Limbo is never gated. It is where people prove who they are, and a
        // closed limbo means nobody can ever satisfy the allowlist, including
        // whoever closed the network.
        if (targetName.equals(config.gate.limboServer)) {
            return;
        }

        Optional<MaintenanceWindow> window = state.current();
        if (window.isEmpty() || !window.get().affects(targetName)) {
            return;
        }

        Player player = event.getPlayer();
        if (mayPass(player)) {
            return;
        }

        MaintenanceWindow active = window.get();
        if (active.isNetworkWide()) {
            if (!state.holdPlayers()) {
                // A window that refuses rather than queues. They are already
                // connected, so the honest thing is to say why and disconnect
                // rather than leave them somewhere they cannot act.
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                player.disconnect(closedMessage(active, player.getEffectiveLocale()));
                return;
            }
            // Held rather than disconnected: they are already connected, and
            // sending them to the waiting room means they are let in
            // automatically rather than having to watch for the reopening.
            Optional<RegisteredServer> limbo = proxy.getServer(config.gate.limboServer);
            if (limbo.isPresent()) {
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(limbo.get()));
                holds.hold(player);
                player.sendMessage(lang.get("gate.held", player.getEffectiveLocale()));
                return;
            }
            // No limbo configured or registered. Refusing the transfer is the
            // only honest option left; the player keeps their current server.
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.sendMessage(closedMessage(active, player.getEffectiveLocale()));
            return;
        }

        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        player.sendMessage(lang.get("gate.server-closed", player.getEffectiveLocale(),
                "reason", active.reason()));
    }

    /** Whether a connected player may pass, resolving identity where possible. */
    public boolean mayPass(Player player) {
        UUID minecraftId = player.getUniqueId();
        if (player.isOnlineMode() && allowlist.isBreakGlass(minecraftId)) {
            return true;
        }
        Optional<Identity> identity = allowlist.resolve(minecraftId);
        UUID internalId = identity.map(Identity::internalId).orElse(null);
        return allowlist.mayPass(internalId, minecraftId, player.isOnlineMode(),
                player.hasPermission(allowlist.bypassPermission()));
    }

    /** The disconnect or denial text for a window, which differs by mode and by whether it ends. */
    public Component closedMessage(MaintenanceWindow window, java.util.Locale locale) {
        Optional<java.time.Duration> remaining = window.remainingAt(Instant.now());
        boolean preLaunch = window.mode() == MaintenanceMode.PRE_LAUNCH;
        if (remaining.isPresent()) {
            return lang.get(preLaunch ? "gate.pre-launch" : "gate.maintenance", locale,
                    "reason", window.reason(),
                    "remaining", Durations.describe(remaining.get()));
        }
        return lang.get(preLaunch ? "gate.pre-launch-open-ended" : "gate.maintenance-open-ended", locale,
                "reason", window.reason());
    }
}

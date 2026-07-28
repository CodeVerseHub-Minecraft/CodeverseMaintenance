package net.codeverse.maintenance.motd;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.codeverse.api.maintenance.MaintenanceMode;
import net.codeverse.api.maintenance.MaintenanceWindow;
import net.codeverse.maintenance.config.PluginConfig;
import net.codeverse.maintenance.lang.LangManager;
import net.codeverse.maintenance.state.StateStore;
import net.codeverse.maintenance.util.Durations;
import net.kyori.adventure.text.Component;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The server list entry, rendered for whoever is asking.
 *
 * A ping carries no username, so personalisation works by address: an address
 * seen on a previous successful connection can be greeted by name and told
 * whether it is on the list. Anyone else sees the generic notice. That is the
 * difference between a server list saying "under maintenance" and one saying
 * "welcome back, you can join".
 *
 * The maintenance label goes in the version slot rather than the player count,
 * because the count slot only holds numbers. A client shown a protocol number
 * it does not recognise renders the version label in red beside the entry,
 * which is the visual wanted here, and the count carries a decoy so the list
 * does not advertise an empty network.
 */
public final class MotdRenderer {

    private final PluginConfig config;
    private final StateStore state;
    private final LangManager lang;
    private final IpMemory memory;

    public MotdRenderer(PluginConfig config, StateStore state, LangManager lang, IpMemory memory) {
        this.config = config;
        this.state = state;
        this.lang = lang;
        this.memory = memory;
    }

    // Negative priority so this runs after the authentication plugin.
    // Higher runs earlier in Velocity 4, and the identity has to be settled
    // before it is worth judging.
    @Subscribe(priority = -100)
    public void onPing(ProxyPingEvent event) {
        if (!config.motd.enabled) {
            return;
        }
        Optional<MaintenanceWindow> window = state.current();
        if (window.isEmpty() || !window.get().isNetworkWide()) {
            // Nothing closed, or only one server is, which is not worth
            // rewriting the whole network's entry over.
            return;
        }

        MaintenanceWindow active = window.get();
        ServerPing.Builder builder = event.getPing().asBuilder();

        builder.description(describe(active, address(event)));
        builder.version(new ServerPing.Version(-1, config.motd.closedPlayerCount));
        builder.onlinePlayers(0);
        builder.maximumPlayers(config.motd.decoyMaximum);
        builder.clearSamplePlayers();

        event.setPing(builder.build());
    }

    private static InetAddress address(ProxyPingEvent event) {
        java.net.SocketAddress remote = event.getConnection().getRemoteAddress();
        return remote instanceof InetSocketAddress socket ? socket.getAddress() : null;
    }

    private Component describe(MaintenanceWindow window, InetAddress address) {
        Optional<Duration> remaining = window.remainingAt(Instant.now());
        boolean preLaunch = window.mode() == MaintenanceMode.PRE_LAUNCH;
        Optional<String> known = memory.usernameFor(address);

        if (known.isPresent()) {
            if (memory.wasAllowed(address)) {
                return lang.get("motd.welcome-back-allowed", (java.util.Locale) null,
                        "reason", window.reason(),
                        "player", known.get());
            }
            return lang.get("motd.welcome-back", (java.util.Locale) null,
                    "reason", window.reason(),
                    "player", known.get(),
                    "remaining", remaining.map(Durations::describe).orElse("a while"));
        }

        if (remaining.isPresent()) {
            return lang.get(preLaunch ? "motd.pre-launch" : "motd.maintenance", (java.util.Locale) null,
                    "reason", window.reason(),
                    "remaining", Durations.describe(remaining.get()));
        }
        return lang.get(preLaunch ? "motd.pre-launch-open-ended" : "motd.maintenance-open-ended", (java.util.Locale) null,
                "reason", window.reason());
    }
}

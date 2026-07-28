package net.codeverse.maintenance;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.codeverse.api.CodeverseApiProvider;
import net.codeverse.api.identity.Identity;
import net.codeverse.api.maintenance.MaintenanceMode;
import net.codeverse.api.maintenance.MaintenanceService;
import net.codeverse.api.maintenance.MaintenanceWindow;
import net.codeverse.maintenance.allow.Allowlist;
import net.codeverse.maintenance.audit.AuditLog;
import net.codeverse.maintenance.command.MaintenanceCommand;
import net.codeverse.maintenance.config.PluginConfig;
import net.codeverse.maintenance.gate.HoldManager;
import net.codeverse.maintenance.gate.MaintenanceGate;
import net.codeverse.maintenance.http.ControlAuthenticator;
import net.codeverse.maintenance.http.HttpControlServer;
import net.codeverse.maintenance.lang.LangManager;
import net.codeverse.maintenance.motd.IpMemory;
import net.codeverse.maintenance.motd.MotdRenderer;
import net.codeverse.maintenance.service.MaintenanceServiceImpl;
import net.codeverse.maintenance.state.StateStore;
import net.codeverse.maintenance.util.Durations;
import net.codeverse.maintenance.webhook.DiscordWebhook;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Maintenance for a network that accepts cracked, Bedrock and premium players
 * at once.
 *
 * Proxy only, and that is sufficient rather than a shortcut: the backends are on
 * internal allocations and are not reachable from outside, so a gate on the
 * proxy is not the first line of defence but the only line that exists. The same
 * property that makes the authentication model meaningful makes a backend module
 * unnecessary here.
 */
@Plugin(
        id = "codeverse-maintenance",
        name = "Codeverse Maintenance",
        version = "0.1.0",
        description = "Maintenance and pre-launch gating for the Codeverse network",
        authors = {"CodeVerseHub-Minecraft Subteam"},
        // Not optional. This plugin compiles against the shared API and never
        // ships it, so without the authentication plugin those classes exist
        // nowhere on the proxy and this cannot load at all. Declaring it makes
        // Velocity refuse with a clear message and in the right order, rather
        // than failing at init with a NoClassDefFoundError, which is an Error
        // and so escapes a catch written for exceptions.
        dependencies = {@Dependency(id = "codeverse-auth")}
)
public final class CodeverseMaintenance {

    private static final String PLUGIN_ID = "codeverse-maintenance";
    private static final List<String> BUNDLED_LOCALES = List.of("en", "de");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private PluginConfig config;
    private LangManager lang;
    private StateStore state;
    private AuditLog audit;
    private DiscordWebhook webhook;
    private IpMemory ipMemory;
    private Allowlist allowlist;
    private HoldManager holds;
    private MaintenanceServiceImpl service;
    private MaintenanceGate gate;
    private HttpControlServer control;
    private ExecutorService executor;

    @Inject
    public CodeverseMaintenance(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            config = PluginConfig.load(dataDirectory);
            lang = new LangManager(dataDirectory, config.language.defaultLocale,
                    config.language.usePlayerLocale, BUNDLED_LOCALES);
            state = new StateStore(dataDirectory);
            audit = new AuditLog(dataDirectory, logger);
            webhook = new DiscordWebhook(config.webhook, logger);
            ipMemory = new IpMemory(config.motd.rememberAddresses, config.motd.addressMemoryHours);
            allowlist = new Allowlist(config, state);
            holds = new HoldManager(this, proxy, config, lang, logger);
            executor = Executors.newVirtualThreadPerTaskExecutor();

            service = new MaintenanceServiceImpl(state, allowlist, executor, this::onTransition);
            // Contributed rather than registered as the whole API, because the
            // authentication plugin owns that slot on the proxy. Consumers read
            // it through CodeverseApi.maintenance either way.
            CodeverseApiProvider.registerService(MaintenanceService.class, service);

            gate = new MaintenanceGate(proxy, config, state, allowlist, lang, holds);
            proxy.getEventManager().register(this, gate);
            proxy.getEventManager().register(this, new MotdRenderer(config, state, lang, ipMemory));
            // The plugin's own instance is registered by Velocity already, so
            // its @Subscribe methods need no second registration.

            proxy.getCommandManager().register(
                    proxy.getCommandManager().metaBuilder("maintenance").plugin(this).aliases("mt").build(),
                    new MaintenanceCommand(proxy, config, state, service, allowlist, holds, lang,
                            webhook, audit, logger));

            startControlInterface();
            scheduleUpkeep();
            warnIfLockoutPossible();

            Optional<MaintenanceWindow> active = state.current();
            logger.info("Maintenance ready. Network is {}{}, {} allowed, {} break glass, locales {}",
                    active.map(w -> w.mode().name().toLowerCase()).orElse("open"),
                    active.flatMap(w -> w.remainingAt(Instant.now()))
                            .map(d -> " for " + Durations.describe(d)).orElse(""),
                    state.allowed().size(),
                    config.breakGlass().size(),
                    lang.availableLocales());
        } catch (Exception failure) {
            logger.error("Maintenance failed to start. The network is NOT gated, so if a window was "
                    + "meant to be active it is not being enforced.", failure);
            shutdownResources();
        }
    }

    /**
     * Starts the control interface, or explains why it did not.
     *
     * A failure to bind is logged and swallowed. The interface exists so a bot
     * can toggle maintenance; the gate working without it is a degraded state,
     * while a proxy refusing to start because a port was taken is an outage.
     */
    private void startControlInterface() {
        if (!config.http.enabled) {
            return;
        }
        try {
            control = new HttpControlServer(config.http, new ControlAuthenticator(config.http),
                    service, state, audit, logger);
            control.start();
        } catch (Exception failure) {
            control = null;
            logger.error("The maintenance control interface could not start. Gating is unaffected, but "
                    + "nothing outside the proxy can toggle it until this is fixed.", failure);
        }
    }

    /**
     * Warns when the configuration could lock the operator out.
     *
     * With no break glass uuids and no identity service, a closed network has no
     * mechanism that can let anybody through, including whoever closed it. That
     * is worth saying at startup rather than discovering during an outage.
     */
    private void warnIfLockoutPossible() {
        if (!allowlist.hasBreakGlassEntries()) {
            logger.warn("No gate.breakGlassUuids are configured. If the database becomes unreachable "
                    + "while the network is closed, no identity can be resolved and nobody will be able "
                    + "to pass, including you. Add your own premium uuid.");
        }
    }

    /** Reacts to a window opening or closing: moves players, announces, records. */
    private void onTransition(MaintenanceServiceImpl.Transition transition) {
        Instant now = Instant.now();
        if (transition.current().isClosed()) {
            MaintenanceWindow window = transition.window().orElseThrow();
            announceClosed(window, now);
            if (window.isNetworkWide()) {
                enforceOnConnected(window);
            }
        } else {
            announceOpen(now);
            holds.releaseAll();
        }
    }

    /**
     * Applies a newly opened window to players already connected.
     *
     * Held rather than disconnected when the window is short, because a player
     * moved to the waiting room is let back in automatically while a
     * disconnected one has to notice the reopening themselves. A long window
     * disconnects instead: nobody waits an hour in a lobby, and holding them
     * costs limbo capacity that a short restart genuinely benefits from.
     */
    private void enforceOnConnected(MaintenanceWindow window) {
        Optional<java.time.Duration> length = window.remainingAt(Instant.now());
        boolean hold = config.hold.holdOnClose
                && length.map(d -> d.toMinutes() <= config.hold.holdMaximumMinutes).orElse(false);

        for (Player player : proxy.getAllPlayers()) {
            if (gate.mayPass(player)) {
                player.sendMessage(lang.get("notice.allowed-through", player.getEffectiveLocale()));
                continue;
            }
            if (hold) {
                proxy.getServer(config.gate.limboServer).ifPresentOrElse(limbo -> {
                    player.sendMessage(lang.get("notice.closing-now", player.getEffectiveLocale()));
                    holds.hold(player);
                    player.createConnectionRequest(limbo).fireAndForget();
                }, () -> player.disconnect(gate.closedMessage(window, player.getEffectiveLocale())));
            } else {
                player.disconnect(gate.closedMessage(window, player.getEffectiveLocale()));
            }
        }
    }

    private void announceClosed(MaintenanceWindow window, Instant now) {
        String description = window.reason()
                + window.endsAt().map(end -> "\n\nBack " + DiscordWebhook.timestamp(end, 'R')
                        + " (" + DiscordWebhook.timestamp(end, 'f') + ")").orElse("");
        webhook.announce(window.mode() == MaintenanceMode.PRE_LAUNCH
                ? "Codeverse is not open yet"
                : "Maintenance in progress", description, 0xE74C3C, true);
    }

    private void announceOpen(Instant now) {
        webhook.announce("Network is open",
                "Maintenance finished " + DiscordWebhook.timestamp(now, 'R') + ".", 0x2ECC71, false);
    }

    /** Remembers who connected from where, so the server list can greet them next time. */
    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        if (!(player.getRemoteAddress() instanceof InetSocketAddress socket)) {
            return;
        }
        Optional<Identity> identity = allowlist.resolve(player.getUniqueId());
        ipMemory.remember(socket.getAddress(),
                identity.map(Identity::internalId).orElse(null),
                player.getUsername(),
                gate.mayPass(player));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        holds.forget(event.getPlayer().getUniqueId());
    }

    /**
     * Activates a scheduled window when its moment arrives, and keeps the
     * address memory from growing without bound.
     */
    private void scheduleUpkeep() {
        proxy.getScheduler().buildTask(this, () -> {
            state.upcoming().ifPresent(next -> {
                if (next.startsInAt(Instant.now()).isEmpty()) {
                    // Its start has passed, so it becomes the active window.
                    service.open(next.mode(), next.reason(), next.servers(),
                                    next.endsAt().map(end -> java.time.Duration.between(Instant.now(), end)),
                                    null)
                            .thenRun(() -> {
                                try {
                                    state.clearSchedule();
                                } catch (java.io.IOException failure) {
                                    logger.error("A scheduled window activated but could not be cleared "
                                            + "from the schedule, so it may activate again.", failure);
                                }
                                audit.record(AuditLog.Source.SCHEDULE, "schedule", null, "OPEN",
                                        next.mode().name() + " " + next.reason());
                            });
                }
            });
            ipMemory.sweep();
        }).repeat(30, TimeUnit.SECONDS).schedule();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        shutdownResources();
    }

    private void shutdownResources() {
        if (control != null) {
            control.stop();
            control = null;
        }
        if (service != null) {
            CodeverseApiProvider.unregisterService(MaintenanceService.class, service);
            service = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}

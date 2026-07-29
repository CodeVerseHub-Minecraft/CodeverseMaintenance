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
        version = "0.1.2",
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
    // Whether the warning for the current schedule has been sent.
    private volatile boolean warned;
    // The mode as of the last transition this plugin acted on. A window that
    // lapses does so by the clock rather than by a command, so nothing would
    // otherwise notice, and the players held for it would wait for ever.
    private volatile MaintenanceMode actedOn = MaintenanceMode.OPEN;
    // Whether startup finished. The plugin's own listeners are registered by
    // the platform regardless, so they have to be able to tell.
    private volatile boolean ready;
    // Whether something optional failed and the operator should know.
    private volatile boolean degraded;
    private ExecutorService executor;

    @Inject
    public CodeverseMaintenance(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // The order here is a priority order rather than a convenience one.
        // Whether the network should be closed is the only thing this plugin
        // exists to know, and it lives in a file with no dependencies, so it is
        // read first and everything after it is allowed to degrade. Reading
        // config first, as this once did, meant a stray comma in a file an
        // operator had just edited could leave a network that was supposed to
        // be sealed wide open.
        try {
            state = new StateStore(dataDirectory);
        } catch (Exception fatal) {
            logger.error("The maintenance state could not be read, so this plugin cannot tell whether the "
                    + "network is supposed to be closed. It is NOT gating anything. Fix or delete "
                    + "maintenance.json in the plugin directory and restart.", fatal);
            return;
        }

        try {
            config = PluginConfig.load(dataDirectory);
        } catch (Exception broken) {
            // Defaults rather than a refusal to start. A configuration mistake
            // should cost the operator their settings, not their gate.
            config = new PluginConfig();
            degraded = true;
            logger.error("config.json could not be read, so built in defaults are in use and your "
                    + "settings are being ignored. Gating still works. Fix the file and restart.", broken);
        }

        try {
            lang = new LangManager(dataDirectory, config.language.defaultLocale,
                    config.language.usePlayerLocale, BUNDLED_LOCALES);
            if (!lang.degradedLocales().isEmpty()) {
                degraded = true;
                logger.error("These language files could not be read and bundled text is being used "
                        + "instead: {}. The files have been left alone.", lang.degradedLocales());
            }
        } catch (Exception fatal) {
            logger.error("No language could be loaded at all, which should be impossible because the "
                    + "text is bundled in the jar. Not gating.", fatal);
            return;
        }

        try {
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

            actedOn = state.mode();
            ready = true;
        } catch (Exception fatal) {
            logger.error("Maintenance could not finish starting, so the network is NOT being gated even "
                    + "though the saved state may say it should be.", fatal);
            shutdownResources();
            return;
        }

        startControlInterface();
        scheduleUpkeep();
        warnIfLockoutPossible();

        Optional<MaintenanceWindow> active = state.current();
        logger.info("Maintenance ready. Network is {}{}, {} allowed, {} break glass, locales {}{}",
                active.map(w -> w.mode().name().toLowerCase()).orElse("open"),
                active.flatMap(w -> w.remainingAt(Instant.now()))
                        .map(d -> " for " + Durations.describe(d)).orElse(""),
                state.allowed().size(),
                config.breakGlass().size(),
                lang.availableLocales(),
                degraded ? " (DEGRADED, see errors above)" : "");
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
        actedOn = transition.current();
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
        boolean hold = state.holdPlayers();

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
        boolean preLaunch = window.mode() == MaintenanceMode.PRE_LAUNCH;
        List<DiscordWebhook.Field> fields = new java.util.ArrayList<>();
        fields.add(new DiscordWebhook.Field("Reason", window.reason(), false));
        fields.add(new DiscordWebhook.Field(
                preLaunch ? "Opens" : "Back",
                window.endsAt()
                        .map(end -> DiscordWebhook.timestamp(end, 'R')
                                + "  " + DiscordWebhook.timestamp(end, 'f'))
                        .orElse("When it is ready"),
                true));
        fields.add(new DiscordWebhook.Field("Can I join",
                state.holdPlayers()
                        ? "Yes, you will wait in the lobby and be let in automatically"
                        : "Not yet, try again when it reopens",
                true));
        if (!window.isNetworkWide()) {
            fields.add(new DiscordWebhook.Field("Affected", String.join(", ", window.servers()), false));
        }

        webhook.announce(
                preLaunch ? "Codeverse is not open yet" : "Maintenance in progress",
                null,
                preLaunch ? 0x3498DB : 0xE74C3C,
                true,
                fields);
    }

    private void announceOpen(Instant now) {
        webhook.announce("The network is open", null, 0x2ECC71, false,
                List.of(new DiscordWebhook.Field("Reopened", DiscordWebhook.timestamp(now, 'R'), true)));
    }

    /** Remembers who connected from where, so the server list can greet them next time. */
    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        // Velocity registers this class itself, so these fire whether or not
        // startup finished. Without the guard a failed start produced a stack
        // trace on every single join, which buried the one line saying what had
        // actually gone wrong.
        if (!ready) {
            return;
        }
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
        if (!ready) {
            return;
        }
        holds.forget(event.getPlayer().getUniqueId());
    }

    /**
     * Activates a scheduled window when its moment arrives, and keeps the
     * address memory from growing without bound.
     */
    private void scheduleUpkeep() {
        proxy.getScheduler().buildTask(this, () -> {
            try {
                reactToClockChanges();
                activateDueSchedule();
                warnBeforeScheduled();
            } catch (RuntimeException failure) {
                logger.error("Maintenance upkeep failed", failure);
            }
            ipMemory.sweep();
        }).repeat(10, TimeUnit.SECONDS).schedule();

        // A closed network that nobody can enter is worth repeating rather than
        // saying once at boot, because the boot line scrolls away and the
        // situation does not.
        proxy.getScheduler().buildTask(this, () -> {
            if (state.current().isPresent() && state.allowed().isEmpty()
                    && config.breakGlass().isEmpty()) {
                logger.error("The network is closed and there is nobody on the allowlist and no break "
                        + "glass uuid. Nobody can get in, including you. Add one to config.json or run "
                        + "maintenance off from the console.");
            }
        }).repeat(5, TimeUnit.MINUTES).schedule();
    }

    /**
     * Notices a window that lapsed rather than being closed.
     *
     * A window with a duration ends by the clock, so no command fires and
     * nothing would otherwise react. The gate and the server list correct
     * themselves because both read the state fresh, but the players being held
     * for that window would wait for ever, no announcement would say the
     * network was back, and the audit would show a closing with no reopening.
     */
    private void reactToClockChanges() {
        MaintenanceMode current = state.mode();
        if (current == actedOn) {
            return;
        }
        MaintenanceMode previous = actedOn;
        actedOn = current;
        if (!current.isClosed()) {
            logger.info("The maintenance window ended on schedule. Releasing {} held players.",
                    holds.heldCount());
            audit.record(AuditLog.Source.SCHEDULE, "clock", null, "EXPIRE",
                    previous.name() + " lapsed");
            announceOpen(Instant.now());
            holds.releaseAll();
        }
    }

    /** Opens a scheduled window once its moment has arrived. */
    private void activateDueSchedule() {
        state.dueSchedule().ifPresent(due -> {
            java.time.Duration length = due.endsAt()
                    .map(end -> java.time.Duration.between(Instant.now(), end))
                    .filter(remaining -> !remaining.isNegative() && !remaining.isZero())
                    .orElse(null);
            service.open(due.mode(), due.reason(), due.servers(),
                            Optional.ofNullable(length), null)
                    .whenComplete((window, failure) -> {
                        if (failure != null) {
                            logger.error("A scheduled window was due but could not be opened. It stays "
                                    + "scheduled and will be retried.", failure);
                            return;
                        }
                        try {
                            state.clearSchedule();
                        } catch (java.io.IOException clearFailure) {
                            logger.error("A scheduled window activated but could not be cleared from the "
                                    + "schedule, so it may activate again.", clearFailure);
                        }
                        warned = false;
                        audit.record(AuditLog.Source.SCHEDULE, "schedule", null, "OPEN",
                                due.mode().name() + " " + due.reason());
                    });
        });
    }

    /**
     * Tells players a scheduled window is coming, once.
     *
     * Sent once rather than on every pass, because a countdown repeated every
     * ten seconds is noise people learn to ignore, and the point is that they
     * have time to finish what they are doing and log off deliberately.
     */
    private void warnBeforeScheduled() {
        Optional<MaintenanceWindow> next = state.upcoming();
        if (next.isEmpty()) {
            warned = false;
            return;
        }
        Optional<java.time.Duration> until = next.get().startsInAt(Instant.now());
        if (until.isEmpty() || warned) {
            return;
        }
        if (until.get().toSeconds() > config.hold.warningSeconds) {
            return;
        }
        warned = true;
        for (Player player : proxy.getAllPlayers()) {
            player.sendMessage(lang.get("notice.closing-soon", player.getEffectiveLocale(),
                    "remaining", Durations.describe(until.get()),
                    "reason", next.get().reason()));
        }
        logger.info("Warned {} players that a scheduled window begins in {}.",
                proxy.getAllPlayers().size(), Durations.describe(until.get()));
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

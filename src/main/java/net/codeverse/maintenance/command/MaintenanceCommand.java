package net.codeverse.maintenance.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.codeverse.api.identity.Identity;
import net.codeverse.api.maintenance.MaintenanceMode;
import net.codeverse.api.maintenance.MaintenanceWindow;
import net.codeverse.maintenance.allow.Allowlist;
import net.codeverse.maintenance.audit.AuditLog;
import net.codeverse.maintenance.config.PluginConfig;
import net.codeverse.maintenance.gate.HoldManager;
import net.codeverse.maintenance.lang.LangManager;
import net.codeverse.maintenance.service.MaintenanceServiceImpl;
import net.codeverse.maintenance.state.StateStore;
import net.codeverse.maintenance.util.Durations;
import net.codeverse.maintenance.webhook.DiscordWebhook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The operator interface.
 *
 * Velocity has no inventory screens, so the interactive form is chat: a status
 * block with clickable actions, redrawn on demand. Every action it offers is
 * also reachable by typing the whole command, so nothing is only possible
 * through the interface and scripting stays available.
 *
 * The block is only redrawn in response to something the operator did. Nothing
 * redraws on a timer, because flushing someone's chat while they are reading it
 * is worse than a slightly stale view.
 */
public final class MaintenanceCommand implements SimpleCommand {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String PERMISSION = "codeverse.maintenance.admin";

    private final ProxyServer proxy;
    private final PluginConfig config;
    private final StateStore state;
    private final MaintenanceServiceImpl service;
    private final Allowlist allowlist;
    private final HoldManager holds;
    private final LangManager lang;
    private final DiscordWebhook webhook;
    private final AuditLog audit;
    private final Logger logger;

    public MaintenanceCommand(ProxyServer proxy, PluginConfig config, StateStore state,
                              MaintenanceServiceImpl service, Allowlist allowlist, HoldManager holds,
                              LangManager lang, DiscordWebhook webhook, AuditLog audit, Logger logger) {
        this.proxy = proxy;
        this.config = config;
        this.state = state;
        this.service = service;
        this.allowlist = allowlist;
        this.holds = holds;
        this.lang = lang;
        this.webhook = webhook;
        this.audit = audit;
        this.logger = logger;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        Locale locale = source instanceof Player player ? player.getEffectiveLocale() : null;

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sendStatus(source, locale);
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on" -> open(source, locale, MaintenanceMode.MAINTENANCE, tail(args, 1), true);
            case "hold" -> open(source, locale, MaintenanceMode.MAINTENANCE, tail(args, 1), true);
            case "lock" -> open(source, locale, MaintenanceMode.MAINTENANCE, tail(args, 1), false);
            case "prelaunch" -> open(source, locale, MaintenanceMode.PRE_LAUNCH, tail(args, 1), false);
            case "off" -> close(source, locale);
            case "server" -> server(source, locale, args);
            case "schedule" -> schedule(source, locale, args);
            case "unschedule" -> unschedule(source, locale);
            case "allow" -> allow(source, locale, args);
            case "webhook" -> webhook(source, locale, args);
            default -> source.sendMessage(lang.get("command.usage", locale));
        }
    }

    private void sendStatus(CommandSource source, Locale locale) {
        // A run of blank lines pushes the previous block out of view, so the
        // new one reads as a redraw rather than as another wall of text below
        // the last. Only ever done in response to a command, never on a timer.
        for (int i = 0; i < 20; i++) {
            source.sendMessage(Component.empty());
        }
        source.sendMessage(lang.get("command.header", locale));
        source.sendMessage(Component.empty());

        Optional<MaintenanceWindow> window = state.current();
        if (window.isEmpty()) {
            source.sendMessage(lang.get("command.status-open", locale));
        } else {
            MaintenanceWindow active = window.get();
            source.sendMessage(lang.get("command.status-closed", locale,
                    "mode", active.mode().name(), "reason", active.reason()));
            Optional<Duration> remaining = active.remainingAt(Instant.now());
            source.sendMessage(remaining
                    .map(d -> lang.get("command.status-remaining", locale, "remaining", Durations.describe(d)))
                    .orElseGet(() -> lang.get("command.status-open-ended", locale)));
            source.sendMessage(lang.get(state.holdPlayers()
                    ? "command.status-hold" : "command.status-lock", locale,
                    "held", String.valueOf(holds.heldCount())));
            if (!active.isNetworkWide()) {
                source.sendMessage(lang.get("command.status-servers", locale,
                        "servers", String.join(", ", active.servers())));
            }
        }

        source.sendMessage(lang.get("command.status-allowed", locale,
                "count", String.valueOf(state.allowed().size()),
                "breakglass", String.valueOf(config.breakGlass().size())));

        if (!allowlist.isIdentityAvailable()) {
            source.sendMessage(lang.get("command.status-identity-down", locale));
        }

        state.upcoming().ifPresent(next -> next.startsInAt(Instant.now()).ifPresent(starts ->
                source.sendMessage(lang.get("command.status-upcoming", locale,
                        "mode", next.mode().name(), "starts", Durations.describe(starts)))));

        source.sendMessage(Component.empty());
        source.sendMessage(actions(window.isPresent()));
    }

    /** The clickable row. Suggests rather than runs where a reason is needed. */
    private Component actions(boolean closed) {
        Component row = Component.empty();
        if (closed) {
            row = row.append(button("<green>[ open the network ]</green>",
                    "/maintenance off", true, "Reopen and release held players"));
        } else {
            row = row.append(button("<gold>[ close, queue players ]</gold>",
                    "/maintenance hold ", false,
                    "Everyone can connect and waits in limbo, released automatically"));
            row = row.append(Component.text("  "));
            row = row.append(button("<red>[ close, turn away ]</red>",
                    "/maintenance lock ", false,
                    "Nobody but the allowlist can connect at all"));
            row = row.append(Component.text("  "));
            row = row.append(button("<aqua>[ pre-launch ]</aqua>",
                    "/maintenance prelaunch ", false, "Type a reason, then enter"));
        }
        row = row.append(Component.text("  "));
        row = row.append(button("<gray>[ refresh ]</gray>", "/maintenance status", true, "Redraw this"));
        return row;
    }

    private Component button(String label, String command, boolean run, String hover) {
        return MINI.deserialize(label)
                .clickEvent(run ? ClickEvent.runCommand(command) : ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover)));
    }

    private void open(CommandSource source, Locale locale, MaintenanceMode mode, String[] rest,
                      boolean holdPlayers) {
        if (rest.length == 0) {
            source.sendMessage(lang.get("command.usage", locale));
            return;
        }
        // A leading token that parses as a duration is one; otherwise it is the
        // start of the reason. That way both forms work without a flag.
        Optional<Duration> duration = Durations.parse(rest[0]);
        String reason = String.join(" ", duration.isPresent() ? tail(rest, 1) : rest);
        if (reason.isBlank()) {
            source.sendMessage(lang.get("command.usage", locale));
            return;
        }
        if (rest.length > 0 && duration.isEmpty() && looksLikeDuration(rest[0])) {
            source.sendMessage(lang.get("command.bad-duration", locale));
            return;
        }

        UUID actor = identityOf(source);
        service.open(mode, reason, Set.of(), duration, actor, holdPlayers)
                .whenComplete((window, failure) -> {
            if (failure != null) {
                logger.error("Could not open a maintenance window", failure);
                source.sendMessage(lang.get("error.storage", locale));
                return;
            }
            audit.record(sourceKind(source), nameOf(source), actor, "OPEN",
                    mode.name() + (holdPlayers ? " hold" : " lock") + " " + reason
                            + duration.map(d -> " for " + Durations.describe(d)).orElse(""));
            source.sendMessage(lang.get(holdPlayers ? "command.opened-hold" : "command.opened-lock",
                    locale, "mode", mode.name(), "reason", reason));
        });
    }

    private void close(CommandSource source, Locale locale) {
        UUID actor = identityOf(source);
        int waiting = holds.heldCount();
        service.close(Set.of(), actor).whenComplete((changed, failure) -> {
            if (failure != null) {
                logger.error("Could not close the maintenance window", failure);
                source.sendMessage(lang.get("error.storage", locale));
                return;
            }
            if (!Boolean.TRUE.equals(changed)) {
                source.sendMessage(lang.get("command.already-open", locale));
                return;
            }
            audit.record(sourceKind(source), nameOf(source), actor, "CLOSE", "released " + waiting);
            source.sendMessage(lang.get("command.closed", locale, "held", String.valueOf(waiting)));
        });
    }

    private void server(CommandSource source, Locale locale, String[] args) {
        if (args.length < 3) {
            source.sendMessage(lang.get("command.usage", locale));
            return;
        }
        String server = args[1];
        boolean on = args[2].equalsIgnoreCase("on");
        UUID actor = identityOf(source);

        CompletableFuture<?> work = on
                ? service.open(MaintenanceMode.MAINTENANCE, "server maintenance", Set.of(server),
                        Optional.empty(), actor)
                : service.close(Set.of(server), actor);

        work.whenComplete((result, failure) -> {
            if (failure != null) {
                logger.error("Could not change maintenance for server {}", server, failure);
                source.sendMessage(lang.get("error.storage", locale));
                return;
            }
            audit.record(sourceKind(source), nameOf(source), actor,
                    on ? "SERVER_OPEN" : "SERVER_CLOSE", server);
            sendStatus(source, locale);
        });
    }

    /**
     * Schedules a window for later.
     *
     * The moment may be relative, which is timezone free and usually what is
     * meant, or an absolute instant carrying an explicit zone. A bare local
     * time is refused: written without a zone it is ambiguous exactly once, on
     * the day it matters, and this community spans enough of them that the
     * ambiguity would land on somebody.
     */
    private void schedule(CommandSource source, Locale locale, String[] args) {
        if (args.length < 3) {
            source.sendMessage(lang.get("command.usage", locale));
            return;
        }
        Instant now = Instant.now();
        Optional<Instant> startAt = Durations.parseMoment(args[1], now);
        if (startAt.isEmpty()) {
            source.sendMessage(lang.get("command.bad-moment", locale));
            return;
        }
        if (!startAt.get().isAfter(now)) {
            source.sendMessage(lang.get("command.schedule-in-past", locale));
            return;
        }

        // An optional duration may follow the start, so the window can lift
        // itself. Anything that is not a duration is the start of the reason.
        Optional<Duration> length = Durations.parse(args[2]);
        String reason = String.join(" ", tail(args, length.isPresent() ? 3 : 2));
        if (reason.isBlank()) {
            source.sendMessage(lang.get("command.usage", locale));
            return;
        }

        MaintenanceMode mode = reason.toLowerCase(Locale.ROOT).contains("launch")
                ? MaintenanceMode.PRE_LAUNCH
                : MaintenanceMode.MAINTENANCE;
        try {
            state.schedule(mode, reason, startAt.get(), length.map(startAt.get()::plus).orElse(null));
            audit.record(sourceKind(source), nameOf(source), identityOf(source), "SCHEDULE",
                    mode.name() + " at " + startAt.get() + " " + reason);
            source.sendMessage(lang.get("command.scheduled", locale,
                    "mode", mode.name(),
                    "starts", Durations.describe(Duration.between(now, startAt.get()))));
        } catch (java.io.IOException failure) {
            logger.error("Could not save the schedule", failure);
            source.sendMessage(lang.get("error.storage", locale));
        }
    }

    private void unschedule(CommandSource source, Locale locale) {
        try {
            state.clearSchedule();
            audit.record(sourceKind(source), nameOf(source), identityOf(source), "UNSCHEDULE", "");
            source.sendMessage(lang.get("command.unscheduled", locale));
        } catch (java.io.IOException failure) {
            logger.error("Could not clear the schedule", failure);
            source.sendMessage(lang.get("error.storage", locale));
        }
    }

    private void allow(CommandSource source, Locale locale, String[] args) {
        if (args.length < 3) {
            source.sendMessage(lang.get("command.usage", locale));
            return;
        }
        boolean adding = args[1].equalsIgnoreCase("add");
        String target = args[2];

        if (!allowlist.isIdentityAvailable()) {
            // Without identity there is no internal id to key an allowance on,
            // and keying it on a name would be keying it on a claim.
            source.sendMessage(lang.get("command.allow-needs-identity", locale));
            return;
        }

        Optional<Identity> identity = proxy.getPlayer(target)
                .map(player -> allowlist.resolve(player.getUniqueId()))
                .filter(Optional::isPresent)
                .map(Optional::get);
        if (identity.isEmpty()) {
            identity = net.codeverse.api.CodeverseApiProvider.find()
                    .flatMap(api -> api.identity())
                    .flatMap(service -> {
                        try {
                            return service.byUsername(target).join();
                        } catch (RuntimeException unavailable) {
                            return Optional.empty();
                        }
                    });
        }

        if (identity.isEmpty()) {
            source.sendMessage(lang.get("command.allow-unknown", locale, "player", target));
            return;
        }

        UUID internalId = identity.get().internalId();
        UUID actor = identityOf(source);
        try {
            if (adding) {
                state.allow(internalId);
                audit.record(sourceKind(source), nameOf(source), actor, "ALLOW_ADD",
                        target + " " + internalId);
                source.sendMessage(lang.get("command.allow-added", locale, "player", target));
            } else {
                state.disallow(internalId);
                audit.record(sourceKind(source), nameOf(source), actor, "ALLOW_REMOVE",
                        target + " " + internalId);
                source.sendMessage(lang.get("command.allow-removed", locale, "player", target));
            }
        } catch (java.io.IOException failure) {
            logger.error("Could not update the allowlist", failure);
            source.sendMessage(lang.get("error.storage", locale));
        }
    }

    private void webhook(CommandSource source, Locale locale, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("test")) {
            source.sendMessage(lang.get("command.usage", locale));
            return;
        }
        if (!webhook.isEnabled()) {
            source.sendMessage(lang.get("command.webhook-disabled", locale));
            return;
        }
        try {
            webhook.test();
            audit.record(sourceKind(source), nameOf(source), identityOf(source), "WEBHOOK_TEST", "");
            source.sendMessage(lang.get("command.webhook-sent", locale));
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            source.sendMessage(lang.get("command.webhook-failed", locale,
                    "error", String.valueOf(failure.getMessage())));
        }
    }

    private static boolean looksLikeDuration(String token) {
        return !token.isEmpty() && Character.isDigit(token.charAt(0));
    }

    private static String[] tail(String[] args, int from) {
        return from >= args.length ? new String[0] : Arrays.copyOfRange(args, from, args.length);
    }

    private UUID identityOf(CommandSource source) {
        if (!(source instanceof Player player)) {
            return null;
        }
        return allowlist.resolve(player.getUniqueId()).map(Identity::internalId).orElse(null);
    }

    private static String nameOf(CommandSource source) {
        return source instanceof Player player ? player.getUsername() : "console";
    }

    private static AuditLog.Source sourceKind(CommandSource source) {
        return source instanceof Player ? AuditLog.Source.PLAYER : AuditLog.Source.CONSOLE;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            return List.of("status", "on", "hold", "lock", "prelaunch", "off", "schedule",
                    "unschedule", "server", "allow", "webhook");
        }
        if (args[0].equalsIgnoreCase("server") && args.length == 2) {
            List<String> servers = new ArrayList<>();
            proxy.getAllServers().forEach(s -> servers.add(s.getServerInfo().getName()));
            return servers;
        }
        if (args[0].equalsIgnoreCase("server") && args.length == 3) {
            return List.of("on", "off");
        }
        if (args[0].equalsIgnoreCase("allow") && args.length == 2) {
            return List.of("add", "remove");
        }
        if (args[0].equalsIgnoreCase("webhook") && args.length == 2) {
            return List.of("test");
        }
        return List.of();
    }
}

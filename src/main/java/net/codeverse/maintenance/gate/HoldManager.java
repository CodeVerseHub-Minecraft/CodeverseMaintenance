package net.codeverse.maintenance.gate;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.codeverse.maintenance.config.PluginConfig;
import net.codeverse.maintenance.lang.LangManager;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Who is waiting in limbo, and how they get let out.
 *
 * Releasing everyone the instant a window lifts sends the whole set through
 * authentication at once, and signing in runs Argon2id, which is deliberately
 * memory hard and expensive. Fifty simultaneous verifications is a spike on the
 * one resource this network is tightest on; a few hundred, which is what a
 * launch countdown produces, is a spike shaped exactly like an attack. Spreading
 * the release over a short window turns that into a queue nobody notices.
 */
public final class HoldManager {

    private final ProxyServer proxy;
    private final PluginConfig config;
    private final LangManager lang;
    private final Logger logger;
    private final Object plugin;
    private final Set<UUID> held = ConcurrentHashMap.newKeySet();

    public HoldManager(Object plugin, ProxyServer proxy, PluginConfig config, LangManager lang, Logger logger) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.config = config;
        this.lang = lang;
        this.logger = logger;
    }

    public void hold(Player player) {
        held.add(player.getUniqueId());
    }

    public void forget(UUID playerId) {
        held.remove(playerId);
    }

    public int heldCount() {
        return held.size();
    }

    /**
     * Lets everyone out, spread over the configured window.
     *
     * Players who left while held are simply absent from the proxy and are
     * skipped, so a stale entry costs nothing.
     */
    public void releaseAll() {
        List<UUID> waiting = List.copyOf(held);
        held.clear();
        if (waiting.isEmpty()) {
            return;
        }

        Optional<RegisteredServer> lobby = proxy.getServer(config.gate.lobbyServer);
        if (lobby.isEmpty()) {
            logger.error("Cannot release {} held players: no server named '{}' is registered. They are "
                    + "still connected and can move themselves.", waiting.size(), config.gate.lobbyServer);
            return;
        }

        int stagger = Math.max(0, config.hold.releaseStaggerSeconds);
        if (stagger == 0 || waiting.size() == 1) {
            waiting.forEach(id -> send(id, lobby.get()));
            return;
        }

        // Spread evenly across the window rather than in bursts, so the
        // authentication path sees a steady trickle instead of clumps.
        long spacingMillis = Math.max(1L, (stagger * 1000L) / waiting.size());
        for (int i = 0; i < waiting.size(); i++) {
            UUID id = waiting.get(i);
            long delay = spacingMillis * i;
            proxy.getScheduler().buildTask(plugin, () -> send(id, lobby.get()))
                    .delay(delay, TimeUnit.MILLISECONDS)
                    .schedule();
        }
        logger.info("Releasing {} held players over {} seconds.", waiting.size(), stagger);
    }

    private void send(UUID playerId, RegisteredServer lobby) {
        proxy.getPlayer(playerId).ifPresent(player -> {
            player.sendMessage(lang.get("gate.released", player.getEffectiveLocale()));
            player.createConnectionRequest(lobby).fireAndForget();
        });
    }
}

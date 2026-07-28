package net.codeverse.maintenance.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.codeverse.api.maintenance.MaintenanceMode;
import net.codeverse.api.maintenance.MaintenanceWindow;
import net.codeverse.maintenance.audit.AuditLog;
import net.codeverse.maintenance.service.MaintenanceServiceImpl;
import net.codeverse.maintenance.state.StateStore;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Lets the Discord bot see and change maintenance without a client.
 *
 * Uses the JDK's own server rather than a framework, because the surface is
 * four endpoints and a dependency would be more to relocate and account for
 * than the problem is worth.
 *
 * Every response is deliberately uninformative about why a request failed. A
 * caller that is refused learns only that it was refused, never whether the
 * address was wrong, the signature was wrong, or the path does not exist,
 * because each of those is a fact worth having if you are trying to find a way
 * in.
 */
public final class HttpControlServer {

    private static final Gson GSON = new Gson();

    private final HttpControlConfig config;
    private final ControlAuthenticator authenticator;
    private final MaintenanceServiceImpl service;
    private final StateStore state;
    private final AuditLog audit;
    private final Logger logger;
    private HttpServer server;

    public HttpControlServer(HttpControlConfig config, ControlAuthenticator authenticator,
                             MaintenanceServiceImpl service, StateStore state,
                             AuditLog audit, Logger logger) {
        this.config = config;
        this.authenticator = authenticator;
        this.service = service;
        this.state = state;
        this.audit = audit;
        this.logger = logger;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.bindAddress, config.port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        logger.info("Maintenance control interface listening on {}:{}", config.bindAddress, config.port);
        if (!config.bindAddress.equals("127.0.0.1")) {
            logger.warn("The control interface is bound to {}, which is not loopback. It can close the "
                    + "whole network, so firewall the port to the addresses in http.allowedAddresses as "
                    + "well; the allowlist and the firewall then fail independently.", config.bindAddress);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String address = exchange.getRemoteAddress().getAddress().getHostAddress();
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readAllBytes();
            }

            ControlAuthenticator.Result result = authenticator.authenticate(
                    address, method, path,
                    exchange.getRequestHeaders().getFirst("X-Codeverse-Timestamp"),
                    exchange.getRequestHeaders().getFirst("X-Codeverse-Nonce"),
                    exchange.getRequestHeaders().getFirst("X-Codeverse-Signature"),
                    body);
            if (result != ControlAuthenticator.Result.ALLOWED) {
                respond(exchange, 401, refusal());
                return;
            }

            route(exchange, method, path, body, address);
        } catch (RuntimeException failure) {
            logger.error("The control interface failed to handle a request", failure);
            respond(exchange, 500, refusal());
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange, String method, String path, byte[] body, String address)
            throws IOException {
        if (path.equals("/v1/status") && method.equals("GET")) {
            respond(exchange, 200, status());
            return;
        }
        if (path.equals("/v1/maintenance") && method.equals("POST")) {
            open(exchange, body, address);
            return;
        }
        if (path.equals("/v1/maintenance") && method.equals("DELETE")) {
            close(exchange, address);
            return;
        }
        // An unknown path is refused the same way a bad signature is, so
        // probing cannot map the interface.
        respond(exchange, 401, refusal());
    }

    private void open(HttpExchange exchange, byte[] body, String address) throws IOException {
        JsonObject request;
        try {
            request = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException malformed) {
            respond(exchange, 400, error("malformed body"));
            return;
        }

        String reason = request.has("reason") ? request.get("reason").getAsString() : "";
        if (reason.isBlank()) {
            respond(exchange, 400, error("a reason is required, because players are shown it"));
            return;
        }
        MaintenanceMode mode = MaintenanceMode.MAINTENANCE;
        if (request.has("mode")) {
            try {
                mode = MaintenanceMode.valueOf(request.get("mode").getAsString().toUpperCase());
            } catch (IllegalArgumentException unknown) {
                respond(exchange, 400, error("unknown mode"));
                return;
            }
            if (mode == MaintenanceMode.OPEN) {
                respond(exchange, 400, error("use DELETE to reopen"));
                return;
            }
        }
        Optional<Duration> duration = request.has("durationSeconds")
                ? Optional.of(Duration.ofSeconds(request.get("durationSeconds").getAsLong()))
                : Optional.empty();
        Set<String> servers = new LinkedHashSet<>();
        if (request.has("servers")) {
            request.getAsJsonArray("servers").forEach(element -> servers.add(element.getAsString()));
        }

        try {
            MaintenanceWindow window = service.open(mode, reason, servers, duration, null).join();
            audit.record(AuditLog.Source.REMOTE, address, null, "OPEN",
                    window.mode().name() + " " + reason);
            respond(exchange, 200, status());
        } catch (RuntimeException failure) {
            logger.error("A remote request could not open a window", failure);
            respond(exchange, 500, error("could not open"));
        }
    }

    private void close(HttpExchange exchange, String address) throws IOException {
        try {
            boolean changed = service.close(Set.of(), null).join();
            audit.record(AuditLog.Source.REMOTE, address, null, "CLOSE", String.valueOf(changed));
            respond(exchange, 200, status());
        } catch (RuntimeException failure) {
            logger.error("A remote request could not close the window", failure);
            respond(exchange, 500, error("could not close"));
        }
    }

    /**
     * The current state.
     *
     * Carries no token, no allowlist contents and no identity, because a status
     * endpoint that leaks who may pass is a status endpoint that tells an
     * attacker who to impersonate. Counts are enough for a bot to render.
     */
    private String status() {
        JsonObject json = new JsonObject();
        Optional<MaintenanceWindow> window = state.current();
        json.addProperty("mode", state.mode().name());
        json.addProperty("active", window.isPresent());
        window.ifPresent(active -> {
            json.addProperty("reason", active.reason());
            json.addProperty("startedAt", active.startedAt().getEpochSecond());
            active.endsAt().ifPresent(end -> json.addProperty("endsAt", end.getEpochSecond()));
            active.remainingAt(Instant.now())
                    .ifPresent(remaining -> json.addProperty("remainingSeconds", remaining.toSeconds()));
            json.addProperty("networkWide", active.isNetworkWide());
        });
        state.upcoming().ifPresent(next -> {
            json.addProperty("scheduledMode", next.mode().name());
            json.addProperty("scheduledStartAt", next.startedAt().getEpochSecond());
        });
        json.addProperty("allowedCount", state.allowed().size());
        return GSON.toJson(json);
    }

    private static String refusal() {
        JsonObject json = new JsonObject();
        json.addProperty("error", "refused");
        return GSON.toJson(json);
    }

    private static String error(String message) {
        JsonObject json = new JsonObject();
        json.addProperty("error", message);
        return GSON.toJson(json);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}

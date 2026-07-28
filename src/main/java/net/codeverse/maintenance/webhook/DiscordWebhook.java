package net.codeverse.maintenance.webhook;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.codeverse.maintenance.config.PluginConfig;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Announces maintenance to Discord.
 *
 * Edits a single message rather than posting one per change, because a channel
 * with forty maintenance notices in it stops being read, and the thing people
 * actually want is the current state rather than its history. The history lives
 * in the audit log, which is the right place for it.
 *
 * Times are sent as Discord timestamp markup rather than formatted text.
 * Discord renders those in each reader's own timezone, which for a community
 * spread across a dozen of them is the difference between everyone knowing when
 * the network opens and everyone doing arithmetic. It is also the only way to
 * be correct without knowing where each reader is.
 */
public final class DiscordWebhook {

    private static final Gson GSON = new Gson();

    private final PluginConfig.Webhook config;
    private final HttpClient http;
    private final Logger logger;
    private volatile String messageId;

    public DiscordWebhook(PluginConfig.Webhook config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean isEnabled() {
        return config.enabled && config.url != null && !config.url.isBlank();
    }

    /**
     * Renders an instant as Discord timestamp markup.
     *
     * @param style f for a full date and time, R for a relative countdown
     */
    public static String timestamp(Instant instant, char style) {
        return "<t:" + instant.getEpochSecond() + ":" + style + ">";
    }

    /**
     * Posts or updates the status message.
     *
     * A failure is logged and swallowed. Discord being unreachable must never
     * be the reason a maintenance window fails to open: the announcement is a
     * courtesy and the gate is the point.
     */
    public void announce(String title, String description, int colour, boolean resetMessage) {
        if (!isEnabled()) {
            return;
        }
        if (resetMessage) {
            messageId = null;
        }
        try {
            JsonObject payload = buildPayload(title, description, colour);
            String existing = messageId;
            if (config.editSingleMessage && existing != null) {
                if (edit(existing, payload)) {
                    return;
                }
                // The message was deleted or is otherwise gone; fall through
                // and post a fresh one rather than losing the announcement.
                messageId = null;
            }
            post(payload);
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warn("Could not reach the Discord webhook: {}", failure.getMessage());
        }
    }

    /** Sends a test message so an operator can confirm the webhook works before relying on it. */
    public void test() throws IOException, InterruptedException {
        if (!isEnabled()) {
            throw new IOException("The webhook is disabled in config");
        }
        JsonObject payload = buildPayload("Webhook test",
                "This is a test from the maintenance plugin. If you can read this, announcements will "
                        + "arrive here.", 0x5865F2);
        // Deliberately posted rather than edited, so a test never overwrites a
        // live status message that people are reading.
        JsonObject copy = payload.deepCopy();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(copy), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Discord returned status " + response.statusCode());
        }
    }

    private JsonObject buildPayload(String title, String description, int colour) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", title);
        embed.addProperty("description", description);
        embed.addProperty("color", colour);
        embed.addProperty("timestamp", Instant.now().toString());

        JsonArray embeds = new JsonArray();
        embeds.add(embed);

        JsonObject payload = new JsonObject();
        payload.add("embeds", embeds);
        if (config.username != null && !config.username.isBlank()) {
            payload.addProperty("username", config.username);
        }
        return payload;
    }

    private void post(JsonObject payload) throws IOException, InterruptedException {
        // wait=true makes Discord return the created message, which is the only
        // way to learn its id and therefore the only way to edit it later.
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.url + (config.url.contains("?") ? "&" : "?") + "wait=true"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Discord returned status " + response.statusCode());
        }
        messageId = extractId(response.body()).orElse(null);
    }

    private boolean edit(String id, JsonObject payload) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.url + "/messages/" + id))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() / 100 == 2;
    }

    private static Optional<String> extractId(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            return json.has("id") ? Optional.of(json.get("id").getAsString()) : Optional.empty();
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }
}

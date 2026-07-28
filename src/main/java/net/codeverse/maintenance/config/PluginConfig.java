package net.codeverse.maintenance.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Configuration, written with defaults on first start and merged forward on
 * upgrade so an operator never loses settings to a new release.
 *
 * Validation refuses to start on a value that would be dangerous rather than
 * merely wrong, and says what the danger is. The one that matters most here is
 * the break glass list: a maintenance plugin with no way back in is a plugin
 * that can lock an operator out of their own network.
 */
public final class PluginConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public Gate gate = new Gate();
    public Motd motd = new Motd();
    public Hold hold = new Hold();
    public Webhook webhook = new Webhook();
    public Language language = new Language();
    public net.codeverse.maintenance.http.HttpControlConfig http =
            new net.codeverse.maintenance.http.HttpControlConfig();

    public static final class Gate {
        /**
         * Premium account uuids that may always pass, read without touching the
         * database.
         *
         * This is the break glass. The scenario that most often calls for
         * maintenance is a database migration, and if the allowlist only
         * resolved through the accounts table then a database outage would lock
         * the operator out of their own network with no way back in short of
         * editing files on the host. These uuids are verified by Mojang at
         * login, so they are trustworthy with no database at all.
         */
        public List<String> breakGlassUuids = new ArrayList<>();

        /**
         * Permission that lets a player through. Note that CRACKED accounts are
         * barred from elevated permissions in the authentication plugin's code,
         * independent of any permission setup, so a cracked member of staff can
         * never satisfy this. For them the explicit allowlist is the only
         * mechanism that works, which is why it is checked first.
         */
        public String bypassPermission = "codeverse.maintenance.bypass";

        /** Server players are held on while they authenticate. Must stay reachable. */
        public String limboServer = "limbo";

        /** Where a released player is sent when the network reopens. */
        public String lobbyServer = "lobby";
    }

    public static final class Motd {
        public boolean enabled = true;
        /**
         * Whether to greet a returning connection by name.
         *
         * A server list ping carries no username, only an address, so this
         * works by remembering which identity last connected from an address.
         * It is the difference between a generic notice and being told you are
         * on the list before you try to join.
         */
        public boolean rememberAddresses = true;
        /** How long a remembered address stays associated with an identity. */
        public int addressMemoryHours = 168;
        /** Shown in the player count slot while closed, in place of a real number. */
        public String closedPlayerCount = "Maintenance";
        /** Reported maximum while closed. The count slot is decorative here. */
        public int decoyMaximum = 100;
    }

    public static final class Hold {
        /**
         * Whether players already online are moved to limbo rather than
         * disconnected when a window opens.
         */
        public boolean holdOnClose = true;
        /**
         * Windows longer than this disconnect instead of holding. Nobody waits
         * in a lobby for three hours, and holding them costs limbo capacity
         * that a short restart genuinely benefits from.
         */
        public int holdMaximumMinutes = 15;
        /** Seconds of warning before players are moved or disconnected. */
        public int warningSeconds = 30;
        /**
         * Seconds over which held players are released when the window lifts.
         *
         * Releasing everyone in one tick sends the whole set through
         * authentication at once, and signing in runs Argon2id, which is
         * deliberately expensive. A short stagger turns a spike into a queue
         * nobody notices.
         */
        public int releaseStaggerSeconds = 20;
    }

    public static final class Webhook {
        public boolean enabled = false;
        public String url = "";
        /**
         * Whether to edit one message rather than posting a new one per change.
         *
         * A channel with forty maintenance notices in it stops being read. One
         * message that updates stays useful.
         */
        public boolean editSingleMessage = true;
        public String username = "Codeverse Network";
    }

    public static final class Language {
        public String defaultLocale = "en";
        public boolean usePlayerLocale = true;
    }

    public static PluginConfig load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve("config.json");

        PluginConfig config;
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject onDisk = JsonParser.parseReader(reader).getAsJsonObject();
                // Merged forward rather than replaced: a release that adds a
                // setting must not silently discard the operator's others.
                JsonObject merged = merge(GSON.toJsonTree(new PluginConfig()).getAsJsonObject(), onDisk);
                config = GSON.fromJson(merged, PluginConfig.class);
            }
        } else {
            config = new PluginConfig();
        }

        // Generated here rather than at bind time so an operator can read it
        // out of config.json to configure the bot, and so enabling the
        // interface never leaves it running with a blank credential.
        if (config.http.enabled && (config.http.token == null || config.http.token.isBlank())) {
            config.http.token = net.codeverse.maintenance.http.ControlAuthenticator.generateToken();
        }

        config.validate();
        config.write(file);
        return config;
    }

    private static JsonObject merge(JsonObject defaults, JsonObject onDisk) {
        JsonObject result = new JsonObject();
        for (String key : defaults.keySet()) {
            if (onDisk.has(key) && defaults.get(key).isJsonObject() && onDisk.get(key).isJsonObject()) {
                result.add(key, merge(defaults.getAsJsonObject(key), onDisk.getAsJsonObject(key)));
            } else if (onDisk.has(key)) {
                result.add(key, onDisk.get(key));
            } else {
                result.add(key, defaults.get(key));
            }
        }
        return result;
    }

    private void write(Path file) throws IOException {
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        }
    }

    public void validate() {
        if (gate.limboServer == null || gate.limboServer.isBlank()) {
            throw new IllegalStateException("gate.limboServer cannot be blank. Players must have somewhere "
                    + "to authenticate, or nobody can prove they belong on the allowlist and the network "
                    + "closes to everyone including you.");
        }
        if (gate.lobbyServer == null || gate.lobbyServer.isBlank()) {
            throw new IllegalStateException("gate.lobbyServer cannot be blank. Held players need somewhere "
                    + "to be released to when the window lifts.");
        }
        if (gate.limboServer.equals(gate.lobbyServer)) {
            throw new IllegalStateException("gate.limboServer and gate.lobbyServer cannot be the same "
                    + "server. Releasing a held player would send them back where they already are.");
        }
        if (gate.bypassPermission == null || gate.bypassPermission.isBlank()) {
            throw new IllegalStateException("gate.bypassPermission cannot be blank");
        }
        for (String raw : gate.breakGlassUuids) {
            try {
                UUID.fromString(raw);
            } catch (IllegalArgumentException malformed) {
                throw new IllegalStateException("gate.breakGlassUuids contains '" + raw + "', which is not "
                        + "a uuid. This list is the way back in when the database is unreachable, so a "
                        + "typo here is only discovered at the worst moment.");
            }
        }
        if (motd.addressMemoryHours < 1) {
            throw new IllegalStateException("motd.addressMemoryHours must be at least 1");
        }
        if (hold.holdMaximumMinutes < 0) {
            throw new IllegalStateException("hold.holdMaximumMinutes cannot be negative");
        }
        if (hold.warningSeconds < 0) {
            throw new IllegalStateException("hold.warningSeconds cannot be negative");
        }
        if (hold.releaseStaggerSeconds < 0) {
            throw new IllegalStateException("hold.releaseStaggerSeconds cannot be negative");
        }
        if (webhook.enabled && (webhook.url == null || webhook.url.isBlank())) {
            throw new IllegalStateException("webhook.enabled is true but webhook.url is blank");
        }
        http.validate();
        if (webhook.enabled && !webhook.url.startsWith("https://")) {
            throw new IllegalStateException("webhook.url must be https, so the announcement and anything "
                    + "in it does not travel in the clear");
        }
    }

    /** The break glass uuids, parsed. Malformed entries are impossible past validation. */
    public List<UUID> breakGlass() {
        List<UUID> parsed = new ArrayList<>(gate.breakGlassUuids.size());
        for (String raw : gate.breakGlassUuids) {
            parsed.add(UUID.fromString(raw));
        }
        return parsed;
    }
}

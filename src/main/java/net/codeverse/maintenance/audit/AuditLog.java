package net.codeverse.maintenance.audit;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Who closed the network, when, why, and from where.
 *
 * Append only and separate from the state file, because the state answers what
 * is true now and this answers how it got that way. The question that makes it
 * worth keeping is the one asked days later: why was the network down at three
 * in the morning on Tuesday. Without a record the honest answer is that nobody
 * remembers.
 *
 * Written as one line per entry so it can be read with tail and grep during an
 * incident, when nobody wants to parse a structured log.
 */
public final class AuditLog {

    /** Where the change came from, which matters when several people can make it. */
    public enum Source {
        PLAYER,
        CONSOLE,
        SCHEDULE,
        REMOTE
    }

    private final Path file;
    private final Logger logger;

    public AuditLog(Path dataDirectory, Logger logger) throws IOException {
        Files.createDirectories(dataDirectory);
        this.file = dataDirectory.resolve("audit.log");
        this.logger = logger;
    }

    public void record(Source source, String actor, UUID actorIdentity, String action, String detail) {
        String line = String.join(" | ",
                Instant.now().toString(),
                source.name(),
                actor == null ? "-" : actor,
                actorIdentity == null ? "-" : actorIdentity.toString(),
                action,
                detail == null ? "" : detail.replace('\n', ' '));
        try {
            Files.write(file, List.of(line), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            // A failure to record must not prevent the action itself. The log
            // exists to explain what happened, not to gate it.
            logger.warn("Could not append to the maintenance audit log: {}", failure.getMessage());
        }
        logger.info("[audit] {}", line);
    }

    /** The most recent entries, newest last, for the status view. */
    public List<String> tail(int limit) {
        try {
            if (!Files.exists(file)) {
                return List.of();
            }
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            return all.size() <= limit ? all : all.subList(all.size() - limit, all.size());
        } catch (IOException failure) {
            return List.of();
        }
    }
}

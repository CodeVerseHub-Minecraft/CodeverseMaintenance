package net.codeverse.maintenance.util;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Durations as text, and text as durations.
 *
 * Everything player facing is relative rather than absolute, deliberately. This
 * community spans enough timezones that an absolute time in a message is wrong
 * for most of the people reading it, and getting it right would mean knowing
 * each player's zone, which a server list ping cannot tell us. A countdown is
 * correct everywhere and needs no configuration.
 */
public final class Durations {

    private Durations() {
    }

    /**
     * Reads a moment, either relative or absolute.
     *
     * A relative form such as 3d is timezone free by construction and is what
     * an operator usually means. An absolute form must carry an explicit zone,
     * so 2026-03-15T18:00:00Z is accepted and a bare local time is not: a
     * launch instant written without a zone is ambiguous exactly once, on the
     * day it matters.
     */
    public static Optional<java.time.Instant> parseMoment(String raw, java.time.Instant now) {
        Optional<Duration> relative = parse(raw);
        if (relative.isPresent()) {
            return Optional.of(now.plus(relative.get()));
        }
        try {
            return Optional.of(java.time.Instant.parse(raw));
        } catch (java.time.format.DateTimeParseException notAnInstant) {
            return Optional.empty();
        }
    }

    /** A short human description: 2d 4h, 90m, 45s. */
    public static String describe(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return "moments";
        }
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();

        if (days > 0) {
            return hours > 0 ? days + "d " + hours + "h" : days + "d";
        }
        if (hours > 0) {
            return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
        }
        if (minutes > 0) {
            return minutes + "m";
        }
        return seconds + "s";
    }

    /**
     * Parses 30m, 2h, 1d and combinations. Empty when the text is not a
     * duration, which the caller reports rather than guessing at a default:
     * a mistyped duration that silently became an hour would be worse than
     * being told to try again.
     */
    public static Optional<Duration> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        Duration total = Duration.ZERO;
        long number = 0;
        boolean sawDigit = false;
        boolean sawUnit = false;

        for (char c : text.toCharArray()) {
            if (Character.isDigit(c)) {
                number = number * 10 + (c - '0');
                sawDigit = true;
                continue;
            }
            if (!sawDigit) {
                return Optional.empty();
            }
            Duration unit = switch (c) {
                case 's' -> Duration.ofSeconds(number);
                case 'm' -> Duration.ofMinutes(number);
                case 'h' -> Duration.ofHours(number);
                case 'd' -> Duration.ofDays(number);
                case 'w' -> Duration.ofDays(number * 7);
                default -> null;
            };
            if (unit == null) {
                return Optional.empty();
            }
            total = total.plus(unit);
            number = 0;
            sawDigit = false;
            sawUnit = true;
        }
        // A bare number with no unit is ambiguous, so it is refused rather
        // than assumed to be minutes.
        if (sawDigit || !sawUnit || total.isZero()) {
            return Optional.empty();
        }
        return Optional.of(total);
    }
}

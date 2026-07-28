package net.codeverse.maintenance.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationsTest {

    @Test
    void parsesSingleUnits() {
        assertEquals(Optional.of(Duration.ofSeconds(45)), Durations.parse("45s"));
        assertEquals(Optional.of(Duration.ofMinutes(30)), Durations.parse("30m"));
        assertEquals(Optional.of(Duration.ofHours(2)), Durations.parse("2h"));
        assertEquals(Optional.of(Duration.ofDays(1)), Durations.parse("1d"));
        assertEquals(Optional.of(Duration.ofDays(14)), Durations.parse("2w"));
    }

    @Test
    void parsesCombinations() {
        assertEquals(Optional.of(Duration.ofMinutes(90)), Durations.parse("1h30m"));
        assertEquals(Optional.of(Duration.ofHours(25)), Durations.parse("1d1h"));
    }

    /**
     * A bare number is refused rather than assumed to be minutes. Someone
     * typing 30 meaning half an hour and getting thirty seconds would be a
     * maintenance window that ends while they are still working.
     */
    @Test
    void refusesAmbiguousOrMalformedInput() {
        assertEquals(Optional.empty(), Durations.parse("30"));
        assertEquals(Optional.empty(), Durations.parse("soon"));
        assertEquals(Optional.empty(), Durations.parse("m30"));
        assertEquals(Optional.empty(), Durations.parse("0m"));
        assertEquals(Optional.empty(), Durations.parse(""));
        assertEquals(Optional.empty(), Durations.parse(null));
    }

    @Test
    void describesTheLargestMeaningfulUnits() {
        assertEquals("45s", Durations.describe(Duration.ofSeconds(45)));
        assertEquals("30m", Durations.describe(Duration.ofMinutes(30)));
        assertEquals("2h 30m", Durations.describe(Duration.ofMinutes(150)));
        assertEquals("2h", Durations.describe(Duration.ofHours(2)));
        assertEquals("3d 4h", Durations.describe(Duration.ofHours(76)));
        assertEquals("3d", Durations.describe(Duration.ofDays(3)));
    }

    @Test
    void aLapsedDurationReadsAsMomentsRatherThanNegative() {
        assertEquals("moments", Durations.describe(Duration.ofSeconds(-5)));
        assertEquals("moments", Durations.describe(Duration.ZERO));
        assertEquals("moments", Durations.describe(null));
    }
}

package com.reactor.cachedb.core.readmodel;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CountFacetDefinitionTest {
    @Test void acceptsAsciiNamesAndInclusiveLifetimeLimits() {
        for (String name : new String[] {"a", "0", "Model.1_2-3", "a".repeat(96)}) {
            var definition = new CountFacetDefinition(name, Duration.ofDays(7), Duration.ofDays(7), Duration.ofMinutes(5));
            assertEquals(name, definition.name());
        }
        assertDoesNotThrow(() -> definition(Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(1)));
    }

    @Test void rejectsInvalidNamesWithoutNormalizingThem() {
        for (String name : new String[] {"", ".a", "_a", "-a", "a b", "a/b", "a:b", "a\n", "a".repeat(97), "\u00e9"})
            assertThrows(IllegalArgumentException.class,
                    () -> new CountFacetDefinition(name, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class,
                () -> new CountFacetDefinition(null, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1)));
    }

    @Test void rejectsZeroNegativeAndFractionalMillisInEveryDuration() {
        for (Duration invalid : new Duration[] {Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1),
                Duration.ofNanos(999_999), Duration.ofMillis(1).plusNanos(1), Duration.ofSeconds(-1).plusNanos(1)}) {
            assertThrows(IllegalArgumentException.class, () -> definition(invalid, Duration.ofDays(1), Duration.ofSeconds(1)));
            assertThrows(IllegalArgumentException.class, () -> definition(Duration.ofMillis(1), invalid, Duration.ofSeconds(1)));
            assertThrows(IllegalArgumentException.class, () -> definition(Duration.ofMillis(1), Duration.ofDays(1), invalid));
        }
    }

    @Test void rejectsNullDurations() {
        assertThrows(NullPointerException.class, () -> definition(null, Duration.ofDays(1), Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> definition(Duration.ofMillis(1), null, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> definition(Duration.ofMillis(1), Duration.ofDays(1), null));
    }

    @Test void rejectsInvertedAndOversizedLifetimesWithoutConversionOverflow() {
        assertThrows(IllegalArgumentException.class,
                () -> definition(Duration.ofMillis(2), Duration.ofMillis(1), Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class,
                () -> definition(Duration.ofMillis(1), Duration.ofDays(7).plusMillis(1), Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class,
                () -> definition(Duration.ofMillis(1), Duration.ofDays(1), Duration.ofMinutes(5).plusMillis(1)));
        Duration huge = Duration.ofSeconds(Long.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> definition(huge, huge, Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class, () -> definition(Duration.ofMillis(1), Duration.ofDays(1), huge));
    }

    private static CountFacetDefinition definition(Duration age, Duration retention, Duration lease) {
        return new CountFacetDefinition("model", age, retention, lease);
    }
}

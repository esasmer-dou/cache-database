package com.reactor.cachedb.core.readmodel;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CountFacetSnapshotTest {
    private static final CountFacetValues VALUES = new CountFacetValues(0, Map.of());

    @Test void acceptsEqualAndOrderedObservationTimes() {
        var snapshot = new CountFacetSnapshot(1, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, VALUES);
        assertSame(VALUES, snapshot.values());
        assertDoesNotThrow(() -> new CountFacetSnapshot(Long.MAX_VALUE, Instant.MIN, Instant.EPOCH, Instant.MAX, VALUES));
    }

    @Test void rejectsNonpositiveGenerations() {
        for (long generation : new long[] {0, -1, Long.MIN_VALUE})
            assertThrows(IllegalArgumentException.class,
                    () -> new CountFacetSnapshot(generation, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, VALUES));
    }

    @Test void rejectsInvertedObservationAndPublicationTimes() {
        assertThrows(IllegalArgumentException.class,
                () -> new CountFacetSnapshot(1, Instant.EPOCH.plusNanos(1), Instant.EPOCH, Instant.EPOCH, VALUES));
        assertThrows(IllegalArgumentException.class,
                () -> new CountFacetSnapshot(1, Instant.EPOCH, Instant.EPOCH.plusNanos(1), Instant.EPOCH, VALUES));
    }

    @Test void rejectsEveryNullComponent() {
        assertThrows(NullPointerException.class, () -> new CountFacetSnapshot(1, null, Instant.EPOCH, Instant.EPOCH, VALUES));
        assertThrows(NullPointerException.class, () -> new CountFacetSnapshot(1, Instant.EPOCH, null, Instant.EPOCH, VALUES));
        assertThrows(NullPointerException.class, () -> new CountFacetSnapshot(1, Instant.EPOCH, Instant.EPOCH, null, VALUES));
        assertThrows(NullPointerException.class, () -> new CountFacetSnapshot(1, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, null));
    }
}

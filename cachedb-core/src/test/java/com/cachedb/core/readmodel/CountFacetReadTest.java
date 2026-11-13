package com.reactor.cachedb.core.readmodel;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CountFacetReadTest {
    private static final CountFacetSnapshot SNAPSHOT = new CountFacetSnapshot(1, Instant.EPOCH,
            Instant.EPOCH, Instant.EPOCH, new CountFacetValues(0, Map.of()));

    @Test void acceptsOnlyMissingWithoutSnapshotAndPresentFreshOrStale() {
        assertTrue(new CountFacetRead(CountFacetRead.Status.MISSING, Instant.EPOCH, Optional.empty()).snapshot().isEmpty());
        for (var status : new CountFacetRead.Status[] {CountFacetRead.Status.FRESH, CountFacetRead.Status.STALE}) {
            assertSame(SNAPSHOT, new CountFacetRead(status, Instant.EPOCH, Optional.of(SNAPSHOT)).snapshot().orElseThrow());
            assertThrows(IllegalArgumentException.class, () -> new CountFacetRead(status, Instant.EPOCH, Optional.empty()));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new CountFacetRead(CountFacetRead.Status.MISSING, Instant.EPOCH, Optional.of(SNAPSHOT)));
    }

    @Test void rejectsChecksBeforePublicationForEitherPresentStatus() {
        for (var status : new CountFacetRead.Status[] {CountFacetRead.Status.FRESH, CountFacetRead.Status.STALE})
            assertThrows(IllegalArgumentException.class,
                    () -> new CountFacetRead(status, Instant.EPOCH.minusNanos(1), Optional.of(SNAPSHOT)));
    }

    @Test void rejectsEveryNullComponent() {
        assertThrows(NullPointerException.class, () -> new CountFacetRead(null, Instant.EPOCH, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new CountFacetRead(CountFacetRead.Status.MISSING, null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new CountFacetRead(CountFacetRead.Status.MISSING, Instant.EPOCH, null));
    }
}

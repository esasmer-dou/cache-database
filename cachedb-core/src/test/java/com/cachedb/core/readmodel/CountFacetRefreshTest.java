package com.reactor.cachedb.core.readmodel;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CountFacetRefreshTest {
    private static final CountFacetSnapshot SNAPSHOT = new CountFacetSnapshot(1, Instant.EPOCH,
            Instant.EPOCH, Instant.EPOCH, new CountFacetValues(0, Map.of()));

    @Test void onlyPublishedCarriesSnapshot() {
        assertSame(SNAPSHOT, new CountFacetRefresh(CountFacetRefresh.Status.PUBLISHED,
                Optional.of(SNAPSHOT)).snapshot().orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> new CountFacetRefresh(CountFacetRefresh.Status.PUBLISHED, Optional.empty()));
        for (var status : new CountFacetRefresh.Status[] {CountFacetRefresh.Status.BUSY, CountFacetRefresh.Status.LEASE_LOST}) {
            assertTrue(new CountFacetRefresh(status, Optional.empty()).snapshot().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> new CountFacetRefresh(status, Optional.of(SNAPSHOT)));
        }
    }

    @Test void rejectsEveryNullComponent() {
        assertThrows(NullPointerException.class, () -> new CountFacetRefresh(null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new CountFacetRefresh(CountFacetRefresh.Status.BUSY, null));
    }
}

package com.reactor.cachedb.spring.boot.test;

import com.reactor.cachedb.core.repository.HotLookup;
import com.reactor.cachedb.core.repository.HotWindow;
import com.reactor.cachedb.core.route.RouteCoverage;
import com.reactor.cachedb.core.route.RouteCoverageStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CacheDbAssertionsTest {

    @Test
    void shouldExposeEveryNonHitStateWithoutCollapsingThemToMissing() {
        CacheDbAssertions.requireNotCached(HotLookup.notCached());
        CacheDbAssertions.requireTombstoned(HotLookup.tombstoned());
        CacheDbAssertions.requireOutsideHotPolicy(HotLookup.outsidePolicy());

        assertThrows(AssertionError.class, () -> CacheDbAssertions.requireNotCached(HotLookup.tombstoned()));
    }

    @Test
    void shouldRequireCompleteRouteEvidence() {
        RouteCoverage complete = coverage(RouteCoverageStatus.COMPLETE);
        HotWindow<String> window = new HotWindow<>(List.of("ready"), null, complete);

        assertEquals(window, CacheDbAssertions.requireComplete(window));
        assertEquals(complete, CacheDbAssertions.requireComplete(complete));
        assertThrows(
                AssertionError.class,
                () -> CacheDbAssertions.requireComplete(coverage(RouteCoverageStatus.STALE))
        );
    }

    private RouteCoverage coverage(RouteCoverageStatus status) {
        return new RouteCoverage(
                "test-route",
                "global",
                status,
                1,
                1,
                Instant.EPOCH,
                Instant.EPOCH,
                ""
        );
    }
}

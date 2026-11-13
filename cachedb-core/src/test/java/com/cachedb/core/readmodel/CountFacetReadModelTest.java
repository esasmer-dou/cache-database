package com.reactor.cachedb.core.readmodel;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class CountFacetReadModelTest {
    private static final CountFacetDefinition DEFINITION = new CountFacetDefinition("model", Duration.ofMillis(10),
            Duration.ofSeconds(1), Duration.ofSeconds(1));
    private static final CountFacetSnapshot SNAPSHOT = new CountFacetSnapshot(1, Instant.EPOCH,
            Instant.EPOCH.plusMillis(9), Instant.EPOCH.plusMillis(9), new CountFacetValues(1, Map.of("a", 1L)));

    @Test void requireFreshInspectsOnceAndReturnsExactSnapshotWithoutRefreshing() {
        var model = new Stub(new CountFacetRead(CountFacetRead.Status.FRESH, Instant.EPOCH.plusMillis(9), Optional.of(SNAPSHOT)));
        assertSame(SNAPSHOT, model.requireFresh());
        assertEquals(1, model.inspections);
    }

    @Test void requireFreshRejectsMissingAndStaleWithoutRefreshing() {
        for (var read : new CountFacetRead[] {
                new CountFacetRead(CountFacetRead.Status.MISSING, Instant.EPOCH, Optional.empty()),
                new CountFacetRead(CountFacetRead.Status.STALE, Instant.EPOCH.plusMillis(10), Optional.of(SNAPSHOT))}) {
            var model = new Stub(read);
            assertThrows(IllegalStateException.class, model::requireFresh);
            assertEquals(1, model.inspections);
        }
    }

    @Test void freshnessStartsAtObservationNotRecentPublicationAndExcludesExactMaxAge() {
        var model = new Stub(new CountFacetRead(CountFacetRead.Status.FRESH,
                Instant.EPOCH.plusMillis(10), Optional.of(SNAPSHOT)));
        assertThrows(IllegalStateException.class, model::requireFresh);
        var later = new Stub(new CountFacetRead(CountFacetRead.Status.FRESH,
                Instant.EPOCH.plusMillis(11), Optional.of(SNAPSHOT)));
        assertThrows(IllegalStateException.class, later::requireFresh);
    }

    @Test void freshAgeComparisonDoesNotTruncateSubmillisecondAgeOrOverflow() {
        var justFresh = new Stub(new CountFacetRead(CountFacetRead.Status.FRESH,
                Instant.EPOCH.plusMillis(10).minusNanos(1), Optional.of(SNAPSHOT)));
        assertSame(SNAPSHOT, justFresh.requireFresh());
        var ancient = new CountFacetSnapshot(1, Instant.MIN, Instant.EPOCH, Instant.EPOCH, SNAPSHOT.values());
        var model = new Stub(new CountFacetRead(CountFacetRead.Status.FRESH, Instant.MAX, Optional.of(ancient)));
        assertThrows(IllegalStateException.class, model::requireFresh);
    }

    @Test void inspectionFailurePropagatesUnchangedWithoutRetry() {
        var failure = new IllegalArgumentException("storage failure");
        var model = new Stub(null) {
            @Override public CountFacetRead inspect() {
                inspections++;
                throw failure;
            }
        };
        assertSame(failure, assertThrows(IllegalArgumentException.class, model::requireFresh));
        assertEquals(1, model.inspections);
    }

    @Test void rejectsNullInspectionAndNullFreshDefinition() {
        assertThrows(NullPointerException.class, new Stub(null)::requireFresh);
        var model = new Stub(new CountFacetRead(CountFacetRead.Status.FRESH,
                Instant.EPOCH.plusMillis(9), Optional.of(SNAPSHOT))) {
            @Override public CountFacetDefinition definition() { return null; }
        };
        assertThrows(NullPointerException.class, model::requireFresh);
    }

    private static class Stub implements CountFacetReadModel {
        private final CountFacetRead read;
        int inspections;

        Stub(CountFacetRead read) { this.read = read; }
        @Override public CountFacetDefinition definition() { return DEFINITION; }
        @Override public CountFacetRead inspect() { inspections++; return read; }
        @Override public CountFacetRefresh refresh(Supplier<CountFacetValues> supplier) {
            throw new AssertionError("requireFresh must never refresh or invoke a supplier");
        }
    }
}

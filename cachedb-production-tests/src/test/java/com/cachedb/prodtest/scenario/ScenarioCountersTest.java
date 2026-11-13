package com.reactor.cachedb.prodtest.scenario;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioCountersTest {
    @Test
    void failuresAreAttemptsButNeverSuccessfulThroughput() {
        var clock = new AtomicLong();
        var counters = new ScenarioCounters(clock::get);
        counters.record(false, 12_000_000L, 0L);
        counters.record(false, 20L, 0L);
        clock.set(1_000_000_000L);
        counters.finish();
        assertEquals(2L, counters.executedOperations);
        assertEquals(2L, counters.failedOperations);
        assertEquals(0.0d, counters.achievedTransactionsPerSecond());
        assertEquals(2.0d, counters.attemptedOperationsPerSecond());
        assertEquals(0L, counters.successful.count);
        assertEquals(2L, counters.failed.count);
    }

    @Test
    void mixedResultsKeepSeparateLatencyAndUseMonotonicTime() {
        var clock = new AtomicLong(7L);
        var counters = new ScenarioCounters(clock::get);
        counters.record(true, 100L, 50L);
        counters.record(false, 1_000L, 100L);
        counters.record(true, 200L, 200L);
        clock.addAndGet(2_000_000_000L);
        counters.finish();
        clock.addAndGet(9_000_000_000L);
        assertEquals(2_000L, counters.elapsedMillis());
        assertEquals(1.0d, counters.achievedTransactionsPerSecond());
        assertEquals(1.5d, counters.attemptedOperationsPerSecond());
        assertEquals(150L, counters.successful.average());
        assertEquals(1_000L, counters.failed.average());
        assertEquals(433L, counters.averageLatencyMicros());
        assertEquals(250L, counters.scheduledStartLag.percentileUpperBound(0.99d));
    }

    @Test
    void overflowUsesObservedMaximumRatherThanClippingToFiveSeconds() {
        var values = new ScenarioCounters.Latencies();
        values.record(12_000_000L);
        values.record(30_000_000L);
        assertEquals(30_000_000L, values.percentileUpperBound(0.95d));
        assertEquals(30_000_000L, values.percentileUpperBound(0.99d));
        assertEquals(2L, values.overflow);
        assertTrue(values.usesOverflowMaximum(0.95d));
        assertTrue(values.json().contains("\"p99UsesOverflowMaximum\":true"));
    }

    @Test
    void bucketBoundariesAndMixedOverflowRemainConservative() {
        var values = new ScenarioCounters.Latencies();
        for (int i = 0; i < 99; i++) values.record(50L);
        values.record(12_000_000L);
        assertEquals(50L, values.percentileUpperBound(0.99d));
        assertFalse(values.usesOverflowMaximum(0.99d));
        assertEquals(12_000_000L, values.percentileUpperBound(1.0d));
        assertTrue(values.usesOverflowMaximum(1.0d));
    }

    @Test
    void emptyPopulationIsExplicitAndInvalidInputsFail() {
        var values = new ScenarioCounters.Latencies();
        assertEquals(0L, values.percentileUpperBound(0.95d));
        assertEquals(0L, values.average());
        assertFalse(values.usesOverflowMaximum(0.99d));
        assertThrows(IllegalArgumentException.class, () -> values.record(-1L));
        assertThrows(IllegalArgumentException.class, () -> values.percentileUpperBound(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> values.percentileUpperBound(0.0d));
        assertThrows(IllegalArgumentException.class, () -> values.percentileUpperBound(1.1d));
    }

    @Test
    void workerMergePreservesCountsAndOverflowWithoutSharedHotPathAtomics() throws Exception {
        var clock = new AtomicLong();
        var aggregate = new ScenarioCounters(clock::get);
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                var worker = new ScenarioCounters();
                for (int j = 0; j < 1_000; j++) {
                    worker.record(j % 2 == 0, j % 2 == 0 ? 100L : 12_000_000L, 10L);
                }
                worker.readOperations = 500L;
                aggregate.merge(worker);
            });
            threads[i].start();
        }
        for (Thread thread : threads) thread.join();
        clock.set(1_000_000_000L);
        aggregate.finish();
        assertEquals(8_000L, aggregate.executedOperations);
        assertEquals(4_000L, aggregate.readOperations);
        assertEquals(4_000L, aggregate.failedOperations);
        assertEquals(4_000.0d, aggregate.achievedTransactionsPerSecond());
        assertEquals(12_000_000L, aggregate.p99LatencyMicros());
        assertTrue(aggregate.measurementJson().contains("\"successfulOperations\":4000"));
        assertTrue(aggregate.measurementMarkdown().contains("not an open-loop"));
    }
}

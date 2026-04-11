package com.reactor.cachedb.core.queue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class StoragePerformanceCollector {

    private static final int SAMPLE_LIMIT = 256;
    private static final StoragePerformanceCollector NO_OP = new NoOpStoragePerformanceCollector();

    private final LatencyAccumulator redisRead = new LatencyAccumulator();
    private final LatencyAccumulator redisWrite = new LatencyAccumulator();
    private final LatencyAccumulator postgresRead = new LatencyAccumulator();
    private final LatencyAccumulator postgresWrite = new LatencyAccumulator();
    private final ConcurrentHashMap<String, LatencyAccumulator> redisReadBreakdown = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LatencyAccumulator> redisWriteBreakdown = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LatencyAccumulator> postgresReadBreakdown = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LatencyAccumulator> postgresWriteBreakdown = new ConcurrentHashMap<>();

    public static StoragePerformanceCollector noop() {
        return NO_OP;
    }

    public void recordRedisRead(long elapsedMicros) {
        record(redisRead, redisReadBreakdown, PerformanceObservationContext.currentTag(), elapsedMicros);
    }

    public void recordRedisRead(String tag, long elapsedMicros) {
        record(redisRead, redisReadBreakdown, tag, elapsedMicros);
    }

    public void recordRedisWrite(long elapsedMicros) {
        record(redisWrite, redisWriteBreakdown, PerformanceObservationContext.currentTag(), elapsedMicros);
    }

    public void recordRedisWrite(String tag, long elapsedMicros) {
        record(redisWrite, redisWriteBreakdown, tag, elapsedMicros);
    }

    public void recordPostgresRead(long elapsedMicros) {
        record(postgresRead, postgresReadBreakdown, PerformanceObservationContext.currentTag(), elapsedMicros);
    }

    public void recordPostgresRead(String tag, long elapsedMicros) {
        record(postgresRead, postgresReadBreakdown, tag, elapsedMicros);
    }

    public void recordPostgresWrite(long elapsedMicros) {
        record(postgresWrite, postgresWriteBreakdown, PerformanceObservationContext.currentTag(), elapsedMicros);
    }

    public void recordPostgresWrite(String tag, long elapsedMicros) {
        record(postgresWrite, postgresWriteBreakdown, tag, elapsedMicros);
    }

    public StoragePerformanceSnapshot snapshot() {
        return new StoragePerformanceSnapshot(
                redisRead.snapshot(),
                redisWrite.snapshot(),
                postgresRead.snapshot(),
                postgresWrite.snapshot(),
                snapshot(redisReadBreakdown),
                snapshot(redisWriteBreakdown),
                snapshot(postgresReadBreakdown),
                snapshot(postgresWriteBreakdown)
        );
    }

    public StoragePerformanceSnapshot reset() {
        StoragePerformanceSnapshot snapshot = snapshot();
        redisRead.clear();
        redisWrite.clear();
        postgresRead.clear();
        postgresWrite.clear();
        clear(redisReadBreakdown);
        clear(redisWriteBreakdown);
        clear(postgresReadBreakdown);
        clear(postgresWriteBreakdown);
        return snapshot;
    }

    public boolean enabled() {
        return true;
    }

    private void record(
            LatencyAccumulator aggregate,
            ConcurrentHashMap<String, LatencyAccumulator> breakdown,
            String tag,
            long elapsedMicros
    ) {
        aggregate.record(elapsedMicros);
        String normalized = normalizeTag(tag);
        if (!normalized.isBlank()) {
            breakdown.computeIfAbsent(normalized, ignored -> new LatencyAccumulator()).record(elapsedMicros);
        }
    }

    private Map<String, LatencyMetricSnapshot> snapshot(ConcurrentHashMap<String, LatencyAccumulator> accumulators) {
        ArrayList<String> keys = new ArrayList<>(accumulators.keySet());
        Collections.sort(keys);
        LinkedHashMap<String, LatencyMetricSnapshot> snapshot = new LinkedHashMap<>();
        for (String key : keys) {
            snapshot.put(key, accumulators.get(key).snapshot());
        }
        return Map.copyOf(snapshot);
    }

    private void clear(ConcurrentHashMap<String, LatencyAccumulator> accumulators) {
        for (LatencyAccumulator accumulator : accumulators.values()) {
            accumulator.clear();
        }
        accumulators.clear();
    }

    private String normalizeTag(String tag) {
        return tag == null ? "" : tag.trim();
    }

    private static final class LatencyAccumulator {
        private final AtomicLong operationCount = new AtomicLong();
        private final AtomicLong totalMicros = new AtomicLong();
        private final AtomicLong maxMicros = new AtomicLong();
        private final AtomicLong lastMicros = new AtomicLong();
        private final AtomicLong lastObservedAtEpochMillis = new AtomicLong();
        private final ArrayDeque<Long> samples = new ArrayDeque<>();

        void record(long elapsedMicros) {
            long normalized = Math.max(0L, elapsedMicros);
            operationCount.incrementAndGet();
            totalMicros.addAndGet(normalized);
            maxMicros.accumulateAndGet(normalized, Math::max);
            lastMicros.set(normalized);
            lastObservedAtEpochMillis.set(System.currentTimeMillis());
            synchronized (samples) {
                samples.addLast(normalized);
                while (samples.size() > SAMPLE_LIMIT) {
                    samples.removeFirst();
                }
            }
        }

        LatencyMetricSnapshot snapshot() {
            long count = operationCount.get();
            if (count == 0L) {
                return LatencyMetricSnapshot.empty();
            }
            List<Long> sortedSamples;
            synchronized (samples) {
                sortedSamples = new ArrayList<>(samples);
            }
            Collections.sort(sortedSamples);
            return new LatencyMetricSnapshot(
                    count,
                    totalMicros.get() / Math.max(1L, count),
                    percentile(sortedSamples, 0.95d),
                    percentile(sortedSamples, 0.99d),
                    maxMicros.get(),
                    lastMicros.get(),
                    lastObservedAtEpochMillis.get()
            );
        }

        void clear() {
            operationCount.set(0L);
            totalMicros.set(0L);
            maxMicros.set(0L);
            lastMicros.set(0L);
            lastObservedAtEpochMillis.set(0L);
            synchronized (samples) {
                samples.clear();
            }
        }

        private long percentile(List<Long> values, double percentile) {
            if (values.isEmpty()) {
                return 0L;
            }
            int index = (int) Math.ceil(percentile * values.size()) - 1;
            int bounded = Math.max(0, Math.min(values.size() - 1, index));
            return values.get(bounded);
        }
    }

    private static final class NoOpStoragePerformanceCollector extends StoragePerformanceCollector {
        private static final StoragePerformanceSnapshot EMPTY = new StoragePerformanceSnapshot(
                LatencyMetricSnapshot.empty(),
                LatencyMetricSnapshot.empty(),
                LatencyMetricSnapshot.empty(),
                LatencyMetricSnapshot.empty()
        );

        @Override
        public void recordRedisRead(long elapsedMicros) {
        }

        @Override
        public void recordRedisRead(String tag, long elapsedMicros) {
        }

        @Override
        public void recordRedisWrite(long elapsedMicros) {
        }

        @Override
        public void recordRedisWrite(String tag, long elapsedMicros) {
        }

        @Override
        public void recordPostgresRead(long elapsedMicros) {
        }

        @Override
        public void recordPostgresRead(String tag, long elapsedMicros) {
        }

        @Override
        public void recordPostgresWrite(long elapsedMicros) {
        }

        @Override
        public void recordPostgresWrite(String tag, long elapsedMicros) {
        }

        @Override
        public StoragePerformanceSnapshot snapshot() {
            return EMPTY;
        }

        @Override
        public StoragePerformanceSnapshot reset() {
            return EMPTY;
        }

        @Override
        public boolean enabled() {
            return false;
        }
    }
}

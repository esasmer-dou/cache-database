package com.reactor.cachedb.examples.demo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public final class DemoScenarioShapeCollector {

    private static final List<String> PRIORITY_ORDER = List.of(
            "hot-product-read",
            "high-line-orders-read",
            "top-customer-orders-read",
            "order-write-burst",
            "order-line-write-burst"
    );

    private final Map<String, ScenarioShapeStats> statsByKey = new ConcurrentHashMap<>();

    public void reset() {
        statsByKey.clear();
    }

    public void recordRead(String key, long primaryCount, long relatedCount, long stepCount, long objectCount) {
        record(key, "read", primaryCount, relatedCount, stepCount, objectCount, 0L);
    }

    public void recordWrite(String key, long primaryCount, long relatedCount, long stepCount, long objectCount, long writeCount) {
        record(key, "write", primaryCount, relatedCount, stepCount, objectCount, writeCount);
    }

    public DemoScenarioShapeSnapshot snapshot() {
        ArrayList<DemoScenarioShapeRecord> items = new ArrayList<>();
        for (Map.Entry<String, ScenarioShapeStats> entry : statsByKey.entrySet()) {
            items.add(entry.getValue().snapshot(entry.getKey()));
        }
        items.sort(Comparator
                .comparingInt((DemoScenarioShapeRecord item) -> priorityIndex(item.key()))
                .thenComparing(DemoScenarioShapeRecord::key));
        return new DemoScenarioShapeSnapshot(List.copyOf(items), System.currentTimeMillis());
    }

    private void record(
            String key,
            String mode,
            long primaryCount,
            long relatedCount,
            long stepCount,
            long objectCount,
            long writeCount
    ) {
        ScenarioShapeStats stats = statsByKey.computeIfAbsent(key, ignored -> new ScenarioShapeStats(mode));
        stats.record(primaryCount, relatedCount, stepCount, objectCount, writeCount);
    }

    private int priorityIndex(String key) {
        int index = PRIORITY_ORDER.indexOf(key);
        return index >= 0 ? index : PRIORITY_ORDER.size();
    }

    private static final class ScenarioShapeStats {
        private final String mode;
        private final LongAdder sampleCount = new LongAdder();
        private final LongAdder totalPrimaryCount = new LongAdder();
        private final LongAdder totalRelatedCount = new LongAdder();
        private final LongAdder totalStepCount = new LongAdder();
        private final LongAdder totalObjectCount = new LongAdder();
        private final LongAdder totalWriteCount = new LongAdder();
        private final AtomicLong lastRecordedAtEpochMillis = new AtomicLong();
        private final AtomicReference<LastSample> lastSample = new AtomicReference<>(new LastSample(0, 0, 0, 0, 0));

        private ScenarioShapeStats(String mode) {
            this.mode = mode;
        }

        private void record(
                long primaryCount,
                long relatedCount,
                long stepCount,
                long objectCount,
                long writeCount
        ) {
            sampleCount.increment();
            totalPrimaryCount.add(primaryCount);
            totalRelatedCount.add(relatedCount);
            totalStepCount.add(stepCount);
            totalObjectCount.add(objectCount);
            totalWriteCount.add(writeCount);
            lastSample.set(new LastSample(primaryCount, relatedCount, stepCount, objectCount, writeCount));
            lastRecordedAtEpochMillis.set(System.currentTimeMillis());
        }

        private DemoScenarioShapeRecord snapshot(String key) {
            long samples = Math.max(1L, sampleCount.sum());
            LastSample last = lastSample.get();
            return new DemoScenarioShapeRecord(
                    key,
                    mode,
                    sampleCount.sum(),
                    last.primaryCount(),
                    totalPrimaryCount.sum() / samples,
                    last.relatedCount(),
                    totalRelatedCount.sum() / samples,
                    last.stepCount(),
                    totalStepCount.sum() / samples,
                    last.objectCount(),
                    totalObjectCount.sum() / samples,
                    last.writeCount(),
                    totalWriteCount.sum() / samples,
                    lastRecordedAtEpochMillis.get()
            );
        }
    }

    private record LastSample(
            long primaryCount,
            long relatedCount,
            long stepCount,
            long objectCount,
            long writeCount
    ) {
    }
}

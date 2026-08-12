package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.starter.CacheHotSetReconciliationResult;
import com.reactor.cachedb.starter.CacheWarmResult;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

public final class CacheScheduledWarmRegistry {

    private final ConcurrentHashMap<String, CacheScheduledWarmSnapshot> snapshots = new ConcurrentHashMap<>();

    public List<CacheScheduledWarmSnapshot> snapshots() {
        return snapshots.values().stream()
                .sorted(Comparator.comparing(CacheScheduledWarmSnapshot::jobName))
                .toList();
    }

    /** Returns the alphabetically first jobs while allocating at most {@code limit} entries. */
    public List<CacheScheduledWarmSnapshot> snapshots(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
        Comparator<CacheScheduledWarmSnapshot> order = Comparator.comparing(CacheScheduledWarmSnapshot::jobName);
        int initialCapacity = Math.max(1, Math.min(limit, snapshots.size()));
        PriorityQueue<CacheScheduledWarmSnapshot> selected = new PriorityQueue<>(initialCapacity, order.reversed());
        for (CacheScheduledWarmSnapshot snapshot : snapshots.values()) {
            if (selected.size() < limit) {
                selected.add(snapshot);
            } else if (order.compare(snapshot, selected.peek()) < 0) {
                selected.poll();
                selected.add(snapshot);
            }
        }
        if (selected.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<CacheScheduledWarmSnapshot> ordered = new java.util.ArrayList<>(selected);
        ordered.sort(order);
        return List.copyOf(ordered);
    }

    public int size() {
        return snapshots.size();
    }

    public CacheScheduledWarmSnapshot snapshot(String jobName) {
        return snapshots.get(jobName);
    }

    public int runningCount() {
        int running = 0;
        for (CacheScheduledWarmSnapshot snapshot : snapshots.values()) {
            if (snapshot.state() == CacheScheduledWarmState.RUNNING) {
                running++;
            }
        }
        return running;
    }

    public long failureCount() {
        return sumCounts(false);
    }

    public long skippedCount() {
        return sumCounts(true);
    }

    void registered(String jobName, String instanceId) {
        snapshots.putIfAbsent(jobName, empty(jobName, instanceId));
    }

    void running(String jobName, String instanceId, Instant startedAt) {
        snapshots.compute(jobName, (ignored, current) -> new CacheScheduledWarmSnapshot(
                jobName,
                CacheScheduledWarmState.RUNNING,
                instanceId,
                current == null ? 0L : current.executionCount(),
                current == null ? 0L : current.skippedCount(),
                current == null ? 0L : current.failureCount(),
                startedAt,
                current == null ? null : current.lastFinishedAt(),
                current == null ? 0L : current.lastDurationMillis(),
                current == null ? 0 : current.lastLoadedRows(),
                current == null ? 0 : current.lastSubmittedRows(),
                current == null ? 0 : current.lastInspectedRows(),
                current == null ? 0 : current.lastEvictedRows(),
                current == null ? 0 : current.lastMissingRows(),
                current == null ? 0 : current.lastInvalidRows(),
                current == null ? "0" : current.lastReconciliationCursor(),
                current != null && current.lastReconciliationCycleCompleted(),
                "Warm execution is running"
        ));
    }

    void completed(
            String jobName,
            String instanceId,
            Instant startedAt,
            Instant finishedAt,
            CacheWarmResult warm,
            CacheHotSetReconciliationResult reconciliation
    ) {
        snapshots.compute(jobName, (ignored, current) -> new CacheScheduledWarmSnapshot(
                jobName,
                CacheScheduledWarmState.COMPLETED,
                instanceId,
                count(current == null ? 0L : current.executionCount()),
                current == null ? 0L : current.skippedCount(),
                current == null ? 0L : current.failureCount(),
                startedAt,
                finishedAt,
                durationMillis(startedAt, finishedAt),
                warm == null ? 0 : warm.loadedRows(),
                warm == null ? 0 : warm.submittedRows(),
                reconciliation == null ? 0 : reconciliation.inspectedRows(),
                reconciliation == null ? 0 : reconciliation.evictedRows(),
                reconciliation == null ? 0 : reconciliation.missingRows(),
                reconciliation == null ? 0 : reconciliation.invalidRows(),
                reconciliation == null ? "0" : reconciliation.nextCursor(),
                reconciliation != null && reconciliation.fullCycleCompleted(),
                reconciliation == null
                        ? "Warm completed"
                        : "Warm and incremental hot-set reconciliation completed"
        ));
    }

    void skipped(String jobName, String instanceId, CacheScheduledWarmState state, String detail) {
        snapshots.compute(jobName, (ignored, current) -> new CacheScheduledWarmSnapshot(
                jobName,
                state,
                instanceId,
                current == null ? 0L : current.executionCount(),
                count(current == null ? 0L : current.skippedCount()),
                current == null ? 0L : current.failureCount(),
                current == null ? null : current.lastStartedAt(),
                Instant.now(),
                current == null ? 0L : current.lastDurationMillis(),
                current == null ? 0 : current.lastLoadedRows(),
                current == null ? 0 : current.lastSubmittedRows(),
                current == null ? 0 : current.lastInspectedRows(),
                current == null ? 0 : current.lastEvictedRows(),
                current == null ? 0 : current.lastMissingRows(),
                current == null ? 0 : current.lastInvalidRows(),
                current == null ? "0" : current.lastReconciliationCursor(),
                current != null && current.lastReconciliationCycleCompleted(),
                detail
        ));
    }

    void failed(
            String jobName,
            String instanceId,
            Instant startedAt,
            CacheScheduledWarmState state,
            Throwable failure
    ) {
        Instant finishedAt = Instant.now();
        snapshots.compute(jobName, (ignored, current) -> new CacheScheduledWarmSnapshot(
                jobName,
                state,
                instanceId,
                current == null ? 0L : current.executionCount(),
                current == null ? 0L : current.skippedCount(),
                count(current == null ? 0L : current.failureCount()),
                startedAt,
                finishedAt,
                durationMillis(startedAt, finishedAt),
                current == null ? 0 : current.lastLoadedRows(),
                current == null ? 0 : current.lastSubmittedRows(),
                current == null ? 0 : current.lastInspectedRows(),
                current == null ? 0 : current.lastEvictedRows(),
                current == null ? 0 : current.lastMissingRows(),
                current == null ? 0 : current.lastInvalidRows(),
                current == null ? "0" : current.lastReconciliationCursor(),
                current != null && current.lastReconciliationCycleCompleted(),
                failure == null ? state.name() : failure.getClass().getSimpleName() + ": " + failure.getMessage()
        ));
    }

    private CacheScheduledWarmSnapshot empty(String jobName, String instanceId) {
        return new CacheScheduledWarmSnapshot(
                jobName,
                CacheScheduledWarmState.REGISTERED,
                instanceId,
                0L,
                0L,
                0L,
                null,
                null,
                0L,
                0,
                0,
                0,
                0,
                0,
                0,
                "0",
                false,
                "Scheduled warm registered"
        );
    }

    private long count(long current) {
        return current == Long.MAX_VALUE ? current : current + 1L;
    }

    private long sumCounts(boolean skipped) {
        long total = 0L;
        for (CacheScheduledWarmSnapshot snapshot : snapshots.values()) {
            long value = skipped ? snapshot.skippedCount() : snapshot.failureCount();
            if (value > 0L) {
                total = Long.MAX_VALUE - total < value ? Long.MAX_VALUE : total + value;
            }
        }
        return total;
    }

    private long durationMillis(Instant startedAt, Instant finishedAt) {
        if (startedAt == null || finishedAt == null) {
            return 0L;
        }
        return Math.max(0L, finishedAt.toEpochMilli() - startedAt.toEpochMilli());
    }
}

package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.AdminMonitoringConfig;
import com.reactor.cachedb.core.queue.StoragePerformanceSnapshot;
import com.reactor.cachedb.redis.RedisLeaderLease;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.resps.StreamEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class PerformanceHistoryBuffer implements AutoCloseable {

    private final Supplier<CacheDatabaseAdmin> adminSupplier;
    private final JedisPooled jedis;
    private final boolean enabled;
    private final long sampleIntervalMillis;
    private final int maxSamples;
    private final String streamKey;
    private final long ttlSeconds;
    private final RedisLeaderLease leaderLease;
    private final String threadName;
    private ScheduledExecutorService executor;

    PerformanceHistoryBuffer(Supplier<CacheDatabaseAdmin> adminSupplier, JedisPooled jedis, AdminMonitoringConfig config) {
        this(adminSupplier, jedis, config, RedisLeaderLease.disabled(), "cachedb-performance-history", true);
    }

    PerformanceHistoryBuffer(
            Supplier<CacheDatabaseAdmin> adminSupplier,
            JedisPooled jedis,
            AdminMonitoringConfig config,
            RedisLeaderLease leaderLease,
            String threadName
    ) {
        this(adminSupplier, jedis, config, leaderLease, threadName, true);
    }

    private PerformanceHistoryBuffer(
            Supplier<CacheDatabaseAdmin> adminSupplier,
            JedisPooled jedis,
            AdminMonitoringConfig config,
            RedisLeaderLease leaderLease,
            String threadName,
            boolean enabled
    ) {
        this.adminSupplier = adminSupplier;
        this.jedis = jedis;
        this.enabled = enabled;
        this.sampleIntervalMillis = Math.max(config.historyMinSampleIntervalMillis(), config.historySampleIntervalMillis());
        this.maxSamples = Math.max(config.historyMinSamples(), config.historyMaxSamples());
        this.streamKey = config.performanceHistoryStreamKey();
        this.ttlSeconds = config.telemetryTtlSeconds();
        this.leaderLease = leaderLease == null ? RedisLeaderLease.disabled() : leaderLease;
        this.threadName = threadName == null || threadName.isBlank() ? "cachedb-performance-history" : threadName;
    }

    static PerformanceHistoryBuffer disabled(AdminMonitoringConfig config) {
        return new PerformanceHistoryBuffer(
                () -> null,
                null,
                config,
                RedisLeaderLease.disabled(),
                "cachedb-performance-history",
                false
        );
    }

    void start() {
        if (!enabled || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(new PerformanceHistoryThreadFactory(threadName));
        recordNow();
        executor.scheduleAtFixedRate(this::recordNowSafe, sampleIntervalMillis, sampleIntervalMillis, TimeUnit.MILLISECONDS);
    }

    List<PerformanceHistoryPoint> history(int limit) {
        if (!enabled) {
            return List.of();
        }
        if (jedis.xlen(streamKey) == 0L) {
            recordNowSafe();
        }
        List<StreamEntry> entries = jedis.xrevrange(streamKey, "+", "-", Math.max(1, limit));
        ArrayList<PerformanceHistoryPoint> snapshot = new ArrayList<>(entries.size());
        for (StreamEntry entry : entries) {
            snapshot.add(toPoint(entry));
        }
        Collections.reverse(snapshot);
        return List.copyOf(snapshot);
    }

    int clear() {
        if (!enabled) {
            return 0;
        }
        return RedisTelemetrySupport.clearStream(jedis, streamKey);
    }

    void recordNow() {
        if (!enabled) {
            return;
        }
        if (!leaderLease.tryAcquireOrRenewLeadership()) {
            return;
        }
        StoragePerformanceSnapshot snapshot = adminSupplier.get().storagePerformance();
        PerformanceHistoryPoint point = new PerformanceHistoryPoint(
                Instant.now(),
                snapshot.redisRead().operationCount(),
                snapshot.redisRead().averageMicros(),
                snapshot.redisWrite().operationCount(),
                snapshot.redisWrite().averageMicros(),
                snapshot.postgresRead().operationCount(),
                snapshot.postgresRead().averageMicros(),
                snapshot.postgresWrite().operationCount(),
                snapshot.postgresWrite().averageMicros()
        );
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("recordedAt", point.recordedAt().toString());
        fields.put("redisReadOperationCount", String.valueOf(point.redisReadOperationCount()));
        fields.put("redisReadAverageMicros", String.valueOf(point.redisReadAverageMicros()));
        fields.put("redisWriteOperationCount", String.valueOf(point.redisWriteOperationCount()));
        fields.put("redisWriteAverageMicros", String.valueOf(point.redisWriteAverageMicros()));
        fields.put("postgresReadOperationCount", String.valueOf(point.postgresReadOperationCount()));
        fields.put("postgresReadAverageMicros", String.valueOf(point.postgresReadAverageMicros()));
        fields.put("postgresWriteOperationCount", String.valueOf(point.postgresWriteOperationCount()));
        fields.put("postgresWriteAverageMicros", String.valueOf(point.postgresWriteAverageMicros()));
        jedis.xadd(streamKey, XAddParams.xAddParams(), fields);
        if (maxSamples > 0) {
            jedis.xtrim(streamKey, maxSamples, true);
        }
        RedisTelemetrySupport.expireIfNeeded(jedis, streamKey, ttlSeconds);
    }

    private void recordNowSafe() {
        try {
            recordNow();
        } catch (RuntimeException ignored) {
            // Best-effort monitoring support should never break the main system.
        }
    }

    private PerformanceHistoryPoint toPoint(StreamEntry entry) {
        return new PerformanceHistoryPoint(
                Instant.parse(entry.getFields().getOrDefault("recordedAt", Instant.EPOCH.toString())),
                parseLong(entry, "redisReadOperationCount"),
                parseLong(entry, "redisReadAverageMicros"),
                parseLong(entry, "redisWriteOperationCount"),
                parseLong(entry, "redisWriteAverageMicros"),
                parseLong(entry, "postgresReadOperationCount"),
                parseLong(entry, "postgresReadAverageMicros"),
                parseLong(entry, "postgresWriteOperationCount"),
                parseLong(entry, "postgresWriteAverageMicros")
        );
    }

    private long parseLong(StreamEntry entry, String field) {
        String value = entry.getFields().get(field);
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        leaderLease.close();
    }

    private static final class PerformanceHistoryThreadFactory implements ThreadFactory {
        private final String threadName;

        private PerformanceHistoryThreadFactory(String threadName) {
            this.threadName = threadName;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        }
    }
}

package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.AdminMonitoringConfig;
import com.reactor.cachedb.core.queue.ReconciliationHealth;
import com.reactor.cachedb.core.queue.ReconciliationMetrics;
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

final class MonitoringHistoryBuffer implements AutoCloseable {

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

    MonitoringHistoryBuffer(Supplier<CacheDatabaseAdmin> adminSupplier, JedisPooled jedis, AdminMonitoringConfig config) {
        this(adminSupplier, jedis, config, RedisLeaderLease.disabled(), "cachedb-monitoring-history", true);
    }

    MonitoringHistoryBuffer(
            Supplier<CacheDatabaseAdmin> adminSupplier,
            JedisPooled jedis,
            AdminMonitoringConfig config,
            RedisLeaderLease leaderLease,
            String threadName
    ) {
        this(adminSupplier, jedis, config, leaderLease, threadName, true);
    }

    private MonitoringHistoryBuffer(
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
        this.streamKey = config.monitoringHistoryStreamKey();
        this.ttlSeconds = config.telemetryTtlSeconds();
        this.leaderLease = leaderLease == null ? RedisLeaderLease.disabled() : leaderLease;
        this.threadName = threadName == null || threadName.isBlank() ? "cachedb-monitoring-history" : threadName;
    }

    static MonitoringHistoryBuffer disabled(AdminMonitoringConfig config) {
        return new MonitoringHistoryBuffer(
                () -> null,
                null,
                config,
                RedisLeaderLease.disabled(),
                "cachedb-monitoring-history",
                false
        );
    }

    void start() {
        if (!enabled || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(new MonitoringHistoryThreadFactory(threadName));
        recordNow();
        executor.scheduleAtFixedRate(this::recordNowSafe, sampleIntervalMillis, sampleIntervalMillis, TimeUnit.MILLISECONDS);
    }

    List<MonitoringHistoryPoint> history(int limit) {
        if (!enabled) {
            return List.of();
        }
        if (jedis.xlen(streamKey) == 0L) {
            recordNowSafe();
        }
        List<StreamEntry> entries = jedis.xrevrange(streamKey, "+", "-", Math.max(1, limit));
        ArrayList<MonitoringHistoryPoint> snapshot = new ArrayList<>(entries.size());
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
        CacheDatabaseAdmin admin = adminSupplier.get();
        ReconciliationHealth health = admin.health();
        ReconciliationMetrics metrics = health.metrics();
        MonitoringHistoryPoint point = new MonitoringHistoryPoint(
                Instant.now(),
                metrics.writeBehindStreamLength(),
                metrics.deadLetterStreamLength(),
                metrics.redisGuardrailSnapshot().usedMemoryBytes(),
                metrics.redisGuardrailSnapshot().compactionPendingCount(),
                metrics.redisRuntimeProfileSnapshot().activeProfile(),
                health.status().name()
        );
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("recordedAt", point.recordedAt().toString());
        fields.put("writeBehindBacklog", String.valueOf(point.writeBehindBacklog()));
        fields.put("deadLetterBacklog", String.valueOf(point.deadLetterBacklog()));
        fields.put("redisUsedMemoryBytes", String.valueOf(point.redisUsedMemoryBytes()));
        fields.put("compactionPendingCount", String.valueOf(point.compactionPendingCount()));
        fields.put("runtimeProfile", point.runtimeProfile());
        fields.put("healthStatus", point.healthStatus());
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

    private MonitoringHistoryPoint toPoint(StreamEntry entry) {
        return new MonitoringHistoryPoint(
                Instant.parse(entry.getFields().getOrDefault("recordedAt", Instant.EPOCH.toString())),
                parseLong(entry, "writeBehindBacklog"),
                parseLong(entry, "deadLetterBacklog"),
                parseLong(entry, "redisUsedMemoryBytes"),
                parseLong(entry, "compactionPendingCount"),
                entry.getFields().getOrDefault("runtimeProfile", ""),
                entry.getFields().getOrDefault("healthStatus", "")
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

    private static final class MonitoringHistoryThreadFactory implements ThreadFactory {
        private final String threadName;

        private MonitoringHistoryThreadFactory(String threadName) {
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

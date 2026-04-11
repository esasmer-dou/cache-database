package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.AdminMonitoringConfig;
import com.reactor.cachedb.redis.RedisLeaderLease;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.resps.StreamEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class AlertRouteHistoryBuffer implements AutoCloseable {

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

    AlertRouteHistoryBuffer(Supplier<CacheDatabaseAdmin> adminSupplier, JedisPooled jedis, AdminMonitoringConfig config) {
        this(adminSupplier, jedis, config, RedisLeaderLease.disabled(), "cachedb-alert-route-history", true);
    }

    AlertRouteHistoryBuffer(
            Supplier<CacheDatabaseAdmin> adminSupplier,
            JedisPooled jedis,
            AdminMonitoringConfig config,
            RedisLeaderLease leaderLease,
            String threadName
    ) {
        this(adminSupplier, jedis, config, leaderLease, threadName, true);
    }

    private AlertRouteHistoryBuffer(
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
        this.maxSamples = Math.max(
                config.alertRouteHistoryMinSamples(),
                config.historyMaxSamples() * Math.max(1, config.alertRouteHistorySampleMultiplier())
        );
        this.streamKey = config.alertRouteHistoryStreamKey();
        this.ttlSeconds = config.telemetryTtlSeconds();
        this.leaderLease = leaderLease == null ? RedisLeaderLease.disabled() : leaderLease;
        this.threadName = threadName == null || threadName.isBlank() ? "cachedb-alert-route-history" : threadName;
    }

    static AlertRouteHistoryBuffer disabled(AdminMonitoringConfig config) {
        return new AlertRouteHistoryBuffer(
                () -> null,
                null,
                config,
                RedisLeaderLease.disabled(),
                "cachedb-alert-route-history",
                false
        );
    }

    void start() {
        if (!enabled || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(new AlertRouteHistoryThreadFactory(threadName));
        recordNow();
        executor.scheduleAtFixedRate(this::recordNowSafe, sampleIntervalMillis, sampleIntervalMillis, TimeUnit.MILLISECONDS);
    }

    List<AlertRouteHistoryPoint> history(int limit) {
        if (!enabled) {
            return List.of();
        }
        if (jedis.xlen(streamKey) == 0L) {
            recordNowSafe();
        }
        List<StreamEntry> entries = jedis.xrevrange(streamKey, "+", "-", Math.max(1, limit));
        ArrayList<AlertRouteHistoryPoint> snapshot = new ArrayList<>(entries.size());
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
        List<AlertRouteSnapshot> routes = admin.alertRoutes();
        for (AlertRouteSnapshot route : routes) {
            AlertRouteHistoryPoint point = new AlertRouteHistoryPoint(
                    java.time.Instant.now(),
                    route.routeName(),
                    route.status(),
                    route.escalationLevel(),
                    route.deliveredCount(),
                    route.failedCount(),
                    route.droppedCount(),
                    route.lastErrorType()
            );
            LinkedHashMap<String, String> fields = new LinkedHashMap<>();
            fields.put("recordedAt", point.recordedAt().toString());
            fields.put("routeName", point.routeName());
            fields.put("status", point.status());
            fields.put("escalationLevel", point.escalationLevel());
            fields.put("deliveredCount", String.valueOf(point.deliveredCount()));
            fields.put("failedCount", String.valueOf(point.failedCount()));
            fields.put("droppedCount", String.valueOf(point.droppedCount()));
            fields.put("lastErrorType", point.lastErrorType() == null ? "" : point.lastErrorType());
            jedis.xadd(streamKey, XAddParams.xAddParams(), fields);
        }
        if (maxSamples > 0) {
            jedis.xtrim(streamKey, maxSamples, true);
        }
        RedisTelemetrySupport.expireIfNeeded(jedis, streamKey, ttlSeconds);
    }

    private void recordNowSafe() {
        try {
            recordNow();
        } catch (RuntimeException ignored) {
            // Monitoring support should remain best effort.
        }
    }

    private AlertRouteHistoryPoint toPoint(StreamEntry entry) {
        return new AlertRouteHistoryPoint(
                java.time.Instant.parse(entry.getFields().getOrDefault("recordedAt", java.time.Instant.EPOCH.toString())),
                entry.getFields().getOrDefault("routeName", ""),
                entry.getFields().getOrDefault("status", ""),
                entry.getFields().getOrDefault("escalationLevel", ""),
                parseLong(entry, "deliveredCount"),
                parseLong(entry, "failedCount"),
                parseLong(entry, "droppedCount"),
                blankToNull(entry.getFields().get("lastErrorType"))
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        leaderLease.close();
    }

    private static final class AlertRouteHistoryThreadFactory implements ThreadFactory {
        private final String threadName;

        private AlertRouteHistoryThreadFactory(String threadName) {
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

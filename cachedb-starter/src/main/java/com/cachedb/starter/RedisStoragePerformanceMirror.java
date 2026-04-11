package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.AdminMonitoringConfig;
import com.reactor.cachedb.core.queue.LatencyMetricSnapshot;
import com.reactor.cachedb.core.queue.StoragePerformanceCollector;
import com.reactor.cachedb.core.queue.StoragePerformanceSnapshot;
import redis.clients.jedis.JedisPooled;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

final class RedisStoragePerformanceMirror {

    private static final String AGGREGATE_PREFIX = "aggregate.";
    private static final String BREAKDOWN_PREFIX = "breakdown.";
    private static final String FIELD_SEPARATOR = ".";

    private final StoragePerformanceCollector collector;
    private final JedisPooled jedis;
    private final boolean enabled;
    private final String snapshotKey;
    private final long ttlSeconds;
    private final AtomicReference<StoragePerformanceSnapshot> lastSnapshot = new AtomicReference<>(emptySnapshot());

    RedisStoragePerformanceMirror(StoragePerformanceCollector collector, JedisPooled jedis, AdminMonitoringConfig config) {
        this(collector, jedis, config, true);
    }

    private RedisStoragePerformanceMirror(
            StoragePerformanceCollector collector,
            JedisPooled jedis,
            AdminMonitoringConfig config,
            boolean enabled
    ) {
        this.collector = collector;
        this.jedis = jedis;
        this.enabled = enabled;
        this.snapshotKey = config.performanceSnapshotKey();
        this.ttlSeconds = config.telemetryTtlSeconds();
    }

    static RedisStoragePerformanceMirror disabled(StoragePerformanceCollector collector, AdminMonitoringConfig config) {
        return new RedisStoragePerformanceMirror(collector, null, config, false);
    }

    StoragePerformanceSnapshot snapshot() {
        StoragePerformanceSnapshot current = collector.snapshot();
        if (!enabled) {
            lastSnapshot.set(current);
            return current;
        }
        if (isEmpty(current)) {
            return readSnapshotSafely(current);
        }
        writeSnapshotSafely(current);
        lastSnapshot.set(current);
        return current;
    }

    StoragePerformanceSnapshot reset() {
        StoragePerformanceSnapshot current = snapshot();
        collector.reset();
        if (enabled) {
            try {
                jedis.del(snapshotKey);
            } catch (RuntimeException ignored) {
                // Telemetry reset should not fail hard when Redis telemetry storage is temporarily unavailable.
            }
        }
        lastSnapshot.set(emptySnapshot());
        return current;
    }

    private void writeSnapshotSafely(StoragePerformanceSnapshot snapshot) {
        try {
            writeSnapshot(snapshot);
        } catch (RuntimeException ignored) {
            // Performance telemetry must not break repository/runtime paths.
        }
    }

    private StoragePerformanceSnapshot readSnapshotSafely(StoragePerformanceSnapshot fallback) {
        try {
            StoragePerformanceSnapshot snapshot = readSnapshot(fallback);
            if (!isEmpty(snapshot)) {
                lastSnapshot.set(snapshot);
                return snapshot;
            }
        } catch (RuntimeException ignored) {
            // Fall back to last known snapshot below.
        }
        StoragePerformanceSnapshot cached = lastSnapshot.get();
        return isEmpty(cached) ? fallback : cached;
    }

    private void writeSnapshot(StoragePerformanceSnapshot snapshot) {
        Map<String, String> fields = encodeSnapshot(snapshot);
        if (fields.isEmpty()) {
            jedis.del(snapshotKey);
            return;
        }
        jedis.del(snapshotKey);
        jedis.hset(snapshotKey, fields);
        RedisTelemetrySupport.expireIfNeeded(jedis, snapshotKey, ttlSeconds);
    }

    private StoragePerformanceSnapshot readSnapshot(StoragePerformanceSnapshot fallback) {
        Map<String, String> fields = jedis.hgetAll(snapshotKey);
        if (fields == null || fields.isEmpty()) {
            return fallback;
        }

        LinkedHashMap<String, MetricBuilder> aggregateBuilders = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashMap<String, MetricBuilder>> breakdownBuilders = new LinkedHashMap<>();
        fields.forEach((field, value) -> decodeField(field, value, aggregateBuilders, breakdownBuilders));

        return new StoragePerformanceSnapshot(
                snapshot(aggregateBuilders.get("redisRead")),
                snapshot(aggregateBuilders.get("redisWrite")),
                snapshot(aggregateBuilders.get("postgresRead")),
                snapshot(aggregateBuilders.get("postgresWrite")),
                snapshotBreakdown(breakdownBuilders.get("redisRead")),
                snapshotBreakdown(breakdownBuilders.get("redisWrite")),
                snapshotBreakdown(breakdownBuilders.get("postgresRead")),
                snapshotBreakdown(breakdownBuilders.get("postgresWrite"))
        );
    }

    private void decodeField(
            String field,
            String value,
            Map<String, MetricBuilder> aggregates,
            Map<String, LinkedHashMap<String, MetricBuilder>> breakdowns
    ) {
        if (field.startsWith(AGGREGATE_PREFIX)) {
            String remainder = field.substring(AGGREGATE_PREFIX.length());
            int split = remainder.lastIndexOf(FIELD_SEPARATOR);
            if (split <= 0) {
                return;
            }
            String kind = remainder.substring(0, split);
            String metric = remainder.substring(split + 1);
            aggregates.computeIfAbsent(kind, ignored -> new MetricBuilder()).apply(metric, value);
            return;
        }
        if (!field.startsWith(BREAKDOWN_PREFIX)) {
            return;
        }
        String remainder = field.substring(BREAKDOWN_PREFIX.length());
        String[] parts = remainder.split("\\.", 3);
        if (parts.length != 3) {
            return;
        }
        String kind = parts[0];
        String tag = decodeTag(parts[1]);
        String metric = parts[2];
        breakdowns.computeIfAbsent(kind, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(tag, ignored -> new MetricBuilder())
                .apply(metric, value);
    }

    private Map<String, String> encodeSnapshot(StoragePerformanceSnapshot snapshot) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        encodeMetric(fields, AGGREGATE_PREFIX, "redisRead", snapshot.redisRead());
        encodeMetric(fields, AGGREGATE_PREFIX, "redisWrite", snapshot.redisWrite());
        encodeMetric(fields, AGGREGATE_PREFIX, "postgresRead", snapshot.postgresRead());
        encodeMetric(fields, AGGREGATE_PREFIX, "postgresWrite", snapshot.postgresWrite());
        encodeBreakdown(fields, "redisRead", snapshot.redisReadBreakdown());
        encodeBreakdown(fields, "redisWrite", snapshot.redisWriteBreakdown());
        encodeBreakdown(fields, "postgresRead", snapshot.postgresReadBreakdown());
        encodeBreakdown(fields, "postgresWrite", snapshot.postgresWriteBreakdown());
        return fields;
    }

    private boolean isEmpty(StoragePerformanceSnapshot snapshot) {
        return snapshot.redisRead().operationCount() <= 0L
                && snapshot.redisWrite().operationCount() <= 0L
                && snapshot.postgresRead().operationCount() <= 0L
                && snapshot.postgresWrite().operationCount() <= 0L;
    }

    private static StoragePerformanceSnapshot emptySnapshot() {
        return new StoragePerformanceSnapshot(
                LatencyMetricSnapshot.empty(),
                LatencyMetricSnapshot.empty(),
                LatencyMetricSnapshot.empty(),
                LatencyMetricSnapshot.empty()
        );
    }

    private void encodeBreakdown(Map<String, String> fields, String kind, Map<String, LatencyMetricSnapshot> breakdown) {
        if (breakdown == null || breakdown.isEmpty()) {
            return;
        }
        breakdown.forEach((tag, metric) -> encodeMetric(fields, BREAKDOWN_PREFIX + kind + FIELD_SEPARATOR + encodeTag(tag) + FIELD_SEPARATOR, "", metric));
    }

    private void encodeMetric(Map<String, String> fields, String prefix, String kind, LatencyMetricSnapshot metric) {
        if (metric == null || metric.operationCount() <= 0L) {
            return;
        }
        String base = kind.isBlank() ? prefix : prefix + kind + FIELD_SEPARATOR;
        fields.put(base + "operationCount", String.valueOf(metric.operationCount()));
        fields.put(base + "averageMicros", String.valueOf(metric.averageMicros()));
        fields.put(base + "p95Micros", String.valueOf(metric.p95Micros()));
        fields.put(base + "p99Micros", String.valueOf(metric.p99Micros()));
        fields.put(base + "maxMicros", String.valueOf(metric.maxMicros()));
        fields.put(base + "lastMicros", String.valueOf(metric.lastMicros()));
        fields.put(base + "lastObservedAtEpochMillis", String.valueOf(metric.lastObservedAtEpochMillis()));
    }

    private Map<String, LatencyMetricSnapshot> snapshotBreakdown(Map<String, MetricBuilder> builders) {
        if (builders == null || builders.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, LatencyMetricSnapshot> snapshots = new LinkedHashMap<>();
        builders.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> snapshots.put(entry.getKey(), entry.getValue().build()));
        return Map.copyOf(snapshots);
    }

    private LatencyMetricSnapshot snapshot(MetricBuilder builder) {
        return builder == null ? LatencyMetricSnapshot.empty() : builder.build();
    }

    private String encodeTag(String tag) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tag.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeTag(String encodedTag) {
        try {
            return new String(Base64.getUrlDecoder().decode(encodedTag), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return encodedTag;
        }
    }

    private static final class MetricBuilder {
        private long operationCount;
        private long averageMicros;
        private long p95Micros;
        private long p99Micros;
        private long maxMicros;
        private long lastMicros;
        private long lastObservedAtEpochMillis;

        void apply(String metric, String rawValue) {
            long value = parseLong(rawValue);
            switch (metric) {
                case "operationCount" -> operationCount = value;
                case "averageMicros" -> averageMicros = value;
                case "p95Micros" -> p95Micros = value;
                case "p99Micros" -> p99Micros = value;
                case "maxMicros" -> maxMicros = value;
                case "lastMicros" -> lastMicros = value;
                case "lastObservedAtEpochMillis" -> lastObservedAtEpochMillis = value;
                default -> {
                }
            }
        }

        LatencyMetricSnapshot build() {
            if (operationCount <= 0L) {
                return LatencyMetricSnapshot.empty();
            }
            return new LatencyMetricSnapshot(
                    operationCount,
                    averageMicros,
                    p95Micros,
                    p99Micros,
                    maxMicros,
                    lastMicros,
                    lastObservedAtEpochMillis
            );
        }

        private long parseLong(String rawValue) {
            if (rawValue == null || rawValue.isBlank()) {
                return 0L;
            }
            try {
                return Long.parseLong(rawValue);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
    }
}

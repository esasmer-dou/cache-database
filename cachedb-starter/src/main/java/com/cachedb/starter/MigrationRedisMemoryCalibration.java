package com.reactor.cachedb.starter;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class MigrationRedisMemoryCalibration {

    private static final int DEFAULT_SCAN_COUNT = 500;
    private static final int DEFAULT_MAX_KEYS = 50_000;

    private final JedisPooled jedis;
    private final String keyPrefix;

    MigrationRedisMemoryCalibration(JedisPooled jedis, String keyPrefix) {
        this.jedis = Objects.requireNonNull(jedis, "jedis");
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "cachedb" : keyPrefix.trim();
    }

    Result calibrate(MigrationRedisMemoryEstimator.Result estimate) {
        LinkedHashMap<String, MutableComponent> components = new LinkedHashMap<>();
        ArrayList<String> warnings = new ArrayList<>();
        long actualTotalBytes = 0L;
        long sampledKeys = 0L;
        boolean truncated = false;
        String cursor = ScanParams.SCAN_POINTER_START;
        ScanParams params = new ScanParams()
                .match(keyPrefix + ":*")
                .count(DEFAULT_SCAN_COUNT);
        try {
            do {
                ScanResult<String> result = jedis.scan(cursor, params);
                cursor = result.getCursor();
                for (String key : result.getResult()) {
                    if (sampledKeys >= DEFAULT_MAX_KEYS) {
                        truncated = true;
                        break;
                    }
                    sampledKeys++;
                    long bytes = memoryUsage(key, warnings);
                    if (bytes <= 0L) {
                        continue;
                    }
                    actualTotalBytes = saturatingAdd(actualTotalBytes, bytes);
                    component(components, classify(key)).add(key, bytes);
                }
                if (truncated) {
                    break;
                }
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        } catch (RuntimeException exception) {
            warnings.add("Redis memory calibration failed: " + exception.getMessage());
        }

        long estimatedTotalBytes = estimate == null ? 0L : estimate.estimatedTotalBytes();
        long deltaBytes = actualTotalBytes - estimatedTotalBytes;
        double actualToEstimateRatio = estimatedTotalBytes <= 0L
                ? (actualTotalBytes <= 0L ? 1.0d : 999.0d)
                : actualTotalBytes / (double) estimatedTotalBytes;
        if (truncated) {
            warnings.add("Redis key scan was truncated at " + DEFAULT_MAX_KEYS + " keys; actual memory may be higher.");
        }
        if (actualTotalBytes == 0L) {
            warnings.add("No Redis keys under prefix " + keyPrefix + " reported MEMORY USAGE. Run warm execution before trusting calibration.");
        }

        List<Component> sortedComponents = components.values().stream()
                .map(MutableComponent::snapshot)
                .sorted(Comparator.comparing(Component::actualBytes).reversed())
                .toList();
        return new Result(
                keyPrefix,
                estimatedTotalBytes,
                actualTotalBytes,
                deltaBytes,
                actualToEstimateRatio,
                sampledKeys,
                truncated,
                sortedComponents,
                List.copyOf(warnings),
                Instant.now()
        );
    }

    private long memoryUsage(String key, ArrayList<String> warnings) {
        try {
            Long usage = jedis.memoryUsage(key);
            return usage == null ? 0L : Math.max(0L, usage);
        } catch (RuntimeException exception) {
            if (warnings.isEmpty()) {
                warnings.add("Redis MEMORY USAGE is unavailable or failed for at least one key: " + exception.getMessage());
            }
            return 0L;
        }
    }

    private MutableComponent component(Map<String, MutableComponent> components, String code) {
        return components.computeIfAbsent(code, MutableComponent::new);
    }

    private String classify(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (normalized.contains(":entity:projection:")) {
            return "PROJECTION";
        }
        if (normalized.contains(":entity:")) {
            return "ENTITY";
        }
        if (normalized.contains(":index:")) {
            return "INDEX";
        }
        if (normalized.contains(":hotset")) {
            return "HOTSET";
        }
        if (normalized.contains(":page:")) {
            return "PAGE_CACHE";
        }
        if (normalized.contains(":stream") || normalized.contains(":write") || normalized.contains(":projection-refresh")) {
            return "STREAM_OPS";
        }
        if (normalized.contains(":compaction:")) {
            return "COMPACTION";
        }
        return "OTHER";
    }

    private long saturatingAdd(long left, long right) {
        if (left < 0L || right < 0L || Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    record Result(
            String keyPrefix,
            long estimatedTotalBytes,
            long actualTotalBytes,
            long deltaBytes,
            double actualToEstimateRatio,
            long sampledKeyCount,
            boolean truncated,
            List<Component> components,
            List<String> warnings,
            Instant calibratedAt
    ) {
    }

    record Component(
            String code,
            long keyCount,
            long actualBytes,
            String largestKey,
            long largestKeyBytes
    ) {
    }

    private static final class MutableComponent {
        private final String code;
        private long keyCount;
        private long actualBytes;
        private String largestKey = "";
        private long largestKeyBytes;

        private MutableComponent(String code) {
            this.code = code;
        }

        private void add(String key, long bytes) {
            keyCount++;
            actualBytes = Long.MAX_VALUE - actualBytes < bytes ? Long.MAX_VALUE : actualBytes + bytes;
            if (bytes > largestKeyBytes) {
                largestKey = key;
                largestKeyBytes = bytes;
            }
        }

        private Component snapshot() {
            return new Component(code, keyCount, actualBytes, largestKey, largestKeyBytes);
        }
    }
}

package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.route.RouteCoverage;
import com.reactor.cachedb.core.route.RouteCoverageStatus;
import com.reactor.cachedb.core.route.RouteCoverageStore;
import redis.clients.jedis.JedisPooled;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Redis-backed route coverage shared by all application pods. */
public final class RedisRouteCoverageStore implements RouteCoverageStore {
    private final JedisPooled jedis;
    private final String keyPrefix;

    public RedisRouteCoverageStore(JedisPooled jedis, String keyPrefix) {
        this.jedis = Objects.requireNonNull(jedis, "jedis");
        String normalized = keyPrefix == null || keyPrefix.isBlank() ? "cachedb" : keyPrefix.trim();
        this.keyPrefix = normalized + ":route-coverage:";
    }

    @Override
    public RouteCoverage get(String routeName, String scope, Duration maxAge) {
        String normalizedRoute = requireText(routeName, "routeName");
        String normalizedScope = normalizeScope(scope);
        Map<String, String> values = jedis.hgetAll(key(normalizedRoute, normalizedScope));
        if (values == null || values.isEmpty()) {
            return RouteCoverage.notWarmed(normalizedRoute, normalizedScope);
        }
        try {
            RouteCoverage coverage = new RouteCoverage(
                    normalizedRoute,
                    normalizedScope,
                    RouteCoverageStatus.valueOf(values.getOrDefault("status", RouteCoverageStatus.NOT_WARMED.name())),
                    parseLong(values.get("sourceRows")),
                    parseLong(values.get("submittedRows")),
                    parseInstant(values.get("warmedAt")),
                    parseInstant(values.get("updatedAt")),
                    values.getOrDefault("detail", "")
            );
            return coverage.withStaleness(maxAge, Instant.now());
        } catch (RuntimeException invalidState) {
            return new RouteCoverage(normalizedRoute, normalizedScope, RouteCoverageStatus.FAILED,
                    0, 0, null, Instant.now(), "Invalid Redis coverage state: " + invalidState.getMessage());
        }
    }

    @Override
    public void markWarming(String routeName, String scope, Duration ttl) {
        write(routeName, scope, RouteCoverageStatus.WARMING, 0, 0, null, "Warm is running", ttl);
    }

    @Override
    public void markComplete(String routeName, String scope, long sourceRows, long submittedRows, Duration ttl) {
        write(routeName, scope, RouteCoverageStatus.COMPLETE, sourceRows, submittedRows, Instant.now(), "", ttl);
    }

    @Override
    public void markPartial(
            String routeName,
            String scope,
            long sourceRows,
            long submittedRows,
            String detail,
            Duration ttl
    ) {
        write(routeName, scope, RouteCoverageStatus.PARTIAL, sourceRows, submittedRows, Instant.now(), detail, ttl);
    }

    @Override
    public void markFailed(String routeName, String scope, String detail, Duration ttl) {
        write(routeName, scope, RouteCoverageStatus.FAILED, 0, 0, null, detail, ttl);
    }

    private void write(
            String routeName,
            String scope,
            RouteCoverageStatus status,
            long sourceRows,
            long submittedRows,
            Instant warmedAt,
            String detail,
            Duration ttl
    ) {
        String normalizedRoute = requireText(routeName, "routeName");
        String normalizedScope = normalizeScope(scope);
        Instant now = Instant.now();
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("status", status.name());
        values.put("sourceRows", String.valueOf(Math.max(0L, sourceRows)));
        values.put("submittedRows", String.valueOf(Math.max(0L, submittedRows)));
        values.put("warmedAt", warmedAt == null ? "" : warmedAt.toString());
        values.put("updatedAt", now.toString());
        values.put("detail", detail == null ? "" : truncate(detail, 1_024));
        String key = key(normalizedRoute, normalizedScope);
        jedis.hset(key, values);
        long ttlSeconds = ttl == null ? 86_400L : Math.max(60L, ttl.toSeconds());
        jedis.expire(key, ttlSeconds);
    }

    private String key(String routeName, String scope) {
        return keyPrefix + encode(routeName) + ':' + encode(scope);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizeScope(String value) {
        return value == null || value.isBlank() ? "global" : value.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static long parseLong(String value) {
        return value == null || value.isBlank() ? 0L : Long.parseLong(value);
    }

    private static Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

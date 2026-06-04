package com.reactor.cachedb.starter;

import redis.clients.jedis.JedisPooled;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class RedisMigrationWarmCheckpointStore implements MigrationWarmCheckpointStore {

    private static final long CHECKPOINT_TTL_SECONDS = 86_400L;

    private final JedisPooled jedis;
    private final String keyPrefix;

    RedisMigrationWarmCheckpointStore(JedisPooled jedis, String keyPrefix) {
        this.jedis = Objects.requireNonNull(jedis, "jedis");
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "cachedb" : keyPrefix.trim();
    }

    @Override
    public Optional<MigrationWarmRunner.Checkpoint> load(String jobId) {
        String normalizedJobId = normalizeJobId(jobId);
        if (normalizedJobId.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> fields = jedis.hgetAll(key(normalizedJobId));
        if (fields == null || fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MigrationWarmRunner.Checkpoint(
                normalizedJobId,
                fields.getOrDefault("phase", ""),
                parseLong(fields.get("childRowsRead")),
                parseLong(fields.get("childRowsHydrated")),
                parseLong(fields.get("rootRowsRead")),
                parseLong(fields.get("rootRowsHydrated")),
                parseInstant(fields.get("updatedAt"))
        ));
    }

    @Override
    public void save(MigrationWarmRunner.Checkpoint checkpoint) {
        if (checkpoint == null || checkpoint.jobId() == null || checkpoint.jobId().isBlank()) {
            return;
        }
        String key = key(checkpoint.jobId());
        jedis.hset(key, Map.of(
                "phase", checkpoint.phase(),
                "childRowsRead", String.valueOf(checkpoint.childRowsRead()),
                "childRowsHydrated", String.valueOf(checkpoint.childRowsHydrated()),
                "rootRowsRead", String.valueOf(checkpoint.rootRowsRead()),
                "rootRowsHydrated", String.valueOf(checkpoint.rootRowsHydrated()),
                "updatedAt", checkpoint.updatedAt().toString()
        ));
        jedis.expire(key, CHECKPOINT_TTL_SECONDS);
    }

    @Override
    public void clear(String jobId) {
        String normalizedJobId = normalizeJobId(jobId);
        if (!normalizedJobId.isBlank()) {
            jedis.del(key(normalizedJobId));
        }
    }

    private String key(String jobId) {
        return keyPrefix + ":migration:warm:checkpoint:" + normalizeJobId(jobId);
    }

    private String normalizeJobId(String jobId) {
        return jobId == null ? "" : jobId.trim().replaceAll("[^A-Za-z0-9_.:-]", "_");
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return Instant.EPOCH;
        }
    }
}

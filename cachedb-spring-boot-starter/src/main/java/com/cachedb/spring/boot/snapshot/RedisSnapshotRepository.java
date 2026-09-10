package com.reactor.cachedb.spring.boot.snapshot;

import redis.clients.jedis.JedisPooled;

import java.util.Optional;

/** Point reads from a committed generation only; never invokes JDBC or starts a warm. */
final class RedisSnapshotRepository implements SnapshotRepository {
    private final JedisPooled redis;
    private final String prefix;

    RedisSnapshotRepository(JedisPooled redis, String prefix) {
        this.redis = redis;
        this.prefix = prefix;
    }

    public Optional<SnapshotValue> findById(String id) {
        return findById(id, true);
    }

    private Optional<SnapshotValue> findById(String id, boolean retry) {
        String active = redis.get(prefix + ":active");
        if (active == null) return Optional.empty();
        int split = active.indexOf(':');
        if (split <= 0) throw new IllegalArgumentException("Invalid snapshot generation marker");
        String generation = active.substring(0, split);
        if (!generation.matches("[0-9a-f-]{36}"))
            throw new IllegalArgumentException("Invalid snapshot generation");
        long sourceAt = Long.parseLong(active.substring(split + 1));
        String payload = redis.hget(prefix + ":data:" + generation, id);
        // A concurrent commit may unlink the generation we just read. Retry against the new pointer
        // once.
        if (payload == null && retry && !active.equals(redis.get(prefix + ":active")))
            return findById(id, false);
        return payload == null
                ? Optional.empty()
                : Optional.of(new SnapshotValue(id, payload, sourceAt, generation));
    }
}

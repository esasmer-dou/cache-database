package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.model.WriteDependency;
import com.reactor.cachedb.core.model.WriteReceipt;
import redis.clients.jedis.JedisPooled;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Shared, monotonic SQL-durability markers written by write-behind workers.
 */
public final class RedisDurabilityTracker {

    private static final String ADVANCE_SCRIPT = """
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            local candidate = tonumber(ARGV[1])
            if candidate > current then
                redis.call('SET', KEYS[1], tostring(candidate))
                return candidate
            end
            return current
            """;

    private final JedisPooled jedis;
    private final RedisKeyStrategy keyStrategy;

    public RedisDurabilityTracker(JedisPooled jedis, RedisKeyStrategy keyStrategy) {
        this.jedis = Objects.requireNonNull(jedis, "jedis");
        this.keyStrategy = Objects.requireNonNull(keyStrategy, "keyStrategy");
    }

    public long advance(String namespace, Object id, long version) {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be greater than zero");
        }
        Object result = jedis.eval(
                ADVANCE_SCRIPT,
                List.of(keyStrategy.durabilityKey(namespace, id)),
                List.of(String.valueOf(version))
        );
        return result instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(result));
    }

    public void advanceBatch(String namespace, List<?> ids, List<Long> versions) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        if (versions == null || versions.size() != ids.size()) {
            throw new IllegalArgumentException("versions must align with ids");
        }
        ArrayList<String> keys = new ArrayList<>(ids.size());
        ArrayList<String> args = new ArrayList<>(ids.size());
        for (int index = 0; index < ids.size(); index++) {
            Long version = versions.get(index);
            if (version == null || version <= 0) {
                throw new IllegalArgumentException("versions must be greater than zero");
            }
            keys.add(keyStrategy.durabilityKey(namespace, ids.get(index)));
            args.add(String.valueOf(version));
        }
        jedis.eval("""
                for i = 1, #KEYS do
                    local current = tonumber(redis.call('GET', KEYS[i]) or '0')
                    local candidate = tonumber(ARGV[i])
                    if candidate > current then
                        redis.call('SET', KEYS[i], tostring(candidate))
                    end
                end
                return #KEYS
                """, keys, args);
    }

    public boolean isDurable(WriteDependency dependency) {
        Objects.requireNonNull(dependency, "dependency");
        return durableVersion(dependency.redisNamespace(), dependency.id()) >= dependency.version();
    }

    public boolean isDurable(WriteReceipt<?, ?> receipt) {
        Objects.requireNonNull(receipt, "receipt");
        return durableVersion(receipt.redisNamespace(), receipt.id()) >= receipt.version();
    }

    public boolean areDurable(Collection<? extends WriteReceipt<?, ?>> receipts) {
        if (receipts == null || receipts.isEmpty()) {
            return true;
        }
        ArrayList<WriteReceipt<?, ?>> ordered = new ArrayList<>(receipts.size());
        ArrayList<String> keys = new ArrayList<>(receipts.size());
        for (WriteReceipt<?, ?> receipt : receipts) {
            if (receipt == null) {
                continue;
            }
            ordered.add(receipt);
            keys.add(keyStrategy.durabilityKey(receipt.redisNamespace(), receipt.id()));
        }
        if (ordered.isEmpty()) {
            return true;
        }
        List<String> durableVersions = jedis.mget(keys.toArray(String[]::new));
        for (int index = 0; index < ordered.size(); index++) {
            if (parseVersion(durableVersions.get(index)) < ordered.get(index).version()) {
                return false;
            }
        }
        return true;
    }

    public long durableVersion(String namespace, Object id) {
        return parseVersion(jedis.get(keyStrategy.durabilityKey(namespace, id)));
    }

    public boolean await(WriteReceipt<?, ?> receipt, Duration timeout, Duration pollInterval) {
        Objects.requireNonNull(receipt, "receipt");
        Duration effectiveTimeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        Duration effectivePoll = pollInterval == null ? Duration.ofMillis(25) : pollInterval;
        if (effectiveTimeout.isNegative() || effectiveTimeout.isZero()) {
            return isDurable(receipt);
        }
        long deadline = System.nanoTime() + effectiveTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (isDurable(receipt)) {
                return true;
            }
            try {
                Thread.sleep(Math.max(1L, effectivePoll.toMillis()));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isDurable(receipt);
    }

    public boolean awaitAll(
            Collection<? extends WriteReceipt<?, ?>> receipts,
            Duration timeout,
            Duration pollInterval
    ) {
        Duration effectiveTimeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        Duration effectivePoll = pollInterval == null ? Duration.ofMillis(25) : pollInterval;
        if (effectiveTimeout.isNegative() || effectiveTimeout.isZero()) {
            return areDurable(receipts);
        }
        long deadline = System.nanoTime() + effectiveTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (areDurable(receipts)) {
                return true;
            }
            try {
                Thread.sleep(Math.max(1L, effectivePoll.toMillis()));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return areDurable(receipts);
    }

    private long parseVersion(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}

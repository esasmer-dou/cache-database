package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.model.WriteDependency;
import com.reactor.cachedb.core.model.WriteReceipt;

import redis.clients.jedis.JedisPooled;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Shared, monotonic SQL-durability markers written by write-behind workers. */
public final class RedisDurabilityTracker {
    public boolean awaitBatch(com.reactor.cachedb.core.api.CacheWriteBatchReceipt receipt, Duration timeout) {
        if (receipt.dependencies().isEmpty()) throw new IllegalArgumentException("Batch receipt has no durability dependencies");
        if (receipt.dependencies().size() > 100) throw new IllegalArgumentException("Batch exceeds 100 dependencies");
        var receipts = new java.util.ArrayList<WriteReceipt<?, ?>>(receipt.dependencies().size());
        for (var dependency : receipt.dependencies()) receipts.add(new WriteReceipt<>(null,
                dependency.id(), dependency.redisNamespace(), com.reactor.cachedb.core.model.OperationType.UPSERT,
                dependency.version(), java.time.Instant.EPOCH));
        return awaitAll(receipts, timeout, null);
    }

    private static final String ADVANCE_SCRIPT =
            """
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            local candidate = tonumber(ARGV[1])
            if candidate > current then
                redis.call('SET', KEYS[1], tostring(candidate))
                return candidate
            end
            return current
            """;

    private final RedisClientOperations jedis;
    private final RedisKeyStrategy keyStrategy;

    public RedisDurabilityTracker(JedisPooled jedis, RedisKeyStrategy keyStrategy) {
        this(JedisPooledOperations.of(jedis), keyStrategy);
    }

    public RedisDurabilityTracker(RedisClientOperations jedis, RedisKeyStrategy keyStrategy) {
        this.jedis = Objects.requireNonNull(jedis, "jedis");
        this.keyStrategy = Objects.requireNonNull(keyStrategy, "keyStrategy");
    }

    public long advance(String namespace, Object id, long version) {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be greater than zero");
        }
        Object result =
                jedis.eval(
                        ADVANCE_SCRIPT,
                        List.of(keyStrategy.durabilityKey(namespace, id)),
                        List.of(String.valueOf(version)));
        long advanced = result instanceof Number number
                ? number.longValue()
                : Long.parseLong(String.valueOf(result));
        LocalDurabilitySignals.advance(jedis.commands(), keyStrategy.durabilityKey(namespace, id), advanced);
        return advanced;
    }

    public void advanceBatch(String namespace, List<?> ids, List<Long> versions) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        if (versions == null || versions.size() != ids.size()) {
            throw new IllegalArgumentException("versions must align with ids");
        }
        if (ids.size() > 10_000) throw new IllegalArgumentException("At most 10000 durability advances per batch");
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
        if (jedis.clustered()) {
            // Each marker is monotonic independently; a pipeline is safe across Cluster slots.
            try (var pipeline = jedis.pipeline()) {
                for (int i = 0; i < keys.size(); i++)
                    pipeline.eval(ADVANCE_SCRIPT, List.of(keys.get(i)), List.of(args.get(i)));
                pipeline.sync();
            }
        } else jedis.eval(
                """
                for i = 1, #KEYS do
                    local current = tonumber(redis.call('GET', KEYS[i]) or '0')
                    local candidate = tonumber(ARGV[i])
                    if candidate > current then
                        redis.call('SET', KEYS[i], tostring(candidate))
                    end
                end
                return #KEYS
                """,
                keys,
                args);
        for (int index = 0; index < keys.size(); index++)
            LocalDurabilitySignals.advance(jedis.commands(), keys.get(index), versions.get(index));
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
        return parseVersion(jedis.commands().get(keyStrategy.durabilityKey(namespace, id)));
    }

    public boolean areDurableDependencies(Collection<WriteDependency> dependencies) {
        Objects.requireNonNull(dependencies, "dependencies");
        if (dependencies.isEmpty() || dependencies.size() > 1_000)
            throw new IllegalArgumentException("Expected 1..1000 durability dependencies");
        var ordered = List.copyOf(dependencies);
        String[] keys =
                ordered.stream()
                        .map(
                                dependency ->
                                        keyStrategy.durabilityKey(
                                                dependency.redisNamespace(), dependency.id()))
                        .toArray(String[]::new);
        var versions = jedis.mget(keys);
        for (int i = 0; i < ordered.size(); i++)
            if (parseVersion(versions.get(i)) < ordered.get(i).version()) return false;
        return true;
    }

    public boolean await(WriteReceipt<?, ?> receipt, Duration timeout, Duration pollInterval) {
        Objects.requireNonNull(receipt, "receipt");
        Duration effectiveTimeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (effectiveTimeout.isNegative() || effectiveTimeout.isZero()) {
            return isDurable(receipt);
        }
        try (var waiter = LocalDurabilitySignals.register(jedis.commands(),
                keyStrategy.durabilityKey(receipt.redisNamespace(), receipt.id()), receipt.version())) {
            return awaitSignal(waiter.future(), () -> isDurable(receipt), effectiveTimeout, pollInterval);
        }
    }

    public boolean awaitAll(
            Collection<? extends WriteReceipt<?, ?>> receipts,
            Duration timeout,
            Duration pollInterval) {
        Duration effectiveTimeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (effectiveTimeout.isNegative() || effectiveTimeout.isZero()) {
            return areDurable(receipts);
        }
        if (receipts == null || receipts.isEmpty()) return true;
        if (receipts.size() > 16_384) throw new IllegalArgumentException("Too many durability receipts");
        var registrations = new ArrayList<LocalDurabilitySignals.Registration>();
        try {
            for (var receipt : receipts) if (receipt != null)
                registrations.add(LocalDurabilitySignals.register(jedis.commands(),
                        keyStrategy.durabilityKey(receipt.redisNamespace(), receipt.id()), receipt.version()));
            var signal = java.util.concurrent.CompletableFuture.allOf(registrations.stream()
                    .map(LocalDurabilitySignals.Registration::future).toArray(java.util.concurrent.CompletableFuture[]::new));
            return awaitSignal(signal, () -> areDurable(receipts), effectiveTimeout, pollInterval);
        } finally {
            registrations.forEach(LocalDurabilitySignals.Registration::close);
        }
    }

    private boolean awaitSignal(java.util.concurrent.CompletableFuture<?> signal,
            java.util.function.BooleanSupplier durable, Duration timeout, Duration pollInterval) {
        long timeoutNanos = timeout.toNanos();
        if (timeoutNanos > java.util.concurrent.TimeUnit.MINUTES.toNanos(5))
            throw new IllegalArgumentException("Durability timeout exceeds five minutes");
        long deadline = System.nanoTime() + timeoutNanos;
        long cap = pollInterval == null ? 25 : Math.max(1, pollInterval.toMillis());
        long delay = Math.min(2, cap);
        while (true) {
            if (durable.getAsBoolean()) return true;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            try {
                signal.get(Math.min(remaining, java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(delay)),
                        java.util.concurrent.TimeUnit.NANOSECONDS);
                if (signal.isDone()) signal = new java.util.concurrent.CompletableFuture<>();
            } catch (java.util.concurrent.TimeoutException expected) {
                delay = Math.min(cap, delay < 8 ? delay * 2 : (delay < 15 ? 15 : (delay < 25 ? 25 : delay * 2)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            } catch (java.util.concurrent.ExecutionException failure) {
                throw new IllegalStateException("Durability signal failed", failure.getCause());
            }
        }
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

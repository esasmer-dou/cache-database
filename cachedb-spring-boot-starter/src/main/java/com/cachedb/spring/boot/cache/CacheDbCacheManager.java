package com.reactor.cachedb.spring.boot.cache;

import com.reactor.cachedb.core.cache.SingleFlightLoader;
import com.reactor.cachedb.core.codec.CacheValueCodec;
import com.reactor.cachedb.core.config.SingleFlightConfig;
import com.reactor.cachedb.redis.RedisClientOperations;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import org.springframework.cache.CacheManager;

/** Owns coordination only, never the application Redis clients. No lazy/unbounded cache creation. */
public final class CacheDbCacheManager implements CacheManager, AutoCloseable {
    private final RedisClientOperations redis;
    private final String prefix;
    private final String channel;
    private final Map<String, CacheDbCache> caches;
    private final CacheDbCacheInvalidationCoordinator coordinator;
    private final SingleFlightLoader<String, String> flights;
    private final CacheDbCacheFlights.Lease flightLease;
    private final Semaphore operations;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LongAdder rejected = new LongAdder();

    public CacheDbCacheManager(RedisClientOperations redis, String prefix,
            Map<String, CacheDbCacheConfiguration> configurations, List<CacheDbCacheCodec<?>> codecs) {
        this(redis, redis, prefix, configurations, codecs, 256, SingleFlightConfig.defaults());
    }

    public CacheDbCacheManager(RedisClientOperations redis, RedisClientOperations invalidationRedis, String prefix,
            Map<String, CacheDbCacheConfiguration> configurations, List<CacheDbCacheCodec<?>> codecs,
            int maxConcurrentOperations, SingleFlightConfig singleFlight) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.prefix = CacheDbCacheKeys.prefix(prefix);
        Objects.requireNonNull(configurations, "configurations");
        if (configurations.isEmpty() || configurations.size() > 256)
            throw new IllegalArgumentException("Configure between 1 and 256 Spring caches");
        configurations = Map.copyOf(configurations);
        if (maxConcurrentOperations < 1 || maxConcurrentOperations > 4096 || !Objects.requireNonNull(singleFlight, "singleFlight").enabled()
                || singleFlight.maxInFlightKeys() > 4096
                || singleFlight.maxResultCharacters() > CacheDbCacheConfiguration.MAX_VALUE_BYTES)
            throw new IllegalArgumentException("Spring Cache requires bounded operations and enabled single-flight");
        long entries = 0, bytes = 0;
        for (var entry : configurations.entrySet()) {
            CacheDbCacheKeys.checkName(entry.getKey());
            entries += entry.getValue().nearCache().maxEntries();
            bytes += entry.getValue().nearCache().maxBytes();
        }
        if (entries > 64000 || bytes > 64L * 1024 * 1024)
            throw new IllegalArgumentException("Total Spring Cache L1 budget exceeds 64000 entries / 64 MiB");
        Map<String, CacheValueCodec<Object>> schema = new HashMap<>();
        if (codecs.size() > 256) throw new IllegalArgumentException("Too many Spring Cache codecs");
        for (var binding : codecs) {
            if (!configurations.containsKey(binding.cacheName()) || schema.containsKey(binding.cacheName()))
                throw new IllegalArgumentException("Duplicate or unknown Spring Cache codec binding");
            @SuppressWarnings("unchecked") CacheValueCodec<Object> codec = (CacheValueCodec<Object>) binding.codec();
            schema.put(binding.cacheName(), codec);
        }
        operations = new Semaphore(maxConcurrentOperations);
        coordinator = new CacheDbCacheInvalidationCoordinator(Objects.requireNonNull(invalidationRedis, "invalidationRedis"), this.prefix);
        channel = coordinator.channel();
        Map<String, Object> codecIdentities = new HashMap<>();
        configurations.keySet().forEach(name -> codecIdentities.put(name,
                schema.containsKey(name) ? CacheDbCacheFlights.codecIdentity(schema.get(name)) : CacheDbJsonCacheValueCodec.class));
        flightLease = CacheDbCacheFlights.acquire(redis, this.prefix, singleFlight, configurations, codecIdentities);
        flights = flightLease.loader();
        Map<String, CacheDbCache> built = new LinkedHashMap<>();
        try {
            for (var entry : configurations.entrySet()) {
                var config = entry.getValue();
                var near = coordinator.register(entry.getKey(), config);
                var codec = schema.getOrDefault(entry.getKey(), new CacheDbJsonCacheValueCodec(config.maxValueBytes()));
                built.put(entry.getKey(), new CacheDbCache(this, entry.getKey(), config, codec, near));
            }
            caches = Map.copyOf(built);
            coordinator.freezeAndStartIfNearEnabled();
        } catch (RuntimeException | Error failure) {
            try { coordinator.close(); } finally { flightLease.close(); }
            throw failure;
        }
    }

    @Override public CacheDbCache getCache(String name) { requireOpen(); return caches.get(name); }
    @Override public Collection<String> getCacheNames() { return caches.keySet(); }
    public boolean invalidationConnected() { return coordinator.connected() && !closed.get(); }
    public CacheDbCacheInvalidationCoordinator invalidationCoordinator() { return coordinator; }
    public long rejectedOperations() { return rejected.sum(); }
    RedisClientOperations redis() { return redis; }
    String prefix() { return prefix; }
    String channel() { return channel; }
    SingleFlightLoader<String, String> flights() { return flights; }
    CacheDbCacheFlights.Call enterLoad(String key) { requireOpen(); return flightLease.enter(key); }
    void requireOpen() {
        if (closed.get()) throw new IllegalStateException("Spring Cache manager is closed");
    }
    <T> T operation(Supplier<T> task) {
        requireOpen();
        if (!operations.tryAcquire()) {
            rejected.increment();
            throw new IllegalStateException("Spring Cache operation capacity exceeded");
        }
        try { requireOpen(); return task.get(); }
        finally { operations.release(); }
    }
    @Override public void close() {
        boolean release = closed.compareAndSet(false, true);
        try { coordinator.close(); }
        finally { if (release) flightLease.close(); }
    }
}

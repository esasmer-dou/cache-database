package com.reactor.cachedb.spring.boot.cache;

import com.reactor.cachedb.core.cache.SingleFlightLoader;
import com.reactor.cachedb.core.config.SingleFlightConfig;
import com.reactor.cachedb.redis.RedisClientOperations;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded JVM sharing by borrowed client identity + prefix, never by ambiguous endpoint strings. */
final class CacheDbCacheFlights {
    private static final int MAX_GROUPS = 256, MAX_LEASES = 1024;
    private static final int MAX_RESERVED_KEYS = 65536, MAX_RESERVED_WAITERS = 1048576;
    private static final Semaphore HANDOFF_BYTES = new Semaphore(64 * 1024 * 1024);
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final Map<Key, Group> GROUPS = new HashMap<>();
    private static int leases, reservedKeys, reservedWaiters;

    private CacheDbCacheFlights() {}
    private static final class Key {
        final RedisClientOperations redis;
        final String prefix;
        Key(RedisClientOperations redis, String prefix) { this.redis = redis; this.prefix = prefix; }
        @Override public int hashCode() { return 31 * System.identityHashCode(redis) + prefix.hashCode(); }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && redis == key.redis && prefix.equals(key.prefix);
        }
    }
    private static final class CodecIdentity {
        final Object codec;
        CodecIdentity(Object codec) { this.codec = codec; }
        @Override public int hashCode() { return System.identityHashCode(codec); }
        @Override public boolean equals(Object other) {
            return other instanceof CodecIdentity identity && codec == identity.codec;
        }
    }
    static Object codecIdentity(Object codec) { return new CodecIdentity(codec); }

    private record Handoff(String expected, String payload, long cutoff, int bytes) {}
    private static final class Cohort {
        long sequence;
        int users;
        Handoff completed;
    }
    static final class Call implements AutoCloseable {
        final Group group;
        final String key;
        final Cohort cohort;
        final long sequence;
        final boolean follower;
        boolean closed;
        Call(Group group, String key, Cohort cohort, long sequence, boolean follower) {
            this.group = group; this.key = key; this.cohort = cohort;
            this.sequence = sequence; this.follower = follower;
        }
        String load(String expected, Supplier<String> source) {
            String completed = completed(expected);
            if (completed != null) return completed;
            return group.loader.load(expected, () -> {
                String late = completed(expected);
                if (late != null) return late;
                String payload = source.get();
                checkPayload(payload);
                group.callsLock.lock();
                try {
                    Handoff old = cohort.completed;
                    if (old != null) HANDOFF_BYTES.release(old.bytes);
                    int bytes = 256 + 2 * (payload.length() + expected.length());
                    boolean retained = HANDOFF_BYTES.tryAcquire(bytes);
                    cohort.completed = new Handoff(expected, retained ? payload : null, cohort.sequence, retained ? bytes : 0);
                } finally { group.callsLock.unlock(); }
                return payload;
            });
        }
        void checkPayload(String payload) {
            if (payload == null || payload.length() > group.config.maxResultCharacters())
                throw new IllegalArgumentException("Spring Cache single-flight result exceeds its character limit");
        }
        private String completed(String expected) {
            group.callsLock.lock();
            try {
                if (closed) throw new IllegalStateException("Spring Cache load registration is closed");
                Handoff value = cohort.completed;
                if (value == null || sequence > value.cutoff || !expected.equals(value.expected)) return null;
                if (value.payload == null)
                    throw new IllegalStateException("Spring Cache completed-load byte budget exceeded; retry the cache lookup");
                return value.payload;
            } finally { group.callsLock.unlock(); }
        }
        @Override public void close() {
            group.callsLock.lock();
            try {
                if (closed) return;
                closed = true;
                if (follower) group.registeredFollowers--;
                if (--cohort.users == 0) {
                    group.calls.remove(key);
                    Handoff value = cohort.completed;
                    if (value != null) HANDOFF_BYTES.release(value.bytes);
                    cohort.completed = null;
                }
            } finally { group.callsLock.unlock(); }
        }
    }
    private static final class Group {
        final SingleFlightConfig config;
        final Map<String, CacheDbCacheConfiguration> caches;
        final Map<String, Object> codecIdentities;
        final SingleFlightLoader<String, String> loader;
        final ReentrantLock callsLock = new ReentrantLock();
        final Map<String, Cohort> calls = new HashMap<>();
        int registeredFollowers;
        int references;
        Group(SingleFlightConfig config, Map<String, CacheDbCacheConfiguration> caches, Map<String, Object> codecIdentities) {
            this.config = config;
            this.caches = Map.copyOf(caches);
            this.codecIdentities = Map.copyOf(codecIdentities);
            loader = new SingleFlightLoader<>(config);
        }
        boolean idle() {
            callsLock.lock();
            try { return references == 0 && calls.isEmpty() && loader.inFlightCount() == 0 && loader.waiterCount() == 0; }
            finally { callsLock.unlock(); }
        }
    }
    static final class Lease implements AutoCloseable {
        private final Group group;
        private final AtomicBoolean closed = new AtomicBoolean();
        Lease(Group group) { this.group = group; }
        SingleFlightLoader<String, String> loader() { return group.loader; }
        Call enter(String key) {
            group.callsLock.lock();
            try {
                if (closed.get()) throw new IllegalStateException("Spring Cache manager lease is closed");
                Cohort cohort = group.calls.get(key);
                boolean follower = cohort != null;
                if (follower) {
                    if (group.registeredFollowers >= group.config.maxWaiters())
                        throw new IllegalStateException("Spring Cache registered waiter capacity exceeded");
                    if (cohort.sequence == Long.MAX_VALUE)
                        throw new IllegalStateException("Spring Cache load registration sequence exhausted");
                } else {
                    if (group.calls.size() >= group.config.maxInFlightKeys())
                        throw new IllegalStateException("Spring Cache registered key capacity exceeded");
                    cohort = new Cohort();
                    group.calls.put(key, cohort);
                }
                Call call = new Call(group, key, cohort, cohort.sequence + 1, follower);
                cohort.sequence++; cohort.users++;
                if (follower) group.registeredFollowers++;
                return call;
            } finally { group.callsLock.unlock(); }
        }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            LOCK.lock();
            try { group.references--; leases--; sweep(); }
            finally { LOCK.unlock(); }
        }
    }

    static Lease acquire(RedisClientOperations redis, String prefix, SingleFlightConfig config,
            Map<String, CacheDbCacheConfiguration> caches, Map<String, Object> codecIdentities) {
        LOCK.lock();
        try {
            sweep();
            if (leases >= MAX_LEASES) throw new IllegalStateException("Spring Cache JVM manager capacity exceeded");
            Key key = new Key(redis, prefix);
            Group group = GROUPS.get(key);
            if (group != null && (!group.config.equals(config) || !group.caches.equals(caches)
                    || !group.codecIdentities.equals(codecIdentities)))
                throw new IllegalArgumentException("Managers sharing a Redis client and prefix require identical cache settings, shared explicit codec instances and single-flight limits");
            if (group == null) {
                if (GROUPS.size() >= MAX_GROUPS || reservedKeys + config.maxInFlightKeys() > MAX_RESERVED_KEYS
                        || reservedWaiters + config.maxWaiters() > MAX_RESERVED_WAITERS)
                    throw new IllegalStateException("Spring Cache JVM single-flight capacity exceeded");
                group = new Group(config, caches, codecIdentities);
                GROUPS.put(key, group);
                reservedKeys += config.maxInFlightKeys();
                reservedWaiters += config.maxWaiters();
            }
            group.references++;
            leases++;
            return new Lease(group);
        } finally { LOCK.unlock(); }
    }

    /** At most 256 pure-memory entries. Active/stuck sources remain bounded and are never evicted. */
    private static void sweep() {
        var iterator = GROUPS.entrySet().iterator();
        while (iterator.hasNext()) {
            Group group = iterator.next().getValue();
            if (!group.idle()) continue;
            reservedKeys -= group.config.maxInFlightKeys();
            reservedWaiters -= group.config.maxWaiters();
            iterator.remove();
        }
    }
}

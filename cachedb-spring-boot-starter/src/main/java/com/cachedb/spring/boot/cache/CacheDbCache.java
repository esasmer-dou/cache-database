package com.reactor.cachedb.spring.boot.cache;

import com.reactor.cachedb.core.cache.SingleFlightLoader;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.codec.CacheValueCodec;
import com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.io.IOException;
import java.util.ConcurrentModificationException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;

/** Synchronous method cache: local hits do not contact Redis; misses are atomically validated. */
public final class CacheDbCache implements Cache {
    private static final int MAX_ATTEMPTS = 3;
    private record Lookup(CacheDbCacheStore.State state, String payload) {}
    public record Statistics(long hits, long nearHits, long misses, long puts, long evictions, long clears,
            long failures, long fencedLoads, int inFlight, int waiters, boolean invalidationConnected) {}

    private final CacheDbCacheManager manager;
    private final String name;
    private final CacheDbCacheConfiguration config;
    private final CacheValueCodec<Object> codec;
    private final CacheDbCacheStore.Scope globalScope;
    private final CacheDbCacheStore store;
    private final CacheDbCacheInvalidationCoordinator.Partition near;
    private final LongAdder hits = new LongAdder(), nearHits = new LongAdder(), misses = new LongAdder(), puts = new LongAdder();
    private final LongAdder evictions = new LongAdder(), clears = new LongAdder(), failures = new LongAdder();
    private final LongAdder fencedLoads = new LongAdder();

    CacheDbCache(CacheDbCacheManager manager, String name, CacheDbCacheConfiguration config,
            CacheValueCodec<Object> codec, CacheDbCacheInvalidationCoordinator.Partition near) {
        this.manager = manager; this.name = name; this.config = config; this.codec = codec; this.near = near;
        store = new CacheDbCacheStore(manager.redis(), config, manager.invalidationCoordinator());
        globalScope = config.tenantScoped() ? null : scoped("global");
    }

    @Override public String getName() { return name; }
    @Override public Object getNativeCache() { return manager.redis().commands(); }

    @Override public ValueWrapper get(Object key) {
        var scope = scope();
        String encoded = CacheDbCacheKeys.encode(key);
        return operation(() -> wrapper(read(scope, encoded).payload));
    }
    @Override public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        Object value = wrapper == null ? null : wrapper.get();
        if (value != null && type != null && !type.isInstance(value))
            throw new IllegalStateException("Spring Cache value type does not match the requested type");
        @SuppressWarnings("unchecked") T result = (T) value;
        return result;
    }

    @Override public <T> T get(Object key, Callable<T> valueLoader) {
        Objects.requireNonNull(valueLoader, "valueLoader");
        var scope = scope();
        String encoded = CacheDbCacheKeys.encode(key);
        Callable<T> contextual = CacheCausalContext.wrap(CacheTenantContext.wrapCallable(valueLoader));
        Lookup hit = operation(() -> local(scope, encoded));
        if (hit != null) return operation(() -> decoded(hit.payload));
        // Register before Redis so a delayed miss can consume an already-finished concurrent load.
        try (var call = manager.enterLoad(scope.stem() + ":" + encoded)) {
            Lookup initial = operation(() -> read(scope, encoded));
            if (initial.payload != null) return operation(() -> decoded(initial.payload));
            String payload = call.load(initial.state.nearKey(scope, encoded), () -> operation(() -> {
                // Only the local cache is rechecked; no second Redis miss is issued.
                Lookup local = local(scope, encoded);
                if (local != null) { call.checkPayload(local.payload); return local.payload; }
                T loaded;
                try { loaded = contextual.call(); }
                catch (Exception failure) { throw new ValueRetrievalException("<redacted>", () -> null, failure); }
                manager.requireOpen();
                String wire = encode(loaded);
                call.checkPayload(wire);
                String mode = loaded == null && !config.allowNullValues() ? "CHECK" : "LOAD";
                invalidateLocal(scope, encoded);
                try {
                    var result = store.execute(scope, encoded, initial.state.generation(), initial.state, mode, wire);
                    if (result.status() != 1) {
                        fencedLoads.increment();
                        throw new ConcurrentModificationException("Spring Cache changed while loading; stale result was not cached");
                    }
                    if (!mode.equals("CHECK")) puts.increment();
                    return wire;
                } finally { invalidateLocal(scope, encoded); }
            }));
            return operation(() -> decoded(payload));
        }
    }

    @Override public void put(Object key, Object value) { write(key, value, false); }
    @Override public ValueWrapper putIfAbsent(Object key, Object value) { return write(key, value, true); }
    private ValueWrapper write(Object key, Object value, boolean absent) {
        var scope = scope();
        String encoded = CacheDbCacheKeys.encode(key);
        if (value == null && !config.allowNullValues())
            throw new IllegalArgumentException("Null values are disabled for this Spring cache");
        return operation(() -> {
            String wire = encode(value);
            CacheDbCacheStore.Generation carry = null;
            invalidateLocal(scope, encoded);
            try {
                for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                    var ticket = manager.invalidationCoordinator().beforeFetch(near, scope.hash(), encoded);
                    var result = store.execute(scope, encoded, carry == null ? ticket.hint() : carry,
                            null, absent ? "NX" : "PUT", wire);
                    near.observe(ticket, result.hint());
                    if (result.status() == 0) { carry = result.hint(); continue; }
                    if (result.status() == 2) { validateWire(result.payload()); return wrapper(result.payload()); }
                    if (result.status() != 1) throw contention();
                    puts.increment();
                    return null;
                }
                throw contention();
            } finally { invalidateLocal(scope, encoded); }
        });
    }
    @Override public void evict(Object key) { evictIfPresent(key); }
    @Override public boolean evictIfPresent(Object key) {
        var scope = scope();
        String encoded = CacheDbCacheKeys.encode(key);
        return operation(() -> {
            CacheDbCacheStore.Generation carry = null;
            invalidateLocal(scope, encoded);
            try {
                for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                    var ticket = manager.invalidationCoordinator().beforeFetch(near, scope.hash(), encoded);
                    var result = store.execute(scope, encoded, carry == null ? ticket.hint() : carry, null, "EVICT", "");
                    near.observe(ticket, result.hint());
                    if (result.status() == 0) { carry = result.hint(); continue; }
                    if (result.status() != 1) throw contention();
                    evictions.increment();
                    return result.removed();
                }
                throw contention();
            } finally { invalidateLocal(scope, encoded); }
        });
    }
    @Override public void clear() {
        var scope = scope();
        operation(() -> {
            manager.invalidationCoordinator().scope(name, scope.hash());
            try { store.clear(scope); clears.increment(); return null; }
            finally { manager.invalidationCoordinator().scope(name, scope.hash()); }
        });
    }
    @Override public boolean invalidate() { clear(); return false; }

    private Lookup local(CacheDbCacheStore.Scope scope, String encoded) {
        var entry = near.lookup(scope.hash(), encoded);
        if (entry == null) return null;
        nearHits.increment(); hits.increment();
        return new Lookup(entry.state(), entry.payload());
    }
    private Lookup read(CacheDbCacheStore.Scope scope, String encoded) {
        Lookup cached = local(scope, encoded);
        if (cached != null) return cached; // Genuine zero-RTT path: no metadata/client/Redis calls.
        CacheDbCacheStore.Generation carry = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            long started = System.nanoTime();
            // Captured before EVAL, never after the fetch: KEY/CLEAR/disconnect reject late fill.
            var ticket = manager.invalidationCoordinator().beforeFetch(near, scope.hash(), encoded);
            var result = store.execute(scope, encoded, carry == null ? ticket.hint() : carry, null, "READ", "");
            near.observe(ticket, result.hint());
            if (result.status() == 0) { carry = result.hint(); continue; }
            if (result.status() != 2) throw new IllegalStateException("Invalid Spring Cache read status");
            if (result.payload() == null) misses.increment();
            else {
                validateWire(result.payload());
                long elapsed = (System.nanoTime() - started + 999999) / 1000000;
                near.admit(ticket, result.state(), result.payload(), result.ttlMillis() - elapsed);
                hits.increment();
            }
            return new Lookup(result.state(), result.payload());
        }
        throw contention();
    }

    private CacheDbCacheStore.Scope scope() {
        manager.requireOpen();
        if (CacheCausalContext.hasRequirements())
            throw new IllegalStateException("Spring Cache cannot satisfy an entity causal context; use the versioned repository");
        if (globalScope != null) return globalScope;
        String tenant = CacheTenantContext.currentTenant().orElseThrow(() -> new CacheTenantAccessDeniedException(name));
        CacheDbCacheKeys.unicode(tenant);
        return scoped("t:" + tenant);
    }
    private CacheDbCacheStore.Scope scoped(String owner) {
        String hash = CacheDbCacheKeys.hash(name.length() + ":" + name + ":" + owner);
        return new CacheDbCacheStore.Scope(name, hash, manager.prefix() + ":scache:" + name + ":{" + hash + "}");
    }
    private void invalidateLocal(CacheDbCacheStore.Scope scope, String key) {
        manager.invalidationCoordinator().key(name, scope.hash(), key);
    }

    private String encode(Object value) {
        if (value == null) return "0";
        try {
            String json = Objects.requireNonNull(codec.encodeValue(value), "Cache codec returned null");
            if (json.length() >= config.maxValueBytes())
                throw new IllegalArgumentException("Spring Cache value exceeds configured byte limit");
            String wire = "1" + json;
            validateWire(wire);
            return wire;
        } catch (IOException failure) { throw new IllegalArgumentException("Spring Cache value encoding failed", failure); }
    }
    private void validateWire(String wire) {
        if (wire.isEmpty() || wire.length() > config.maxValueBytes()
                || !(wire.equals("0") || wire.charAt(0) == '1'))
            throw new IllegalArgumentException("Invalid or oversized Spring Cache wire value");
        int bytes = 0;
        for (int i = 0; i < wire.length(); i++) {
            char c = wire.charAt(i);
            if (c < 128) bytes++;
            else if (c < 2048) bytes += 2;
            else if (Character.isHighSurrogate(c)) {
                if (++i == wire.length() || !Character.isLowSurrogate(wire.charAt(i)))
                    throw new IllegalArgumentException("Malformed Unicode cache value");
                bytes += 4;
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Malformed Unicode cache value");
            else bytes += 3;
            if (bytes > config.maxValueBytes())
                throw new IllegalArgumentException("Spring Cache value exceeds configured UTF-8 byte limit");
        }
        if (wire.equals("0") && !config.allowNullValues())
            throw new IllegalStateException("Stored null is incompatible with this Spring cache configuration");
    }
    private <T> T decoded(String wire) {
        if (wire.equals("0")) return null;
        // L1 and single-flight wire values are immutable and were validated before admission/publication.
        try {
            @SuppressWarnings("unchecked") T value = (T) Objects.requireNonNull(codec.decodeValue(wire.substring(1)),
                    "Non-null cache wire decoded to null");
            return value;
        } catch (IOException failure) { throw new IllegalStateException("Spring Cache value decoding failed", failure); }
    }
    private ValueWrapper wrapper(String wire) { return wire == null ? null : new SimpleValueWrapper(decoded(wire)); }
    private <T> T operation(java.util.function.Supplier<T> task) {
        try { return manager.operation(task); }
        catch (RuntimeException failure) { failures.increment(); throw failure; }
    }
    private static ConcurrentModificationException contention() {
        return new ConcurrentModificationException("Spring Cache scope changed during 3 attempts; retry at the application boundary");
    }
    public Statistics statistics() {
        SingleFlightLoader<String, String> flights = manager.flights();
        return new Statistics(hits.sum(), nearHits.sum(), misses.sum(), puts.sum(), evictions.sum(), clears.sum(), failures.sum(),
                fencedLoads.sum(), flights.inFlightCount(), flights.waiterCount(), manager.invalidationConnected());
    }
}

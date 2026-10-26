package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.cache.NearCachePolicy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import redis.clients.jedis.JedisPubSub;

/** One opt-in subscription per runtime. Disconnects invalidate all entries before reconnect. */
public final class RedisNearCacheCoordinator implements AutoCloseable {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private final RedisClientOperations redis;
    private final String channel;
    private final Map<String, Store> stores = new ConcurrentHashMap<>();
    private volatile boolean connected;
    private volatile boolean closed;
    private volatile JedisPubSub subscription;
    private Thread thread;
    private long reservedBytes;
    private int reservedEntries;

    public RedisNearCacheCoordinator(RedisClientOperations redis, RedisKeyStrategy keys) {
        this.redis = Objects.requireNonNull(redis);
        channel = keys.nearInvalidationChannel();
    }

    public synchronized Store register(String namespace, NearCachePolicy policy) {
        if (closed) throw new IllegalStateException("Near cache is closed");
        var existing = stores.get(namespace);
        if (existing != null) {
            if (!existing.policy.equals(policy)) throw new IllegalArgumentException("Conflicting near-cache policies for " + namespace);
            return existing;
        }
        if (!policy.enabled()) throw new IllegalArgumentException("Near cache must be enabled");
        if (stores.size() >= 256 || reservedBytes + policy.maxBytes() > MAX_BYTES
                || reservedEntries + policy.maxEntries() > 64_000)
            throw new IllegalArgumentException("Runtime near-cache budget exceeded (64 MiB / 64000 entries)");
        var store = new Store(policy);
        stores.put(namespace, store);
        reservedBytes += policy.maxBytes(); reservedEntries += policy.maxEntries();
        if (thread == null) {
            thread = new Thread(this::listen, "cachedb-near-invalidation");
            thread.setDaemon(true); thread.start();
        }
        return store;
    }

    public boolean connected() { return connected && !closed; }

    private void listen() {
        long delay = 100;
        while (!closed) {
            var listener = new JedisPubSub() {
                @Override public void onSubscribe(String topic, int count) {
                    clear(); connected = !closed;
                    if (closed) unsubscribe();
                }
                @Override public void onMessage(String topic, String message) {
                    if (message == null || message.length() > 4096) { clear(); return; }
                    int split = message.indexOf(':');
                    if (split < 0) { clear(); return; }
                    try {
                        String namespace = decode(message.substring(0, split));
                        var store = stores.get(namespace);
                        if (store != null) store.invalidate(decode(message.substring(split + 1)));
                    } catch (IllegalArgumentException bad) { clear(); }
                }
            };
            subscription = listener;
            try { redis.commands().subscribe(listener, channel); }
            catch (RuntimeException failure) {
                if (!closed) System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                        "Near-cache invalidation disconnected; Redis fallback remains active");
            } finally { connected = false; clear(); subscription = null; }
            if (!closed) try { Thread.sleep(delay); delay = Math.min(5000, delay * 2); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
        }
    }

    public static String message(String namespace, Object id) {
        String value = encode(namespace) + ':' + encode(String.valueOf(id));
        if (value.length() > 4096) throw new IllegalArgumentException("Near-cache identity exceeds 4096 characters");
        return value;
    }
    private static String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
    private void clear() { stores.values().forEach(Store::clear); }
    @Override public void close() {
        closed = true; connected = false; clear();
        var listener = subscription;
        if (listener != null) try { listener.unsubscribe(); } catch (RuntimeException ignored) { }
        if (thread != null) thread.interrupt();
    }

    public static final class Store {
        private record Entry(String payload, long expiresAt, long bytes) {}
        private final NearCachePolicy policy;
        private final Segment[] segments;
        private final AtomicLong epoch = new AtomicLong();
        Store(NearCachePolicy policy) {
            this.policy = policy;
            int count = Math.min(16, policy.maxEntries());
            segments = new Segment[count];
            for (int i = 0; i < count; i++) segments[i] = new Segment(
                    policy.maxEntries() / count + (i < policy.maxEntries() % count ? 1 : 0),
                    policy.maxBytes() / count);
        }
        private Segment segment(String id) { return segments[(id.hashCode() & Integer.MAX_VALUE) % segments.length]; }
        public long epoch() { return epoch.get(); }
        public String get(String id) {
            var segment = segment(id);
            synchronized (segment) {
                var entry = segment.entries.get(id);
                if (entry == null) return null;
                if (System.nanoTime() - entry.expiresAt >= 0) { segment.remove(id); return null; }
                return entry.payload;
            }
        }
        public void put(String id, String payload, long redisTtlMillis, long observedEpoch) {
            if (redisTtlMillis == -2 || redisTtlMillis == 0 || epoch.get() != observedEpoch) return;
            long ttl = policy.ttlSeconds() * 1000L;
            if (redisTtlMillis > 0) ttl = Math.min(ttl, redisTtlMillis);
            long bytes = 512L + 2L * (id.length() + payload.length());
            var segment = segment(id);
            if (bytes > segment.maxBytes) return;
            synchronized (segment) {
                if (epoch.get() != observedEpoch) return;
                segment.remove(id);
                while (!segment.entries.isEmpty() && (segment.entries.size() >= segment.capacity
                        || segment.bytes + bytes > segment.maxBytes))
                    segment.remove(segment.entries.keySet().iterator().next());
                segment.entries.put(id, new Entry(payload, System.nanoTime() + ttl * 1_000_000L, bytes));
                segment.bytes += bytes;
            }
            if (epoch.get() != observedEpoch) { synchronized (segment) { segment.remove(id); } }
        }
        public void invalidate(String id) {
            epoch.incrementAndGet(); var segment = segment(id);
            synchronized (segment) { segment.remove(id); }
        }
        public void clear() {
            epoch.incrementAndGet();
            for (var segment : segments) synchronized (segment) { segment.entries.clear(); segment.bytes = 0; }
        }
        private static final class Segment {
            final int capacity; final long maxBytes;
            final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(); long bytes;
            Segment(int capacity, long maxBytes) { this.capacity = capacity; this.maxBytes = maxBytes; }
            void remove(String id) { var old = entries.remove(id); if (old != null) bytes -= old.bytes; }
        }
    }
}

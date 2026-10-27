package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.cache.NearCachePolicy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import redis.clients.jedis.JedisPubSub;

/** One opt-in subscription per runtime. Disconnects invalidate all entries before reconnect. */
public final class RedisNearCacheCoordinator implements AutoCloseable {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final AtomicLong COORDINATOR_SEQUENCE = new AtomicLong();
    private final String refreshThreadPrefix = "cachedb-near-refresh-" + COORDINATOR_SEQUENCE.incrementAndGet() + "-";
    private final Semaphore refreshBudget = new Semaphore(258);
    private volatile ThreadPoolExecutor refreshes;
    private record RefreshKey(Store store, String id) {}
    private final Set<RefreshKey> refreshing = ConcurrentHashMap.newKeySet();
    private final RedisClientOperations redis;
    private final String channel;
    private final Map<String, Store> stores = new ConcurrentHashMap<>();
    private volatile boolean connected;
    private volatile boolean closed;
    private volatile JedisPubSub subscription;
    private Thread thread;
    private long reservedBytes;
    private int reservedEntries;
    private final AtomicLong refreshRejected = new AtomicLong(), refreshFailures = new AtomicLong();
    public long rejectedRefreshes() { return refreshRejected.get(); }
    public long failedRefreshes() { return refreshFailures.get(); }

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

    public void refresh(Store store, String id, Runnable task) {
        if (!connected()) return;
        Objects.requireNonNull(store, "store"); Objects.requireNonNull(task, "task");
        if (id == null || id.length() > 4096) throw new IllegalArgumentException("Invalid refresh identity");
        if (!refreshBudget.tryAcquire()) { refreshRejected.incrementAndGet(); return; }
        var key = new RefreshKey(store, id);
        if (!refreshing.add(key)) { refreshBudget.release(); return; }
        var work = new RefreshTask(key, task);
        try {
            synchronized (this) {
                if (closed) { work.release(); return; }
                if (refreshes == null) {
                    var sequence = new AtomicLong();
                    refreshes = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
                            new ArrayBlockingQueue<>(256), command -> {
                                Thread worker = new Thread(command, refreshThreadPrefix + sequence.incrementAndGet());
                                worker.setDaemon(true); return worker;
                            }, new ThreadPoolExecutor.AbortPolicy());
                }
                refreshes.execute(work);
            }
        } catch (RejectedExecutionException full) { work.release(); refreshRejected.incrementAndGet(); }
    }

    String refreshThreadPrefix() { return refreshThreadPrefix; }

    private final class RefreshTask implements Runnable {
        private final RefreshKey key;
        private final Runnable task;
        private final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        private RefreshTask(RefreshKey key, Runnable task) { this.key = key; this.task = task; }
        @Override public void run() {
            try { if (connected()) task.run(); }
            catch (RuntimeException failure) {
                refreshFailures.incrementAndGet();
                System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                        "Near-cache refresh failed; hard TTL remains active (" + failure.getClass().getSimpleName() + ")");
            } finally { release(); }
        }
        private void release() {
            if (released.compareAndSet(false, true)) { refreshing.remove(key); refreshBudget.release(); }
        }
    }

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
        ThreadPoolExecutor executor;
        synchronized (this) {
            if (closed) return;
            closed = true; connected = false; executor = refreshes;
        }
        clear();
        if (executor != null) {
            for (Runnable pending : executor.shutdownNow()) ((RefreshTask) pending).release();
            try { executor.awaitTermination(2, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        refreshing.clear();
        var listener = subscription;
        if (listener != null) try { listener.unsubscribe(); } catch (RuntimeException ignored) { }
        if (thread != null) thread.interrupt();
    }

    public static final class Store {
        public record Entry(String payload, Object decoded, long expiresAt, long softExpiresAt, long bytes, long version) {
            public boolean softExpired() { return System.nanoTime() - softExpiresAt >= 0; }
        }
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
        public Entry lookup(String id, long minVersion) {
            var segment = segment(id);
            synchronized (segment) {
                var entry = segment.entries.get(id);
                if (entry == null) return null;
                if (System.nanoTime() - entry.expiresAt >= 0) { segment.remove(id); return null; }
                segment.incrementFrequency(id);
                return entry.version < minVersion ? null : entry;
            }
        }
        public String get(String id) {
            var entry = lookup(id, 0); return entry == null ? null : entry.payload;
        }
        public Object getDecoded(String id, String expectedPayload) {
            var segment = segment(id);
            synchronized (segment) {
                var entry = segment.entries.get(id);
                if (entry == null || !entry.payload.equals(expectedPayload)) return null;
                if (System.nanoTime() - entry.expiresAt >= 0) { segment.remove(id); return null; }
                segment.incrementFrequency(id);
                return entry.decoded;
            }
        }
        public void put(String id, String payload, long redisTtlMillis, long observedEpoch) {
            put(id, payload, null, redisTtlMillis, observedEpoch);
        }
        public void put(String id, String payload, Object decoded, long redisTtlMillis, long observedEpoch) {
            put(id, payload, decoded, redisTtlMillis, observedEpoch, 0);
        }
        public void put(String id, String payload, Object decoded, long redisTtlMillis, long observedEpoch, long version) {
            if (id == null || id.length() > 4096 || payload == null) throw new IllegalArgumentException("Invalid near-cache identity/payload");
            if (redisTtlMillis == -2 || redisTtlMillis == 0 || epoch.get() != observedEpoch) return;
            long ttl = policy.ttlSeconds() * 1000L;
            if (redisTtlMillis > 0) ttl = Math.min(ttl, redisTtlMillis);
            long bytes = 512L + 2L * (id.length() + payload.length());
            if (decoded != null) bytes += 512L + 4L * payload.length();
            var segment = segment(id);
            if (bytes > segment.maxBytes) return;
            synchronized (segment) {
                if (epoch.get() != observedEpoch) return;
                var previous = segment.entries.get(id);
                if (previous != null && previous.version > version) return;
                segment.remove(id);
                segment.incrementFrequency(id);
                while (!segment.entries.isEmpty() && (segment.entries.size() >= segment.capacity
                        || segment.bytes + bytes > segment.maxBytes))
                    segment.evict(id);
                long now = System.nanoTime();
                long expires = now + ttl * 1_000_000L;
                long soft = policy.softTtlSeconds() == 0 ? expires : Math.min(expires, now + policy.softTtlSeconds() * 1_000_000_000L);
                segment.entries.put(id, new Entry(payload, decoded, expires, soft, bytes, version));
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
            for (var segment : segments) synchronized (segment) {
                segment.entries.clear(); segment.bytes = 0; Arrays.fill(segment.frequencies, 0); segment.samples = 0;
            }
        }
        private static final class Segment {
            final int capacity; final long maxBytes;
            final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true); long bytes;
            final long[] frequencies;
            int samples;
            Segment(int capacity, long maxBytes) {
                this.capacity = capacity; this.maxBytes = maxBytes;
                int size = 16; while (size < capacity && size < 4096) size <<= 1;
                frequencies = new long[size];
            }
            int counter(String id, int seed) {
                int hash = id.hashCode() ^ (0x9e3779b9 * seed);
                hash ^= hash >>> 16; hash *= 0x7feb352d; hash ^= hash >>> 15;
                return hash & (frequencies.length * 16 - 1);
            }
            void incrementFrequency(String id) {
                for (int seed = 1; seed <= 4; seed++) {
                    int index = counter(id, seed), shift = (index & 15) * 4;
                    if (((frequencies[index >>> 4] >>> shift) & 15) < 15) frequencies[index >>> 4] += 1L << shift;
                }
                if (++samples >= capacity * 10) {
                    for (int i = 0; i < frequencies.length; i++) frequencies[i] = (frequencies[i] >>> 1) & 0x7777777777777777L;
                    samples >>>= 1;
                }
            }
            int estimateFrequency(String id) {
                int min = 15;
                for (int seed = 1; seed <= 4; seed++) {
                    int index = counter(id, seed);
                    min = Math.min(min, (int) (frequencies[index >>> 4] >>> ((index & 15) * 4)) & 15);
                }
                return min;
            }
            void evict(String incoming) {
                int frequency = estimateFrequency(incoming);
                for (int probe = 0; probe < 4; probe++) {
                    String victim = entries.keySet().iterator().next();
                    if (estimateFrequency(victim) <= frequency + 1) { remove(victim); return; }
                    entries.get(victim);
                }
                remove(entries.keySet().iterator().next());
            }
            void remove(String id) { var old = entries.remove(id); if (old != null) bytes -= old.bytes; }
        }
    }
}

package com.reactor.cachedb.spring.boot.cache;

import com.reactor.cachedb.core.cache.NearCachePolicy;
import com.reactor.cachedb.redis.RedisClientOperations;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import redis.clients.jedis.JedisPubSub;

/** Scope-aware pub/sub lifecycle with bounded generation hints and late-fill fencing. */
public final class CacheDbCacheInvalidationCoordinator implements AutoCloseable {
    private static final int MAX_SCOPES = 4096, MAX_SCOPES_PER_CACHE = 256;
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private record Session(boolean ready) {}
    record Cached(CacheDbCacheStore.State state, String payload, long expires, long softExpires, long bytes) {}
    private record NearKey(ScopeStamp scope, String encoded) {}
    private static final class ScopeStamp {
        final String hash;
        volatile CacheDbCacheStore.Generation generation;
        long expires;
        boolean live = true;
        ScopeStamp(String hash, long expires) { this.hash = hash; this.expires = expires; }
    }
    static final class Ticket {
        final Partition partition;
        final Session session;
        final ScopeStamp scope;
        final String encoded;
        final long epoch;
        Ticket(Partition partition, Session session, ScopeStamp scope, String encoded, long epoch) {
            this.partition = partition; this.session = session; this.scope = scope;
            this.encoded = encoded; this.epoch = epoch;
        }
        CacheDbCacheStore.Generation hint() { return scope == null ? null : scope.generation; }
    }
    private static final class Attempt {
        JedisPubSub listener;
        volatile boolean acknowledged;
        volatile boolean poisoned;
    }

    final class Partition {
        final NearCachePolicy policy;
        final long generationNanos;
        final ReentrantLock lock = new ReentrantLock();
        final LinkedHashMap<String, ScopeStamp> scopes = new LinkedHashMap<>(16, 0.75f, true);
        final LinkedHashMap<NearKey, Cached> entries = new LinkedHashMap<>(16, 0.75f, true);
        final long[] epochs = new long[16];
        long bytes;

        Partition(CacheDbCacheConfiguration config) {
            policy = config.nearCache();
            generationNanos = TimeUnit.MILLISECONDS.toNanos(policy.enabled()
                    ? policy.ttlSeconds() * 1000L : Math.min(60000, config.ttl().toMillis()));
        }
        private int stripe(String encoded) { return encoded.hashCode() & 15; }
        private ScopeStamp current(String hash, long now) {
            ScopeStamp scope = scopes.get(hash);
            if (scope != null && now - scope.expires >= 0) { removeScope(hash); return null; }
            return scope;
        }
        private void removeScope(String hash) {
            ScopeStamp old = scopes.remove(hash);
            if (old != null) { old.live = false; scopeBudget.release(); }
        }
        private ScopeStamp newScope(String hash, long now) {
            if (scopes.size() >= MAX_SCOPES_PER_CACHE)
                removeScope(scopes.keySet().iterator().next());
            if (!scopeBudget.tryAcquire()) {
                if (scopes.isEmpty()) return null;
                removeScope(scopes.keySet().iterator().next());
                if (!scopeBudget.tryAcquire()) return null;
            }
            ScopeStamp created = new ScopeStamp(hash, now + generationNanos);
            scopes.put(hash, created);
            return created;
        }
        Cached lookup(String hash, String encoded) {
            if (!policy.enabled()) return null;
            Session observed = session;
            if (!observed.ready) return null;
            lock.lock();
            try {
                if (session != observed || !observed.ready) return null;
                long now = System.nanoTime();
                ScopeStamp scope = current(hash, now);
                if (scope == null || scope.generation == null) return null;
                NearKey key = new NearKey(scope, encoded);
                Cached entry = entries.get(key);
                if (entry == null) return null;
                if (now - entry.expires >= 0) { removeEntry(key); return null; }
                if (now - entry.softExpires >= 0) return null;
                return session == observed ? entry : null;
            } finally { lock.unlock(); }
        }
        Ticket beforeFetch(String hash, String encoded) {
            Session observed = session;
            if (!observed.ready) return new Ticket(this, observed, null, encoded, 0);
            lock.lock();
            try {
                if (session != observed || !observed.ready) return new Ticket(this, observed, null, encoded, 0);
                long now = System.nanoTime();
                ScopeStamp scope = current(hash, now);
                if (scope == null) scope = newScope(hash, now);
                return new Ticket(this, observed, scope, encoded, epochs[stripe(encoded)]);
            } finally { lock.unlock(); }
        }
        private boolean current(Ticket ticket) {
            return ticket.scope != null && ticket.session == session && session.ready
                    && ticket.scope.live && scopes.get(ticket.scope.hash) == ticket.scope;
        }
        void observe(Ticket ticket, CacheDbCacheStore.Generation generation) {
            lock.lock();
            try {
                if (!current(ticket)) return;
                ScopeStamp scope = ticket.scope;
                if (scope.generation != null && !scope.generation.equals(generation)) {
                    removeScope(scope.hash);
                    scope = newScope(scope.hash, System.nanoTime());
                    if (scope == null) return;
                }
                scope.generation = generation;
                scope.expires = System.nanoTime() + generationNanos;
            } finally { lock.unlock(); }
        }
        boolean admit(Ticket ticket, CacheDbCacheStore.State state, String payload, long remainingMillis) {
            if (!policy.enabled() || payload == null || remainingMillis <= 0) return false;
            lock.lock();
            try {
                if (!current(ticket) || epochs[stripe(ticket.encoded)] != ticket.epoch
                        || ticket.scope.generation != null && !ticket.scope.generation.equals(state.generation())) {
                    rejectedFills.increment();
                    return false;
                }
                ScopeStamp scope = ticket.scope;
                scope.generation = state.generation();
                long now = System.nanoTime();
                scope.expires = now + generationNanos;
                long ttl = Math.min(remainingMillis, policy.ttlSeconds() * 1000L);
                long weight = 768L + 2L * (scope.hash.length() + ticket.encoded.length() + payload.length());
                if (weight > policy.maxBytes()) return false;
                var stale = entries.entrySet().iterator();
                for (int drained = 0; drained < 16 && stale.hasNext(); drained++) {
                    var oldest = stale.next();
                    if (oldest.getKey().scope.live && now - oldest.getValue().expires < 0) break;
                    bytes -= oldest.getValue().bytes;
                    stale.remove();
                }
                NearKey key = new NearKey(scope, ticket.encoded);
                removeEntry(key);
                while (!entries.isEmpty() && (entries.size() >= policy.maxEntries() || bytes + weight > policy.maxBytes()))
                    removeEntry(entries.keySet().iterator().next());
                long expires = now + TimeUnit.MILLISECONDS.toNanos(ttl);
                long soft = policy.softTtlSeconds() == 0 ? expires
                        : Math.min(expires, now + TimeUnit.SECONDS.toNanos(policy.softTtlSeconds()));
                entries.put(key, new Cached(state, payload, expires, soft, weight));
                bytes += weight;
                return true;
            } finally { lock.unlock(); }
        }
        void key(String hash, String encoded) {
            lock.lock();
            try {
                int stripe = stripe(encoded);
                if (++epochs[stripe] == Long.MIN_VALUE) resetLocked();
                ScopeStamp scope = scopes.get(hash);
                if (scope != null) removeEntry(new NearKey(scope, encoded));
            } finally { lock.unlock(); }
        }
        void scope(String hash) {
            lock.lock();
            try { removeScope(hash); } // Invalidates the scope epoch by identity; old entries are unreachable.
            finally { lock.unlock(); }
        }
        void generation(String hash, CacheDbCacheStore.Generation generation) {
            lock.lock();
            try {
                // Notifications never grow a tenant catalog for scopes this manager has not used.
                if (!scopes.containsKey(hash)) return;
                removeScope(hash);
                ScopeStamp fresh = newScope(hash, System.nanoTime());
                if (fresh != null) fresh.generation = generation;
            } finally { lock.unlock(); }
        }
        private void removeEntry(NearKey key) {
            Cached old = entries.remove(key);
            if (old != null) bytes -= old.bytes;
        }
        private void resetLocked() {
            // Scope identities, not a reset numeric counter, protect outstanding tickets from ABA.
            for (ScopeStamp scope : scopes.values()) scope.live = false;
            scopeBudget.release(scopes.size());
            scopes.clear(); entries.clear(); bytes = 0; Arrays.fill(epochs, 0);
        }
        void reset() {
            lock.lock();
            try { resetLocked(); } finally { lock.unlock(); }
        }
    }

    private final RedisClientOperations redis;
    private final String prefix;
    private final String channel;
    private final String threadName = "cachedb-scache-invalidation-" + SEQUENCE.incrementAndGet();
    private final Map<String, Partition> partitions = new HashMap<>();
    private final Semaphore scopeBudget = new Semaphore(MAX_SCOPES);
    private final ReentrantLock lifecycle = new ReentrantLock();
    private final AtomicBoolean started = new AtomicBoolean(), closed = new AtomicBoolean();
    private final LongAdder rejectedFills = new LongAdder(), notifications = new LongAdder(), disconnects = new LongAdder();
    private volatile Session session = new Session(false);
    private volatile Attempt attempt;
    private volatile Thread worker;
    private boolean pubsubHealthy, trackingRequired, trackingHealthy;
    private boolean frozen;
    private long closeUntil;
    private final AtomicReference<IllegalStateException> cleanupFailure = new AtomicReference<>();

    CacheDbCacheInvalidationCoordinator(RedisClientOperations redis, String prefix) {
        this.redis = Objects.requireNonNull(redis);
        this.prefix = prefix;
        channel = prefix + ":scache:invalidation";
    }
    Partition register(String name, CacheDbCacheConfiguration config) {
        if (frozen || partitions.size() >= 256 || partitions.containsKey(name))
            throw new IllegalStateException("Invalid or frozen Spring Cache invalidation registration");
        Partition partition = new Partition(config);
        partitions.put(name, partition);
        return partition;
    }
    void freezeAndStartIfNearEnabled() {
        frozen = true;
        if (partitions.values().stream().anyMatch(partition -> partition.policy.enabled())) start();
    }
    private void start() {
        if (closed.get() || !started.compareAndSet(false, true)) return;
        lifecycle.lock();
        try {
            if (closed.get()) return;
            Thread thread = Thread.ofVirtual().name(threadName).unstarted(this::listen);
            worker = thread;
            thread.start();
        } finally { lifecycle.unlock(); }
    }
    Ticket beforeFetch(Partition partition, String hash, String encoded) {
        start();
        return partition.beforeFetch(hash, encoded);
    }
    String channel() { return channel; }
    public boolean connected() { return !closed.get() && session.ready; }
    public boolean listenerAlive() { Thread listener = worker; return listener != null && listener.isAlive(); }
    public boolean cleanupFailed() { return cleanupFailure.get() != null; }
    private IllegalStateException failCleanup(String reason) {
        cleanupFailure.compareAndSet(null, new IllegalStateException(reason));
        lifecycle.lock();
        try { closed.set(true); pubsubHealthy = false; transition(); }
        finally { lifecycle.unlock(); }
        return cleanupFailure.get();
    }
    public long rejectedFills() { return rejectedFills.sum(); }
    public long receivedNotifications() { return notifications.sum(); }
    public long disconnectCount() { return disconnects.sum(); }
    public int generationScopes() { return MAX_SCOPES - scopeBudget.availablePermits(); }

    static String message(String kind, String cacheName, String scope, String encoded) {
        if (!(kind.equals("K") || kind.equals("C")) || scope.length() != 64 || encoded.length() > 1024)
            throw new IllegalArgumentException("Invalid Spring Cache notification identity");
        CacheDbCacheKeys.checkName(cacheName);
        return "sc1|" + kind + "|" + cacheName + "|" + scope + "|" + encoded;
    }
    void key(String cacheName, String scope, String encoded) {
        Partition partition = partitions.get(cacheName);
        if (partition != null) partition.key(scope, encoded);
    }
    void scope(String cacheName, String scope) {
        Partition partition = partitions.get(cacheName);
        if (partition != null) partition.scope(scope);
    }
    private void notification(String message) {
        if (message == null || message.length() > 4096) { flush(); return; }
        String[] fields = message.split("\\|", 6);
        if (fields.length != 5 || !fields[0].equals("sc1") || fields[3].length() != 64 || !hex(fields[3])) {
            flush(); return;
        }
        Partition partition = partitions.get(fields[2]);
        if (fields[1].equals("K")) {
            if (fields[4].isEmpty() || fields[4].length() > 1024 || !encoded(fields[4])) { flush(); return; }
            if (partition != null) partition.key(fields[3], fields[4]);
        } else if (fields[1].equals("C")) {
            if (fields[4].isEmpty()) {
                if (partition != null) partition.scope(fields[3]);
            } else {
                CacheDbCacheStore.Generation generation = generation(fields[4]);
                if (generation == null) { flush(); return; }
                if (partition != null) partition.generation(fields[3], generation);
            }
        } else { flush(); return; }
        if (partition != null) notifications.increment();
    }
    private static CacheDbCacheStore.Generation generation(String wire) {
        int split = wire.indexOf(',');
        if (split != 36 || wire.length() < 38 || wire.length() > 52) return null;
        String incarnation = wire.substring(0, split), counter = wire.substring(split + 1);
        for (int i = 0; i < incarnation.length(); i++) {
            char c = incarnation.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') return null;
            } else if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) return null;
        }
        for (int i = 0; i < counter.length(); i++)
            if (counter.charAt(i) < '0' || counter.charAt(i) > '9') return null;
        return new CacheDbCacheStore.Generation(incarnation, counter);
    }
    private static boolean hex(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) return false;
        }
        return true;
    }
    private static boolean encoded(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '-' || c == '_' || c == ':')) return false;
        }
        return true;
    }
    /** Called while lifecycle is locked; never performs Redis or source I/O. */
    private void transition() {
        session = new Session(false);
        for (Partition partition : partitions.values()) partition.reset();
        session = new Session(!closed.get() && pubsubHealthy && (!trackingRequired || trackingHealthy));
    }
    private void flush() {
        lifecycle.lock();
        try { transition(); } finally { lifecycle.unlock(); }
    }
    private void subscribed(Attempt current) {
        boolean reject;
        lifecycle.lock();
        try {
            reject = closed.get() || attempt != current || current.poisoned;
            if (!reject) { current.acknowledged = true; pubsubHealthy = true; transition(); }
        } finally { lifecycle.unlock(); }
        if (reject) {
            // Also covers a subscription established after close's bounded stop helper finished.
            try { current.listener.unsubscribe(); } catch (RuntimeException ignored) { }
        }
    }
    private void disconnected(Attempt current) {
        lifecycle.lock();
        try {
            if (attempt != current) return;
            pubsubHealthy = false; transition(); attempt = null; disconnects.increment();
        } finally { lifecycle.unlock(); }
    }
    void redisFailure() {
        lifecycle.lock();
        try {
            Attempt current = attempt;
            if (current != null) current.poisoned = true;
            pubsubHealthy = false; transition();
        } finally { lifecycle.unlock(); }
        Thread thread = worker;
        if (thread != null) thread.interrupt();
    }

    /** Once tracking is enabled, both tracking and pub/sub must be healthy. */
    public void trackingConnected() {
        lifecycle.lock();
        try { trackingRequired = true; trackingHealthy = true; transition(); }
        finally { lifecycle.unlock(); }
    }
    public void trackingDisconnected() {
        lifecycle.lock();
        try { trackingRequired = true; trackingHealthy = false; transition(); }
        finally { lifecycle.unlock(); }
    }
    /** Bounded key invalidation; transport callbacks must preserve ordering and forward failures. */
    public void trackingInvalidated(String redisKey) {
        if (redisKey == null) { flush(); return; } // Redis FLUSHDB/tracking reset.
        if (redisKey.length() > 4096) { flush(); return; }
        String head = prefix + ":scache:";
        if (!redisKey.startsWith(head)) return;
        int nameEnd = redisKey.indexOf(":{", head.length());
        if (nameEnd < 0) { flush(); return; }
        String name = redisKey.substring(head.length(), nameEnd);
        int scopeStart = nameEnd + 2, scopeEnd = redisKey.indexOf('}', scopeStart);
        if (scopeEnd != scopeStart + 64 || !hex(redisKey.substring(scopeStart, scopeEnd))) { flush(); return; }
        String hash = redisKey.substring(scopeStart, scopeEnd);
        String tail = redisKey.substring(scopeEnd + 1);
        if (tail.equals(":generation") || tail.equals(":state")) { scope(name, hash); return; }
        if (tail.startsWith(":revision:")) {
            String key = tail.substring(10);
            if (key.length() > 1024 || !encoded(key)) { flush(); return; }
            key(name, hash, key); return;
        }
        if (tail.startsWith(":g")) {
            int separator = tail.indexOf(':', 2);
            if (separator > 0) {
                String key = tail.substring(separator + 1);
                if (key.length() <= 1024 && encoded(key)) { key(name, hash, key); return; }
            }
        }
        flush();
    }
    private void listen() {
        int failures = 0;
        long backoff = 100;
        while (!closed.get() && failures < 32) {
            Attempt current = new Attempt();
            current.listener = new JedisPubSub() {
                @Override public void onSubscribe(String topic, int count) { subscribed(current); }
                @Override public void onMessage(String topic, String message) {
                    if (attempt == current && !current.poisoned && channel.equals(topic)) notification(message);
                }
            };
            lifecycle.lock();
            try { if (closed.get()) return; attempt = current; }
            finally { lifecycle.unlock(); }
            try { CacheDbCacheSubscription.listen(redis, current.listener, channel); }
            catch (CacheDbCacheSubscription.CleanupFailure failure) {
                failCleanup("Spring Cache owned subscription destruction was not confirmed");
            } catch (RuntimeException failure) {
                if (!closed.get()) System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                        "Spring Cache invalidation disconnected; local hits and admission are disabled");
            } finally { disconnected(current); }
            if (closed.get()) return;
            failures = current.acknowledged ? 0 : failures + 1;
            if (current.acknowledged) backoff = 100;
            Thread.interrupted(); // This is the owned listener, not an application thread.
            try { Thread.sleep(backoff); }
            catch (InterruptedException interrupted) { if (closed.get()) return; }
            backoff = Math.min(5000, backoff * 2);
        }
        // Exhausted initial reconnect attempts: remain fail-closed; recreate the manager operationally.
    }
    @Override public void close() {
        Thread listener;
        long until;
        lifecycle.lock();
        try {
            if (closeUntil == 0) closeUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            closed.set(true);
            pubsubHealthy = false;
            transition();
            listener = worker;
            until = closeUntil;
        } finally { lifecycle.unlock(); }
        boolean interrupted = Thread.interrupted();
        try {
            if (listener == Thread.currentThread())
                throw failCleanup("Spring Cache subscription cannot confirm its own termination");
            if (listener != null) {
                listener.interrupt();
                while (listener.isAlive()) {
                    long remaining = until - System.nanoTime();
                    if (remaining <= 0) break;
                    try { listener.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))); }
                    catch (InterruptedException stopped) { interrupted = true; }
                }
                if (listener.isAlive())
                    failCleanup("Spring Cache owned subscription did not stop within the shared two-second close budget");
            }
            IllegalStateException failure = cleanupFailure.get();
            if (failure != null) throw failure;
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}

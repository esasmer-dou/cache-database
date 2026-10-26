package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.watch.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Best-effort local mutation notifications, not CDC or an exactly-once business event log. */
final class RedisWatchRegistry<T, ID> {
    private static final long MAX_QUEUED_BYTES = 4 * 1024 * 1024;
    private static final int PARTITIONS = 4;
    private static final Dispatcher[] DISPATCHERS = new Dispatcher[PARTITIONS];
    static { for (int i = 0; i < PARTITIONS; i++) DISPATCHERS[i] = new Dispatcher(i); }
    private final EntityMetadata<T, ID> metadata;
    private final EntityCodec<T> codec;
    private final QueryEvaluator evaluator;
    private final int limit;
    private final CopyOnWriteArrayList<Watch> watches = new CopyOnWriteArrayList<>();
    private final AtomicLong dropped = new AtomicLong();
    RedisWatchRegistry(EntityMetadata<T, ID> metadata, EntityCodec<T> codec, QueryEvaluator evaluator, int limit) {
        this.metadata = metadata; this.codec = codec; this.evaluator = evaluator;
        if (limit < 1 || limit > 64) throw new IllegalArgumentException("Watcher limit must be 1..64");
        this.limit = limit;
    }
    boolean active() { return !watches.isEmpty(); }
    long dropped() { return dropped.get(); }
    synchronized CacheWatcherRegistration register(QuerySpec spec, CacheWatcherListener<T, ID> listener) {
        Objects.requireNonNull(spec); Objects.requireNonNull(listener);
        if (spec.offset() != 0 || !spec.sorts().isEmpty() || !spec.fetchPlan().includes().isEmpty())
            throw new IllegalArgumentException("Watcher observes predicate membership, not sorted windows or relations");
        if (watches.size() >= limit) throw new IllegalStateException("Watcher capacity exceeded");
        Watch watch = new Watch(spec.rootGroup(), listener, CacheTenantContext.currentTenant().orElse(null));
        watches.add(watch); return watch;
    }
    void changed(ID id, String previousPayload, String currentPayload) {
        if (!active()) return;
        long bytes = 512L + 2L * ((previousPayload == null ? 0 : previousPayload.length()) + (currentPayload == null ? 0 : currentPayload.length()));
        if (bytes > 256 * 1024) { reject(); return; }
        List<Watch> snapshot = List.copyOf(watches);
        Runnable action = () -> {
            for (Watch watch : snapshot) {
                if (!watch.active.get()) continue;
                try {
                    if (watch.tenant == null) notify(watch, id, previousPayload, currentPayload);
                    else CacheTenantContext.runWithTenant(watch.tenant, () -> notify(watch, id, previousPayload, currentPayload));
                } catch (RuntimeException failure) {
                    System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "CacheDB watcher callback failed", failure);
                }
            }
        };
        int partition = (Objects.hash(metadata.entityName(), id) & Integer.MAX_VALUE) % PARTITIONS;
        if (!DISPATCHERS[partition].submit(CacheTenantContext.wrap(action), bytes)) reject();
    }
    private void reject() {
        long count = dropped.incrementAndGet();
        if ((count & (count - 1)) == 0)
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "CacheDB watcher bounded dispatch drops: " + count);
    }
    private void notify(Watch watch, ID id, String previousPayload, String currentPayload) {
        T previous = previousPayload == null ? null : codec.fromRedisValue(previousPayload);
        T current = currentPayload == null ? null : codec.fromRedisValue(currentPayload);
        if (watch.tenant != null && ((previous != null && !watch.tenant.equals(metadata.extractTenantId(previous)))
                || (current != null && !watch.tenant.equals(metadata.extractTenantId(current))))) return;
        boolean before = previous != null && evaluator.matches(previous, codec, watch.filter);
        boolean after = current != null && evaluator.matches(current, codec, watch.filter);
        if (!before && !after) return;
        CacheChangeType type = !before ? CacheChangeType.ENTERED : !after ? CacheChangeType.EXITED : CacheChangeType.UPDATED;
        watch.listener.onEvent(new CacheChangeEvent<>(metadata.entityName(), id, type, previous, current, System.currentTimeMillis()));
    }
    private final class Watch implements CacheWatcherRegistration {
        private final String id = UUID.randomUUID().toString(), tenant;
        private final QueryNode filter;
        private final CacheWatcherListener<T, ID> listener;
        private final AtomicBoolean active = new AtomicBoolean(true);
        Watch(QueryNode filter, CacheWatcherListener<T, ID> listener, String tenant) { this.filter = filter; this.listener = listener; this.tenant = tenant; }
        public String watcherId() { return id; }
        public boolean isActive() { return active.get(); }
        public void close() { if (active.getAndSet(false)) watches.remove(this); }
    }
    private static final class Dispatcher {
        private record Task(Runnable action, long bytes) {}
        private final ArrayBlockingQueue<Task> queue = new ArrayBlockingQueue<>(256);
        private long queuedBytes;
        private boolean started;
        private final int index;
        Dispatcher(int index) { this.index = index; }
        synchronized boolean submit(Runnable action, long bytes) {
            if (queuedBytes + bytes > MAX_QUEUED_BYTES) return false;
            queuedBytes += bytes;
            if (!queue.offer(new Task(action, bytes))) { queuedBytes -= bytes; return false; }
            if (!started) {
                Thread worker = new Thread(this::run, "cachedb-watcher-" + index); worker.setDaemon(true); worker.start(); started = true;
            }
            return true;
        }
        private void run() {
            while (true) {
                try {
                    Task task = queue.take();
                    try { task.action.run(); }
                    catch (RuntimeException failure) { System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Watcher dispatch failed", failure); }
                    finally { synchronized (this) { queuedBytes -= task.bytes; } }
                } catch (InterruptedException stop) { Thread.currentThread().interrupt(); return; }
            }
        }
    }
}

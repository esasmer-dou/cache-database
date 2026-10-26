package com.reactor.cachedb.redis;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Process-local wakeups are hints only; Redis durability markers remain authoritative. */
final class LocalDurabilitySignals {
    private static final int MAX_WAITERS = 16_384;
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final ConcurrentHashMap<Key, ArrayList<Waiter>> WAITERS = new ConcurrentHashMap<>();

    static Registration register(Object client, String redisKey, long version) {
        int current;
        do {
            current = ACTIVE.get();
            if (current >= MAX_WAITERS) throw new IllegalStateException("Durability waiter capacity exceeded");
        } while (!ACTIVE.compareAndSet(current, current + 1));
        Key key = new Key(client, redisKey);
        Waiter waiter = new Waiter(version, new CompletableFuture<>());
        try {
            WAITERS.compute(key, (ignored, values) -> {
                if (values == null) values = new ArrayList<>();
                values.add(waiter);
                return values;
            });
            return new Registration(key, waiter);
        } catch (RuntimeException | Error failure) { ACTIVE.decrementAndGet(); throw failure; }
    }

    static void advance(Object client, String redisKey, long version) {
        if (ACTIVE.get() == 0) return;
        // Copy only matching bounded waiters; never run completion callbacks inside the map's key lock.
        var ready = new ArrayList<CompletableFuture<Long>>();
        WAITERS.computeIfPresent(new Key(client, redisKey), (ignored, values) -> {
            for (Waiter waiter : values) if (version >= waiter.version) ready.add(waiter.future);
            return values;
        });
        for (var future : ready) future.complete(version);
    }

    static int activeCount() { return ACTIVE.get(); }
    private record Key(Object client, String redisKey) {}
    private record Waiter(long version, CompletableFuture<Long> future) {}

    static final class Registration implements AutoCloseable {
        private final Key key;
        private final Waiter waiter;
        private boolean closed;
        Registration(Key key, Waiter waiter) { this.key = key; this.waiter = waiter; }
        CompletableFuture<Long> future() { return waiter.future; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            WAITERS.computeIfPresent(key, (ignored, values) -> {
                values.remove(waiter);
                return values.isEmpty() ? null : values;
            });
            ACTIVE.decrementAndGet();
        }
    }
}

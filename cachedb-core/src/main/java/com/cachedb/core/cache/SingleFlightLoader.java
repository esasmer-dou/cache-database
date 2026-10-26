package com.reactor.cachedb.core.cache;

import com.reactor.cachedb.core.config.SingleFlightConfig;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Bounded coordination, with no executor, global lock, or retained completed results. */
public final class SingleFlightLoader<K, V> {
    private final SingleFlightConfig config;
    private final ConcurrentHashMap<K, Flight<V>> flights = new ConcurrentHashMap<>();
    private final AtomicInteger keys = new AtomicInteger();
    private final AtomicInteger waiters = new AtomicInteger();

    public SingleFlightLoader(SingleFlightConfig config) { this.config = Objects.requireNonNull(config); }

    public V load(K key, Supplier<V> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        if (!config.enabled()) return loader.get();
        Flight<V> flight = flights.get(key);
        if (flight == null) {
            reserve(keys, config.maxInFlightKeys(), "Single-flight key capacity exceeded");
            Flight<V> candidate = new Flight<>(Thread.currentThread());
            flight = flights.putIfAbsent(key, candidate);
            if (flight == null) {
                try {
                    V result = loader.get();
                    candidate.result.complete(result);
                    return result;
                } catch (RuntimeException | Error failure) {
                    candidate.result.completeExceptionally(failure);
                    throw failure;
                } finally {
                    flights.remove(key, candidate);
                    keys.decrementAndGet();
                }
            }
            keys.decrementAndGet();
        }
        if (flight.owner == Thread.currentThread()) throw new IllegalStateException("Recursive single-flight load");
        reserve(waiters, config.maxWaiters(), "Single-flight waiter capacity exceeded");
        try {
            return flight.result.get(config.timeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Single-flight wait interrupted", interrupted);
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("Single-flight wait timed out", timeout);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failed.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Single-flight source load failed", failed.getCause());
        } finally { waiters.decrementAndGet(); }
    }

    private static void reserve(AtomicInteger count, int limit, String message) {
        int current;
        do {
            current = count.get();
            if (current >= limit) throw new IllegalStateException(message);
        } while (!count.compareAndSet(current, current + 1));
    }

    public int inFlightCount() { return keys.get(); }
    public int waiterCount() { return waiters.get(); }

    private static final class Flight<V> {
        private final Thread owner;
        private final CompletableFuture<V> result = new CompletableFuture<>();
        private Flight(Thread owner) { this.owner = owner; }
    }
}

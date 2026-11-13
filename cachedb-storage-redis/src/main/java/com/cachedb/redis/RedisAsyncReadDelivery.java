package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.async.CacheAsyncExecutor;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;

/** One bounded decoded-result owner. No worker waits for another future. */
final class RedisAsyncReadDelivery<V> {
    private final RedisAsyncReadBudget.Lease lease;
    private final ReentrantLock lock = new ReentrantLock();
    private final Result result = new Result();
    private CompletableFuture<V> source;
    private boolean bound, setupDone, producerDone, sourceDone, deliveryDone, terminal, released;
    private boolean cancelWanted;
    private int callbacks;
    private long decodedBytes, duplicateBytes;

    RedisAsyncReadDelivery(RedisAsyncReadBudget.Lease lease) {
        this.lease = Objects.requireNonNull(lease);
        lease.armDeadline(this::abort);
    }

    CompletableFuture<V> future() { return result; }

    /** Charge BEFORE decoding, including rejected/cancelled rows which would otherwise allocate first. */
    void charge(long bytes) {
        lock.lock();
        try {
            if (terminal) throw new CancellationException("Async read delivery stopped");
            if (bytes < 0 || bytes > RedisAsyncReadBudget.CONTENT_BYTES - decodedBytes)
                throw new IllegalArgumentException("Async decoded input exceeds 16 MiB");
            decodedBytes += bytes;
        } finally { lock.unlock(); }
    }

    void chargeDuplicate(long bytes) {
        lock.lock();
        try {
            if (terminal) throw new CancellationException("Async read delivery stopped");
            if (bytes < 0 || bytes > RedisAsyncReadBudget.CONTENT_BYTES - duplicateBytes)
                throw new IllegalArgumentException("Async duplicate decode input exceeds 16 MiB");
            duplicateBytes += bytes;
        } finally { lock.unlock(); }
    }

    void checkActive() {
        lock.lock();
        try { if (terminal) throw new CancellationException("Async read delivery stopped"); }
        finally { lock.unlock(); }
    }

    <R> R decode(long bytes, java.util.function.Supplier<R> decoder) {
        charge(bytes);
        return decoder.get();
    }

    void bind(CompletableFuture<V> data, CompletableFuture<Void> producer) {
        Objects.requireNonNull(data); Objects.requireNonNull(producer);
        boolean cancel;
        lock.lock();
        try {
            if (bound) throw new IllegalStateException("Async result is already bound");
            bound = true;
            source = data;
            cancel = cancelWanted;
        } finally { lock.unlock(); }
        producer.whenComplete((ignored, failure) -> {
            lock.lock();
            try { producerDone = true; }
            finally { lock.unlock(); }
            releaseIfIdle();
        });
        data.whenComplete(this::received);
        if (cancel) cancelSource(data);
    }

    void bind(CacheAsyncExecutor gate, CompletableFuture<V> data) {
        bind(data, gate.deliveryCompletion(data));
    }

    void finishSetup() {
        lock.lock();
        try { setupDone = true; }
        finally { lock.unlock(); }
        releaseIfIdle();
    }

    void failSetup(Throwable failure) {
        boolean needsBinding;
        lock.lock();
        try { needsBinding = !bound; }
        finally { lock.unlock(); }
        if (needsBinding) bind(CompletableFuture.failedFuture(failure), CompletableFuture.completedFuture(null));
        else abort(failure);
    }

    private void received(V value, Throwable failure) {
        boolean publish;
        lock.lock();
        try {
            source = null;
            sourceDone = true;
            callbacks++;
            publish = !terminal;
            if (publish) terminal = true;
        } finally { lock.unlock(); }
        try {
            if (publish) {
                lease.stopDeliveryDeadline();
                if (failure == null) result.complete(value);
                else result.completeExceptionally(unwrap(failure));
            }
        } finally {
            lock.lock();
            try { if (publish) deliveryDone = true; callbacks--; }
            finally { lock.unlock(); }
            releaseIfIdle();
        }
    }

    private boolean abort(Throwable failure) {
        CompletableFuture<V> active;
        lock.lock();
        try {
            if (terminal) return false;
            terminal = true;
            cancelWanted = true;
            callbacks++;
            active = source;
        } finally { lock.unlock(); }
        try {
            lease.stopDeliveryDeadline();
            // Publish the deadline before a third-party cancel hook can block or throw.
            if (failure instanceof CancellationException) result.cancelClaimed();
            else result.completeExceptionally(failure);
            if (active != null) cancelSource(active);
            return true;
        } finally {
            lock.lock();
            try { deliveryDone = true; callbacks--; }
            finally { lock.unlock(); }
            releaseIfIdle();
        }
    }

    private void cancelSource(CompletableFuture<V> active) {
        try { active.cancel(false); }
        catch (Throwable ignored) {
            // Producer lifetime remains authoritative; cancellation is best effort, never fake I/O completion.
        }
    }

    private void releaseIfIdle() {
        boolean release = false;
        lock.lock();
        try {
            if (!released && setupDone && bound && producerDone && sourceDone && deliveryDone && callbacks == 0) {
                released = true;
                source = null;
                release = true;
            }
        } finally { lock.unlock(); }
        if (release) lease.close();
    }

    private final class Result extends CompletableFuture<V> {
        @Override public boolean cancel(boolean interrupt) {
            return abort(new CancellationException("Async read cancelled")) || isCancelled();
        }
        boolean cancelClaimed() { return super.cancel(false); }
    }

    private static Throwable unwrap(Throwable failure) {
        while ((failure instanceof java.util.concurrent.CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
}

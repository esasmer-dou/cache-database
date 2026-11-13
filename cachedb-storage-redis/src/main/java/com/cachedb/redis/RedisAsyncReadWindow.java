package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.async.CacheAsyncExecutor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** Streaming bounded windows: never retain an allOf array of raw, possibly debatched 16-MiB replies. */
final class RedisAsyncReadWindow<T, ID> {
    record Row<T>(T value, long bytes, boolean accessed, String encoded) {
        Row(T value, long bytes, boolean accessed) { this(value, bytes, accessed, null); }
        Row {
            if (bytes < 0 || bytes > 16L * 1024 * 1024) throw new IllegalArgumentException("Invalid async row budget");
        }
    }
    private static final int WINDOW = 512;
    private List<ID> ids;
    private Function<ID, CacheAsyncExecutor.ExternalOperation<Row<T>>> load;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicInteger work = new AtomicInteger();
    private final Object[] rows;
    private final CompletableFuture<?>[] active = new CompletableFuture<?>[WINDOW];
    private final CompletableFuture<Void> physical = new CompletableFuture<>();
    private final Result result = new Result();
    private final CompletableFuture<Void> delivery = new CompletableFuture<>();
    private boolean deliveryPublished, publicationExited;
    private Runnable contextualSetup;
    private int cursor, remaining, physicalPending;
    private long bytes;
    private boolean settingUp, done, dataPublished, physicalPublished;
    private Throwable failure;

    RedisAsyncReadWindow(List<ID> ids, CacheAsyncExecutor gate,
            Function<ID, CacheAsyncExecutor.ExternalOperation<Row<T>>> load) {
        this.ids = List.copyOf(ids);
        this.load = Objects.requireNonNull(load);
        rows = new Object[ids.size()];
        contextualSetup = gate.contextual(this::openWindow);
    }

    CacheAsyncExecutor.ExternalOperation<List<Row<T>>> prepare() {
        return new CacheAsyncExecutor.ExternalOperation<>(result, physical);
    }

    void activate() { pump(); }
    CompletableFuture<Void> deliveryCompletion() { return delivery; }

    CacheAsyncExecutor.ExternalOperation<List<Row<T>>> start() {
        var operation = prepare();
        activate();
        return operation;
    }

    private void pump() {
        if (work.getAndIncrement() != 0) return;
        int missed = 1;
        do {
            try { Runnable setup = contextualSetup; if (setup != null) setup.run(); }
            catch (Throwable problem) { fail(problem); }
            // Leave the captured context before public completion/inline user callbacks.
            publish();
            missed = work.addAndGet(-missed);
        } while (missed != 0);
        publishDeliveryIfIdle();
    }

    private void openWindow() {
        int start, size;
        lock.lock();
        try {
            if (done || settingUp || remaining != 0 || physicalPending != 0) return;
            if (cursor == ids.size()) { done = true; return; }
            start = cursor;
            size = Math.min(WINDOW, ids.size() - cursor);
            cursor += size;
            remaining = size;
            settingUp = true;
        } finally { lock.unlock(); }
        try {
            for (int offset = 0; offset < size; offset++) {
                lock.lock();
                try {
                    if (done) { remaining -= size - offset; break; }
                    physicalPending++;
                } finally { lock.unlock(); }
                int slot = offset, index = start + offset;
                CacheAsyncExecutor.ExternalOperation<Row<T>> operation;
                try { operation = Objects.requireNonNull(load.apply(ids.get(index)), "row operation"); }
                catch (Throwable problem) {
                    physicalDone(null);
                    received(index, slot, null, problem);
                    continue;
                }
                boolean cancel;
                lock.lock();
                try { active[slot] = operation.result(); cancel = done; }
                finally { lock.unlock(); }
                operation.physical().whenComplete((ignored, problem) -> physicalDone(problem));
                operation.result().whenComplete((row, problem) -> received(index, slot, row, problem));
                if (cancel) operation.result().cancel(false);
            }
        } finally {
            lock.lock();
            try { settingUp = false; }
            finally { lock.unlock(); }
            publishPhysicalIfIdle();
        }
        // Handles all-L1/immediate windows without recursion, and the empty tail.
        work.incrementAndGet();
    }

    private void received(int index, int slot, Row<T> row, Throwable problem) {
        List<CompletableFuture<?>> cancel = List.of();
        lock.lock();
        try {
            active[slot] = null;
            remaining--;
            if (!done) {
                if (problem == null && row == null) problem = new IllegalStateException("Missing async row");
                if (problem == null && row.bytes() > 16L * 1024 * 1024 - bytes)
                    problem = new IllegalArgumentException("Async bulk output exceeds 16 MiB");
                if (problem != null) {
                    failure = unwrap(problem);
                    done = true;
                    Arrays.fill(rows, null);
                    cancel = activeSnapshot();
                } else {
                    bytes += row.bytes();
                    rows[index] = row;
                }
            }
        } finally { lock.unlock(); }
        cancel.forEach(future -> future.cancel(false));
        pump();
    }

    private void physicalDone(Throwable problem) {
        lock.lock();
        try { physicalPending--; }
        finally { lock.unlock(); }
        List<CompletableFuture<?>> cancel = problem == null ? List.of() : markFailure(problem);
        publishPhysicalIfIdle(); // Also precedes cancellation callbacks when a physical stage itself fails.
        cancel.forEach(future -> future.cancel(false));
        pump();
    }

    private List<CompletableFuture<?>> activeSnapshot() {
        var copy = new ArrayList<CompletableFuture<?>>(WINDOW);
        for (var future : active) if (future != null) copy.add(future);
        return copy;
    }

    private void fail(Throwable problem) {
        markFailure(problem).forEach(future -> future.cancel(false));
    }

    private List<CompletableFuture<?>> markFailure(Throwable problem) {
        lock.lock();
        try {
            if (done) return List.of();
            done = true;
            failure = unwrap(problem);
            Arrays.fill(rows, null);
            return activeSnapshot();
        } finally { lock.unlock(); }
    }

    @SuppressWarnings("unchecked")
    private void publish() {
        List<Row<T>> values = null;
        Throwable error = null;
        boolean publishData = false;
        lock.lock();
        try {
            if (done && !dataPublished) {
                dataPublished = true;
                publishData = true;
                error = failure;
                if (error == null) {
                    var copy = new ArrayList<Row<T>>(rows.length);
                    for (Object row : rows) copy.add((Row<T>) Objects.requireNonNull(row, "unresolved async row"));
                    values = List.copyOf(copy);
                    Arrays.fill(rows, null);
                }
            }
        } finally { lock.unlock(); }
        publishPhysicalIfIdle();
        if (publishData) {
            try { if (error == null) result.complete(values); else result.completeExceptionally(error); }
            finally {
                lock.lock();
                try { publicationExited = true; }
                finally { lock.unlock(); }
            }
        }
    }

    private void publishPhysicalIfIdle() {
        boolean signal = false;
        lock.lock();
        try {
            if (done && !settingUp && physicalPending == 0 && !physicalPublished) {
                physicalPublished = true;
                signal = true;
            }
        } finally { lock.unlock(); }
        if (signal) physical.complete(null);
    }

    private void publishDeliveryIfIdle() {
        boolean signal = false;
        lock.lock();
        try {
            if (!deliveryPublished && done && publicationExited && !settingUp
                    && remaining == 0 && physicalPending == 0 && work.get() == 0) {
                deliveryPublished = true;
                Arrays.fill(rows, null);
                Arrays.fill(active, null);
                ids = List.of();
                load = null;
                contextualSetup = null;
                signal = true;
            }
        } finally { lock.unlock(); }
        if (signal) delivery.complete(null);
    }

    private final class Result extends CompletableFuture<List<Row<T>>> {
        @Override public boolean cancel(boolean interrupt) {
            if (isDone()) return isCancelled();
            fail(new CancellationException("Async bulk read cancelled"));
            pump(); // Detach queued raw waiters before publishing cancellation.
            return super.cancel(false);
        }
    }

    private static Throwable unwrap(Throwable problem) {
        while ((problem instanceof java.util.concurrent.CompletionException
                || problem instanceof java.util.concurrent.ExecutionException) && problem.getCause() != null)
            problem = problem.getCause();
        return problem;
    }
}

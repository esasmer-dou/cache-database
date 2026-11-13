package com.reactor.cachedb.core.async;

import com.reactor.cachedb.core.trace.TraceContextPropagator;
import com.reactor.cachedb.core.trace.TraceContexts;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-runtime admission gate, not a global resource. Owns its default virtual-thread executor;
 * borrowed executors are never shut down. Borrowed execute() must promptly accept or throw,
 * never block, silently discard, or use caller-runs.
 *
 * Cancellation suppresses an operation which has not started. Once RUNNING, it only cancels
 * delivery: even cancel(true) does not interrupt storage or release its permit early.
 */
public final class CacheAsyncExecutor implements AutoCloseable {
    public static final int DEFAULT_MAX_CONCURRENT = 1024;
    public static final int MAX_CONCURRENT = 65_536;
    public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(2);
    private final int maxConcurrent;
    private final Executor executor;
    private final ExecutorService owned;
    private final TraceContextPropagator trace;
    private final Duration shutdownTimeout;
    private final Semaphore permits;
    // Canceled borrowed-executor queue entries still consume a dispatch slot until drained.
    // This prevents cancellation storms from growing even an unbounded borrowed queue.
    private final Semaphore dispatchSlots;
    private final ReentrantLock lifecycle = new ReentrantLock();
    private final Condition idle = lifecycle.newCondition();
    private final Set<CompletableFuture<?>> tasks = new HashSet<>();
    private final AtomicInteger externalOutstanding = new AtomicInteger();
    private final LongAdder externalAccepted = new LongAdder(), externalRejected = new LongAdder();
    private final LongAdder externalCompleted = new LongAdder(), externalFailed = new LongAdder(), externalCancelled = new LongAdder();
    private final LongAdder accepted = new LongAdder(), rejected = new LongAdder();
    private final LongAdder completed = new LongAdder(), failed = new LongAdder(), cancelled = new LongAdder();
    private volatile boolean closed;

    public CacheAsyncExecutor() {
        this(DEFAULT_MAX_CONCURRENT, TraceContexts.threadLocal());
    }

    public CacheAsyncExecutor(int maxConcurrent, TraceContextPropagator trace) {
        this(maxConcurrent, null, trace, DEFAULT_SHUTDOWN_TIMEOUT);
    }

    public CacheAsyncExecutor(int maxConcurrent, Executor executor, TraceContextPropagator trace) {
        this(maxConcurrent, Objects.requireNonNull(executor, "executor"), trace, DEFAULT_SHUTDOWN_TIMEOUT);
    }

    /** A null executor in this overload explicitly selects an owned virtual-thread executor. */
    public CacheAsyncExecutor(int maxConcurrent, Executor executor, TraceContextPropagator trace,
            Duration shutdownTimeout) {
        if (maxConcurrent < 1 || maxConcurrent > MAX_CONCURRENT)
            throw new IllegalArgumentException("maxConcurrent must be in 1..65536");
        this.trace = Objects.requireNonNull(trace, "trace");
        this.shutdownTimeout = checkedTimeout(shutdownTimeout);
        this.maxConcurrent = maxConcurrent;
        permits = new Semaphore(maxConcurrent, true);
        dispatchSlots = new Semaphore(maxConcurrent, true);
        owned = executor == null ? Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("cachedb-async-", 0).inheritInheritableThreadLocals(false).factory()) : null;
        this.executor = executor == null ? owned : executor;
    }

    public <V> CompletableFuture<V> submit(Callable<V> operation) { return submitInternal(operation, false); }

    private <V> CompletableFuture<V> submitInternal(Callable<V> operation, boolean tracked) {
        Objects.requireNonNull(operation, "operation");
        Task<V> task;
        lifecycle.lock();
        try {
            if (closed) return rejection(CacheAsyncRejectedException.Reason.CLOSED);
            if (!permits.tryAcquire()) return rejection(CacheAsyncRejectedException.Reason.SATURATED);
            if (!dispatchSlots.tryAcquire()) {
                permits.release();
                return rejection(CacheAsyncRejectedException.Reason.SATURATED);
            }
            try {
                task = new Task<>(operation);
                tasks.add(task);
                accepted.increment();
            } catch (RuntimeException | Error failure) {
                permits.release();
                dispatchSlots.release();
                return CompletableFuture.failedFuture(failure);
            }
        } finally { lifecycle.unlock(); }

        // Third-party context capture and executor dispatch must never run under the lifecycle lock.
        try {
            task.contextual = CacheAsyncContext.wrap(trace, task::invoke);
            if (closed) { task.contextual = null; task.reject(new CacheAsyncRejectedException(CacheAsyncRejectedException.Reason.CLOSED)); }
            else if (task.isCancelled()) { task.contextual = null; task.releaseDispatch(); }
            else executor.execute(task);
        } catch (RuntimeException failure) {
            task.reject(new CacheAsyncRejectedException(
                    CacheAsyncRejectedException.Reason.EXECUTOR_REJECTED, failure));
        } catch (Error fatal) {
            task.reject(fatal);
            if (!tracked) throw fatal;
            task.completeExceptionally(fatal); // An executor may throw after starting; retain the honest task lifetime.
        } finally { task.submittingThread = null; }
        return task;
    }

    /** Actual worker publication lifetime; cancellation alone never certifies physical exit. */
    public <V> ExternalOperation<V> submitTracked(Callable<V> operation) {
        CompletableFuture<V> result = submitInternal(operation, true);
        return new ExternalOperation<>(result, deliveryCompletion(result));
    }

    /** Includes framework publication/cancellation callbacks, not consumer retention after completion. */
    public CompletableFuture<Void> deliveryCompletion(CompletableFuture<?> operation) {
        if (operation instanceof CacheAsyncExecutor.Task<?> task) {
            if (task.owner != this) throw new IllegalArgumentException("Foreign async worker");
            return task.lifetime;
        }
        if (operation instanceof CacheAsyncExecutor.ExternalTask<?, ?> task) {
            if (task.owner != this) throw new IllegalArgumentException("Foreign external operation");
            return task.deliveryLifetime;
        }
        if (!operation.isDone()) throw new IllegalArgumentException("Unowned incomplete operation");
        return CompletableFuture.completedFuture(null);
    }

    /** A nonblocking per-request transport future plus its honest physical-I/O lifetime. */
    public record ExternalOperation<R>(CompletableFuture<R> result, CompletableFuture<Void> physical) {
        public ExternalOperation {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(physical, "physical");
        }
    }

    /** Capture the configured trace, tenant, causal and route context at callback registration. */
    public Runnable contextual(Runnable callback) { return CacheAsyncContext.wrap(trace, callback); }

    /**
     * Admit an externally completed operation without execute(), a parked worker or a dispatch slot.
     * start must only enqueue nonblocking work. physical must not complete until actual I/O exits.
     * mapper may decode/validate but must not block awaiting another future.
     */
    public <R, V> CompletableFuture<V> submitExternal(
            java.util.function.Supplier<ExternalOperation<R>> start,
            java.util.function.Function<R, V> mapper) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(mapper, "mapper");
        ExternalTask<R, V> task;
        lifecycle.lock();
        try {
            if (closed) return externalRejection(CacheAsyncRejectedException.Reason.CLOSED);
            if (!permits.tryAcquire()) return externalRejection(CacheAsyncRejectedException.Reason.SATURATED);
            try {
                task = new ExternalTask<>(mapper, true);
                tasks.add(task);
                externalOutstanding.incrementAndGet();
                externalAccepted.increment();
            } catch (RuntimeException | Error failure) {
                permits.release();
                return CompletableFuture.failedFuture(failure);
            }
        } finally { lifecycle.unlock(); }
        try {
            task.contextual = CacheAsyncContext.wrap(trace, task::map);
            if (closed || task.cancelRequested.get()) {
                task.notStarted(closed ? new CacheAsyncRejectedException(CacheAsyncRejectedException.Reason.CLOSED)
                        : new java.util.concurrent.CancellationException("Async read cancelled before enqueue"));
                return task;
            }
            ExternalOperation<R> operation = Objects.requireNonNull(start.get(), "external operation");
            task.source = operation.result();
            operation.physical().whenComplete((ignored, problem) -> task.physicalFinished(problem));
            operation.result().whenComplete(task::received);
            if (task.cancelRequested.get()) operation.result().cancel(false);
        } catch (Throwable failure) {
            task.notStarted(failure);
        }
        return task;
    }

    /**
     * Contextual mapping inside an ALREADY admitted bounded operation (for example a 512-row window).
     * Does not acquire another permit or dispatch a worker. The returned physical stage includes mapping scopes.
     */
    public <R, V> ExternalOperation<V> mapExternal(
            ExternalOperation<R> operation, java.util.function.Function<R, V> mapper) {
        Objects.requireNonNull(operation, "operation");
        ExternalTask<R, V> task = new ExternalTask<>(Objects.requireNonNull(mapper, "mapper"), false);
        task.source = operation.result();
        operation.physical().whenComplete((ignored, failure) -> task.physicalFinished(failure));
        try {
            task.contextual = CacheAsyncContext.wrap(trace, task::map);
            operation.result().whenComplete(task::received);
        } catch (Throwable failure) {
            task.notStarted(failure);
            operation.result().cancel(false);
        }
        return new ExternalOperation<>(task, task.physicalLifetime);
    }

    public record ExternalSnapshot(int outstanding, long accepted, long rejected, long completed, long failed, long cancelled) {}
    public ExternalSnapshot externalSnapshot() {
        return new ExternalSnapshot(externalOutstanding.get(), externalAccepted.sum(), externalRejected.sum(),
                externalCompleted.sum(), externalFailed.sum(), externalCancelled.sum());
    }
    private <V> CompletableFuture<V> externalRejection(CacheAsyncRejectedException.Reason reason) {
        externalRejected.increment();
        return CompletableFuture.failedFuture(new CacheAsyncRejectedException(reason));
    }

    /** Read-only fast-path lifecycle check; admission still checks atomically under lifecycle. */
    public boolean accepting() { return !closed; }

    /** Reject new operations without interrupting already admitted callbacks or storage I/O. */
    public void stopAccepting() {
        lifecycle.lock();
        try { closed = true; }
        finally { lifecycle.unlock(); }
    }

    private <V> CompletableFuture<V> rejection(CacheAsyncRejectedException.Reason reason) {
        rejected.increment();
        return CompletableFuture.failedFuture(new CacheAsyncRejectedException(reason));
    }

    private void finished(CompletableFuture<?> task) {
        lifecycle.lock();
        try {
            tasks.remove(task);
            permits.release();
            idle.signalAll();
        } finally { lifecycle.unlock(); }
    }

    /** Snapshot values are diagnostic, not an admission reservation. No tenant/id labels. */
    public Snapshot snapshot() {
        lifecycle.lock();
        try {
            return new Snapshot(maxConcurrent, permits.availablePermits(),
                    maxConcurrent - dispatchSlots.availablePermits(), closed,
                    accepted.sum(), rejected.sum(), completed.sum(), failed.sum(), cancelled.sum());
        } finally { lifecycle.unlock(); }
    }

    public record Snapshot(int maxConcurrent, int availablePermits, int outstandingDispatches,
            boolean closed, long accepted, long rejected, long completed, long failed, long cancelled) {}

    /** Includes RUNNING I/O even when its public future is canceled. Does not wait for canceled queue stubs. */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long remaining = checkedTimeout(timeout).toNanos();
        lifecycle.lockInterruptibly();
        try {
            while (!tasks.isEmpty()) {
                if (remaining <= 0) return false;
                remaining = idle.awaitNanos(remaining);
            }
            return true;
        } finally { lifecycle.unlock(); }
    }

    private static Duration checkedTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("timeout must be in (0, 30 seconds]");
        return timeout;
    }

    /**
     * Rejects new work, then waits up to the configured budget. On expiry interrupts only
     * owned workers and throws; uncooperative I/O stays counted until it actually exits.
     * No retries, rollback, or fabricated completion. May be retried after storage drains.
     */
    @Override public void close() {
        lifecycle.lock();
        try { closed = true; }
        finally { lifecycle.unlock(); }
        if (owned != null) owned.shutdown();
        long deadline = System.nanoTime() + shutdownTimeout.toNanos();
        try {
            boolean drained = awaitIdle(shutdownTimeout);
            long remaining = Math.max(0, deadline - System.nanoTime());
            boolean terminated = owned == null || owned.awaitTermination(remaining, TimeUnit.NANOSECONDS);
            if (!drained || !terminated) {
                if (owned != null) owned.shutdownNow();
                throw new IllegalStateException("CacheDB async shutdown timed out; outstanding I/O remains counted");
            }
        } catch (InterruptedException interrupted) {
            if (owned != null) owned.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CacheDB async shutdown interrupted", interrupted);
        }
    }

    private final class ExternalTask<R, V> extends CompletableFuture<V> {
        private final CacheAsyncExecutor owner = CacheAsyncExecutor.this;
        private final CompletableFuture<Void> deliveryLifetime = new CompletableFuture<>();
        private final AtomicInteger publishers = new AtomicInteger();
        private volatile boolean publicationExited;
        private final boolean admitted;
        private final CompletableFuture<Void> physicalLifetime = new CompletableFuture<>();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean published = new AtomicBoolean();
        private final AtomicBoolean cancellationRecorded = new AtomicBoolean();
        private volatile boolean physicalDone;
        private volatile boolean callbackDone;
        private volatile Throwable physicalFailure;
        private volatile Throwable failure;
        private volatile CompletableFuture<R> source;
        private volatile Runnable contextual;
        private java.util.function.Function<R, V> mapper;
        private R raw;
        private V value;

        ExternalTask(java.util.function.Function<R, V> mapper, boolean admitted) {
            this.mapper = mapper;
            this.admitted = admitted;
        }

        void received(R response, Throwable problem) {
            raw = response;
            failure = unwrapCompletion(problem);
            try { contextual.run(); }
            catch (Throwable contextFailure) {
                if (failure != null && failure != contextFailure) contextFailure.addSuppressed(failure);
                failure = contextFailure;
            } finally {
                raw = null;
                mapper = null;
                contextual = null;
                callbackDone = true;
                publishIfFinished();
            }
        }

        void map() {
            if (failure != null || cancelRequested.get() || isDone()) return;
            try { value = mapper.apply(raw); }
            catch (Throwable problem) { failure = problem; }
        }

        void physicalFinished(Throwable problem) {
            physicalFailure = unwrapCompletion(problem);
            physicalDone = true;
            publishIfFinished();
        }

        void notStarted(Throwable problem) {
            // This path is only for capture/start failure before an ExternalOperation was returned.
            // Once a source exists, its physical signal remains authoritative.
            failure = problem;
            if (source == null) physicalDone = true;
            raw = null;
            mapper = null;
            contextual = null;
            callbackDone = true;
            publishIfFinished();
        }

        private void publishIfFinished() {
            publishers.incrementAndGet();
            try { publishResultIfFinished(); }
            finally {
                if (publishers.decrementAndGet() == 0 && publicationExited) deliveryLifetime.complete(null);
            }
        }

        private void publishResultIfFinished() {
            if (!callbackDone) return;
            Throwable problem = failure != null ? failure : physicalFailure;
            if (!physicalDone) {
                // In particular, raw deadline delivery must not wait for a longer socket/pool timeout.
                // Publish only the failure; the registered task and its physical permit stay owned here.
                if (problem != null && !cancelRequested.get() && published.compareAndSet(false, true)) {
                    if (admitted) externalFailed.increment();
                    super.completeExceptionally(problem);
                }
                return;
            }
            if (!finished.compareAndSet(false, true)) return;
            V result = value;
            value = null;
            source = null;
            if (admitted) {
                externalOutstanding.decrementAndGet();
                CacheAsyncExecutor.this.finished(this);
            }
            physicalLifetime.complete(null);
            // Admission/context/storage have all exited before arbitrary user callbacks can run.
            if (cancelRequested.get() || !published.compareAndSet(false, true)) {
                publicationExited = true;
                return;
            }
            if (admitted) {
                if (problem == null) externalCompleted.increment(); else externalFailed.increment();
            }
            try { if (problem == null) super.complete(result); else super.completeExceptionally(problem); }
            finally { publicationExited = true; }
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            if (isDone()) return isCancelled();
            publishers.incrementAndGet();
            try {
                cancelRequested.set(true);
                CompletableFuture<R> active = source;
                try { if (active != null) active.cancel(false); }
                finally {
                    boolean changed = super.cancel(false);
                    if (admitted && changed && cancellationRecorded.compareAndSet(false, true)) externalCancelled.increment();
                }
                return isCancelled();
            } finally {
                if (publishers.decrementAndGet() == 0 && publicationExited) deliveryLifetime.complete(null);
            }
        }
    }

    private static Throwable unwrapCompletion(Throwable failure) {
        while ((failure instanceof java.util.concurrent.CompletionException
                || failure instanceof java.util.concurrent.ExecutionException) && failure.getCause() != null)
            failure = failure.getCause();
        return failure;
    }

    private final class Task<V> extends CompletableFuture<V> implements Runnable {
        private final CacheAsyncExecutor owner = CacheAsyncExecutor.this;
        private final CompletableFuture<Void> lifetime = new CompletableFuture<>();
        private final AtomicInteger publishers = new AtomicInteger();
        private volatile boolean executionExited;
        private static final int QUEUED = 0, RUNNING = 1, DONE = 2;
        private final AtomicInteger state = new AtomicInteger(QUEUED);
        private final AtomicBoolean dispatchReleased = new AtomicBoolean();
        private final AtomicBoolean cancellationRecorded = new AtomicBoolean();
        private volatile Callable<V> operation;
        private volatile Runnable contextual;
        private volatile Thread submittingThread = Thread.currentThread();
        private V value;
        private Throwable failure;

        Task(Callable<V> operation) { this.operation = operation; }

        void invoke() {
            try { value = operation.call(); }
            catch (Throwable problem) { failure = problem; }
        }

        @Override public void run() {
            if (Thread.currentThread() == submittingThread) {
                reject(new CacheAsyncRejectedException(CacheAsyncRejectedException.Reason.INLINE_EXECUTOR));
                return;
            }
            if (!state.compareAndSet(QUEUED, RUNNING)) {
                releaseDispatch();
                return;
            }
            publishers.incrementAndGet();
            try {
                try { contextual.run(); }
                catch (Throwable contextFailure) {
                    if (failure != null && failure != contextFailure) contextFailure.addSuppressed(failure);
                    failure = contextFailure;
                }
            } finally {
                operation = null;
                contextual = null;
                state.set(DONE);
                finished(this);
                releaseDispatch();
            }
            // I/O permits are free; the tracked delivery lifetime still owns retained results.
            try { publishWorkerResult(); }
            finally {
                executionExited = true;
                publishers.decrementAndGet();
                publishLifetime();
            }
        }

        private void publishWorkerResult() {
            V result = value;
            Throwable problem = failure;
            value = null;
            failure = null;
            if (problem == null) {
                completed.increment();
                super.complete(result);
            } else {
                failed.increment();
                super.completeExceptionally(problem);
            }
            if (problem instanceof Error fatal) throw fatal;
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            if (isDone()) return isCancelled();
            publishers.incrementAndGet();
            boolean detached = false;
            try {
                if (state.compareAndSet(QUEUED, DONE)) {
                    operation = null;
                    contextual = null;
                    finished(this);
                    detached = true;
                    // The borrowed executor stub keeps its dispatch reservation until drained.
                }
                boolean changed = super.cancel(false);
                if (changed && cancellationRecorded.compareAndSet(false, true)) cancelled.increment();
                return changed;
            } finally {
                if (detached) executionExited = true;
                publishers.decrementAndGet();
                publishLifetime();
            }
        }

        void reject(Throwable problem) {
            publishers.incrementAndGet();
            boolean detached = false;
            try {
                if (state.compareAndSet(QUEUED, DONE)) {
                    operation = null;
                    contextual = null;
                    rejected.increment();
                    finished(this);
                    releaseDispatch();
                    detached = true;
                    super.completeExceptionally(problem);
                } else if (state.get() == DONE) releaseDispatch();
            } finally {
                if (detached) executionExited = true;
                publishers.decrementAndGet();
                publishLifetime();
            }
        }

        private void publishLifetime() {
            if (executionExited && publishers.get() == 0) lifetime.complete(null);
        }

        private void releaseDispatch() {
            if (dispatchReleased.compareAndSet(false, true)) dispatchSlots.release();
        }
    }
}

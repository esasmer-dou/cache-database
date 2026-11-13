package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.api.CacheUnitOfWork;
import com.reactor.cachedb.core.config.RedisReadCoalescingConfig;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/** One lifecycle and policy boundary shared by a database's repositories and fetch-plan copies. */
public final class RedisRepositoryReadState implements AutoCloseable {
    private final RedisReadRuntime runtime;
    private final long bulkDeleteTimeoutMillis;
    private final java.util.concurrent.Semaphore bulkDeletes = new java.util.concurrent.Semaphore(2, true);
    private final RedisAsyncReadBudget asyncReadBudget;
    private final boolean enabled;
    private final int poolCapacity;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LongAdder disabled = new LongAdder(), causal = new LongAdder(), unitOfWork = new LongAdder();

    public RedisRepositoryReadState(RedisClientOperations client, RedisReadCoalescingConfig config) {
        Objects.requireNonNull(client, "client"); Objects.requireNonNull(config, "config");
        poolCapacity = client.physicalPoolCapacity();
        enabled = config.enabled();
        bulkDeleteTimeoutMillis = config.timeoutMillis();
        runtime = new RedisReadRuntime(client, config, poolCapacity);
        asyncReadBudget = new RedisAsyncReadBudget(config);
    }
    /** Borrowed transport: the owning CacheDatabase, not a repository/view, closes it. */
    public RedisReadRuntime runtime() { return runtime; }
    public int poolCapacity() { return poolCapacity; }
    boolean acquireBulkDelete() {
        ensureOpen();
        if (!bulkDeletes.tryAcquire()) return false;
        try { ensureOpen(); return true; }
        catch (RuntimeException failure) { bulkDeletes.release(); throw failure; }
    }
    void releaseBulkDelete() { bulkDeletes.release(); }
    long bulkDeleteDeadline() { return System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(bulkDeleteTimeoutMillis); }
    RedisAsyncReadBudget asyncReadBudget() { return asyncReadBudget; }
    public NativeReadMemory nativeReadMemory() {
        var memory = asyncReadBudget.snapshot();
        return new NativeReadMemory(memory.reservedBytes(), memory.limitBytes(), memory.deliveries(), memory.expiryWorkers());
    }
    public record NativeReadMemory(long reservedBytes, long limitBytes, int deliveries, int expiryWorkers) {}

    /**
     * Native async eligibility, NOT the synchronous batching switch.
     * Disabled merging still uses the runtime's bounded singleton FIFO. Causal false delegates to
     * the synchronous compatibility path, which records its bypass exactly once.
     */
    public boolean useAsyncTransport(long minimumVersion) {
        if (closed.get()) throw new RejectedExecutionException("CacheDatabase point-read runtime is closed");
        if (CacheUnitOfWork.active())
            throw new IllegalStateException("Async CacheDB operations are unavailable inside a synchronous unit of work");
        if (minimumVersion < 0) throw new IllegalArgumentException("Minimum version must not be negative");
        return minimumVersion == 0;
    }

    /** Memory-only lifecycle check, including reads that can complete from L1 without transport. */
    public void ensureOpen() {
        if (closed.get()) throw new RejectedExecutionException("CacheDatabase point-read runtime is closed");
    }

    /** Called on the original caller thread after an L1 miss and before any Redis data read. */
    public boolean coalesce(long minimumVersion) {
        ensureOpen();
        if (!enabled) { disabled.increment(); return false; }
        if (minimumVersion > 0) { causal.increment(); return false; }
        if (CacheUnitOfWork.active()) { unitOfWork.increment(); return false; }
        return true;
    }
    public Metrics metrics() { return new Metrics(runtime.metrics(), disabled.sum(), causal.sum(), unitOfWork.sum()); }
    public record Metrics(RedisReadRuntime.Metrics transport, long disabled, long causal, long unitOfWork) {
        public long bypassed() { return transport.bypassed() + disabled + causal + unitOfWork; }
    }
    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            asyncReadBudget.close();
            runtime.close();
        }
    }
}

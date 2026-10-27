package com.reactor.cachedb.benchmarks.redis;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** JMH custom executor: one virtual thread per worker task, never a virtual-thread pool. */
public final class VirtualBenchmarkExecutor extends AbstractExecutorService {
    private final ExecutorService delegate;
    private final Semaphore capacity;

    public VirtualBenchmarkExecutor(int workers, String prefix) {
        if (workers < 1 || workers > 1000) throw new IllegalArgumentException("Benchmark worker limit is 1000");
        capacity = new Semaphore(workers);
        delegate = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("jmh-virtual-", 0).factory());
    }
    @Override public void execute(Runnable command) {
        try {
            if (!capacity.tryAcquire(10, TimeUnit.SECONDS))
                throw new RejectedExecutionException("Benchmark worker limit reached");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new RejectedExecutionException(interrupted);
        }
        try { delegate.execute(() -> { try { command.run(); } finally { capacity.release(); } }); }
        catch (RuntimeException failure) { capacity.release(); throw failure; }
    }
    @Override public void shutdown() { delegate.shutdown(); }
    @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
    @Override public boolean isShutdown() { return delegate.isShutdown(); }
    @Override public boolean isTerminated() { return delegate.isTerminated(); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}

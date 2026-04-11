package com.reactor.cachedb.redis;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

public final class ProjectionRefreshDispatcher implements AutoCloseable {

    private final ExecutorService executor;

    public ProjectionRefreshDispatcher() {
        this.executor = Executors.newSingleThreadExecutor(new ProjectionRefreshThreadFactory());
    }

    public void dispatch(Runnable task) {
        executor.execute(Objects.requireNonNull(task, "task"));
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static final class ProjectionRefreshThreadFactory implements ThreadFactory {
        private final AtomicLong sequence = new AtomicLong();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "cachedb-projection-refresh-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}

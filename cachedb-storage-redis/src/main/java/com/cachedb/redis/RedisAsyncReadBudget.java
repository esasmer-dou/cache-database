package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.RedisReadCoalescingConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import redis.clients.jedis.exceptions.JedisConnectionException;

/** Independent native result ownership; the raw transport and its credit accounting are not modified. */
final class RedisAsyncReadBudget implements AutoCloseable {
    static final long CONTENT_BYTES = 16L * 1024 * 1024;
    static final long BULK_BYTES = 3 * CONTENT_BYTES;
    static final long WORKER_BYTES = 2 * CONTENT_BYTES;
    static final long HARD_RESULT_BYTES = 128L * 1024 * 1024;
    static final int HARD_DELIVERIES = 6144;
    record Snapshot(long reservedBytes, long limitBytes, int deliveries, int expiryWorkers) {}
    private static final System.Logger LOG = System.getLogger(RedisAsyncReadBudget.class.getName());
    private final ReentrantLock lock = new ReentrantLock();
    private final Set<Lease> leases = new HashSet<>();
    private final long byteLimit, timeoutMillis;
    private final int deliveryLimit;
    private final AtomicInteger expiryWorkers = new AtomicInteger();
    private final ScheduledThreadPoolExecutor deadlines;
    private long bytes;
    private boolean closed;

    RedisAsyncReadBudget(RedisReadCoalescingConfig config) {
        this(Math.min(config.maxLiveReplyBytes(), HARD_RESULT_BYTES), config.timeoutMillis(), HARD_DELIVERIES);
    }

    RedisAsyncReadBudget(long byteLimit, long timeoutMillis, int deliveryLimit) {
        if (byteLimit < 1 || byteLimit > HARD_RESULT_BYTES || timeoutMillis < 1 || timeoutMillis > 300000
                || deliveryLimit < 1 || deliveryLimit > HARD_DELIVERIES)
            throw new IllegalArgumentException("Invalid native read delivery bounds");
        this.byteLimit = byteLimit;
        this.timeoutMillis = timeoutMillis;
        this.deliveryLimit = deliveryLimit;
        deadlines = new ScheduledThreadPoolExecutor(1, task -> Thread.ofPlatform()
                .name("cachedb-native-read-deadlines").daemon(true).inheritInheritableThreadLocals(false).unstarted(task));
        deadlines.setRemoveOnCancelPolicy(true);
        deadlines.setExecuteExistingDelayedTasksAfterShutdownPolicy(true);
    }

    Lease reservePoint() { return reserve(0); }
    Lease reserveWorker() { return reserve(WORKER_BYTES); }
    Lease reserveBulk() { return reserve(BULK_BYTES); }

    private Lease reserve(long charge) {
        lock.lock();
        try {
            if (closed) throw new RejectedExecutionException("Native read runtime is closed");
            if (leases.size() == deliveryLimit || charge > byteLimit - bytes)
                throw new RejectedExecutionException("Native read result/delivery admission is full");
            var lease = new Lease(charge);
            leases.add(lease);
            bytes += charge;
            return lease;
        } finally { lock.unlock(); }
    }

    Snapshot snapshot() {
        lock.lock();
        try { return new Snapshot(bytes, byteLimit, leases.size(), expiryWorkers.get()); }
        finally { lock.unlock(); }
    }

    final class Lease implements AutoCloseable {
        private final long charge;
        private final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        private final AtomicBoolean closeRequested = new AtomicBoolean(), returned = new AtomicBoolean();
        private final AtomicInteger alarmState = new AtomicInteger();
        private volatile ScheduledFuture<?> alarm;
        private volatile Consumer<Throwable> failureDelivery;
        private boolean armed;
        Lease(long charge) { this.charge = charge; }

        void armDeadline(Consumer<Throwable> delivery) {
            Objects.requireNonNull(delivery);
            boolean stopped;
            lock.lock();
            try {
                if (armed) throw new IllegalStateException("Native read deadline already armed");
                armed = true;
                if (closeRequested.get()) return;
                failureDelivery = delivery;
                stopped = closed;
            } finally { lock.unlock(); }
            if (stopped) { rejectOnClose(); return; }
            try {
                ScheduledFuture<?> scheduled = deadlines.schedule(() -> dispatchFailure(new JedisConnectionException(
                        "Native read delivery timed out", new TimeoutException("Native read deadline elapsed"))),
                        Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                alarm = scheduled;
                if (alarmState.get() != 0) scheduled.cancel(false);
            } catch (RejectedExecutionException closing) { rejectOnClose(); }
        }

        void rejectOnClose() {
            if (failureDelivery != null) dispatchFailure(new RejectedExecutionException("Native read runtime is closed"));
        }

        private void dispatchFailure(Throwable failure) {
            if (!alarmState.compareAndSet(0, 1)) return;
            var scheduled = alarm;
            if (scheduled != null) scheduled.cancel(false);
            expiryWorkers.incrementAndGet();
            try {
                Thread.ofVirtual().name("cachedb-native-read-expired").inheritInheritableThreadLocals(false).start(() -> {
                    try { if (!closeRequested.get()) failureDelivery.accept(failure); }
                    finally {
                        expiryWorkers.decrementAndGet();
                        alarmState.set(2);
                        returnCredit();
                    }
                });
            } catch (RuntimeException | Error failed) {
                expiryWorkers.decrementAndGet();
                alarmState.set(2);
                returnCredit();
                LOG.log(System.Logger.Level.ERROR, "Cannot start bounded native deadline delivery", failed);
            }
        }

        void stopDeliveryDeadline() {
            alarmState.compareAndSet(0, 2);
            var scheduled = alarm;
            if (scheduled != null) scheduled.cancel(false);
        }

        @Override public void close() {
            closeRequested.set(true);
            stopDeliveryDeadline();
            returnCredit();
        }

        private void returnCredit() {
            if (!closeRequested.get() || alarmState.get() == 1 || !returned.compareAndSet(false, true)) return;
            lock.lock();
            try {
                failureDelivery = null;
                bytes -= charge;
                leases.remove(this);
            } finally { lock.unlock(); }
        }
    }

    @Override public void close() {
        ArrayList<Lease> active;
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            active = new ArrayList<>(leases);
        } finally { lock.unlock(); }
        for (Lease lease : active) lease.rejectOnClose();
        deadlines.shutdown();
    }
}

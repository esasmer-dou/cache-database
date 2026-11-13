package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.RedisReadCoalescingConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

/** Bounded raw transport. Entity, tenant, causal and near-cache policies remain with the caller. */
final class RedisReadCoalescer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(RedisReadCoalescer.class.getName());
    static final int HARD_SYNC_OVERFLOW_WAITERS = 8192;
    static final long HARD_SYNC_OVERFLOW_BYTES = 16L * 1024 * 1024;
    private enum Kind { REGULAR, ASYNC_DIRECT, SYNC_DIRECT }
    private static final class Batch {
        private final List<Request> requests;
        private final Kind kind;
        private final DeliveryLease lease;
        Batch(List<Request> requests, Kind kind, DeliveryLease lease) {
            this.requests = requests instanceof ArrayList<?> ? requests : new ArrayList<>(requests);
            this.kind = kind; this.lease = lease;
        }
        List<Request> requests() { return requests; }
        Kind kind() { return kind; }
        boolean synchronousOnly() {
            for (Request request : requests) if (request.async) return false;
            return true;
        }
    }
    private static final class DeliveryLease {
        long bytes;
        DeliveryLease(long bytes) { this.bytes = bytes; }
    }
    record State(int inFlight, int asyncDirect, int syncDirect, int queued, long queuedBytes,
            int asyncAdmitted, int deadlineAlarms, int deadlineDeliveries,
            long reservedReplyBytes, int deliveryBatches, int pendingContinuations,
            int syncOverflowWaiters, long syncOverflowBytes) {}

    private final RedisClientOperations client;
    private final Runnable beforeWorkerStart;
    private final RedisReadCoalescingConfig config;
    private final int maxInFlight;
    private final int asyncLimit;
    private final int maxSyncOverflowWaiters;
    private final long maxSyncOverflowBytes;
    private final Semaphore asyncAdmission;
    private final ScheduledThreadPoolExecutor deadlines;
    private final AtomicInteger deadlineDeliveries = new AtomicInteger();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition rawIdle = lock.newCondition();
    private final ArrayDeque<Request> queue = new ArrayDeque<>();
    private final ThreadLocal<Boolean> callbackOwner = new ThreadLocal<>();
    private final LongAdder batches = new LongAdder();
    private final LongAdder transported = new LongAdder();
    private final LongAdder coalesced = new LongAdder();
    private final LongAdder bypassed = new LongAdder();
    private int inFlight;
    private int asyncDirect;
    private int syncDirect;
    private int queueReservations;
    private long queuedBytes;
    private long reservedReplyBytes;
    private int deliveryBatches;
    private boolean closed;
    private SyncOverflowWaiter firstSyncOverflow, lastSyncOverflow;
    private int syncOverflowWaiters;
    private long syncOverflowBytes;

    private final class SyncOverflowWaiter {
        final Request request;
        final Condition ready = lock.newCondition();
        SyncOverflowWaiter previous, next;
        boolean linked;
        SyncOverflowWaiter(Request request) { this.request = request; }
    }

    RedisReadCoalescer(RedisClientOperations client, RedisReadCoalescingConfig config, int maxPool) {
        this(client, config, maxPool, HARD_SYNC_OVERFLOW_WAITERS, HARD_SYNC_OVERFLOW_BYTES);
    }

    /** Smaller explicit limits are a package-local deterministic test seam; public runtime uses hard limits. */
    RedisReadCoalescer(RedisClientOperations client, RedisReadCoalescingConfig config, int maxPool,
            int maxSyncOverflowWaiters, long maxSyncOverflowBytes) {
        this(client, config, maxPool, maxSyncOverflowWaiters, maxSyncOverflowBytes, () -> {});
    }

    /** Package-local seam for holding or failing dispatched worker startup deterministically. */
    RedisReadCoalescer(RedisClientOperations client, RedisReadCoalescingConfig config, int maxPool,
            int maxSyncOverflowWaiters, long maxSyncOverflowBytes, Runnable beforeWorkerStart) {
        if (maxSyncOverflowWaiters < 1 || maxSyncOverflowWaiters > HARD_SYNC_OVERFLOW_WAITERS
                || maxSyncOverflowBytes < 1 || maxSyncOverflowBytes > HARD_SYNC_OVERFLOW_BYTES)
            throw new IllegalArgumentException("Invalid sync overflow limits");
        this.maxSyncOverflowWaiters = maxSyncOverflowWaiters;
        this.maxSyncOverflowBytes = maxSyncOverflowBytes;
        this.client = Objects.requireNonNull(client);
        this.beforeWorkerStart = Objects.requireNonNull(beforeWorkerStart);
        this.config = Objects.requireNonNull(config);
        maxInFlight = config.resolveMaxInFlight(maxPool);
        // Also bound raw-completed workers retained by arbitrary inline future callbacks.
        asyncLimit = Math.addExact(config.maxQueuedRequests(),
                Math.addExact(maxInFlight, config.maxAsyncDirect()));
        asyncAdmission = new Semaphore(asyncLimit);
        deadlines = new ScheduledThreadPoolExecutor(1, runnable -> Thread.ofPlatform()
                .name("cachedb-redis-read-deadlines").daemon(true).inheritInheritableThreadLocals(false)
                .unstarted(runnable));
        deadlines.setRemoveOnCancelPolicy(true);
        // Closing must not discard deadlines for physical I/O which outlives the two-second raw drain.
        deadlines.setExecuteExistingDelayedTasksAfterShutdownPolicy(true);
        deadlines.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    RedisPointRead readSync(String entity, String tombstone, boolean needTtl) {
        Request request = new Request(entity, tombstone, needTtl, false);
        Batch immediate;
        try { immediate = admit(request); }
        catch (RuntimeException | Error failure) {
            request.physicallyFinished();
            request.deliver(null, failure);
            throw failure;
        }
        if (immediate != null) execute(immediate); // No dispatch, executor hop or parked worker for the first sync read.
        try {
            long remaining = request.deadline - System.nanoTime();
            if (remaining <= 0 && !request.future.isDone()) throw new TimeoutException();
            return request.future.get(Math.max(1, remaining), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            var failure = timeout(timeout);
            if (unlink(request)) request.physicallyFinished();
            request.deliver(null, failure);
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (unlink(request)) request.physicallyFinished();
            var failure = new JedisConnectionException("Interrupted waiting for Redis point read", interrupted);
            request.deliver(null, failure);
            throw failure;
        } catch (ExecutionException failed) {
            return RedisReadCoalescer.<RuntimeException, RedisPointRead>rethrow(failed.getCause());
        }
    }

    CompletableFuture<RedisPointRead> readAsync(String entity, String tombstone, boolean needTtl) {
        Request request;
        try {
            request = new Request(entity, tombstone, needTtl, true);
        } catch (RuntimeException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }
        if (!asyncAdmission.tryAcquire()) {
            bypassed.increment();
            return CompletableFuture.failedFuture(rejected("Redis async delivery admission is full"));
        }
        request.hasAdmission = true;
        try {
            request.armDeadline();
            Batch immediate = admit(request);
            if (immediate != null) dispatch(immediate);
        } catch (RuntimeException | Error failure) {
            request.physicallyFinished();
            request.deliver(null, failure);
        }
        return request.future;
    }

    private Batch admit(Request request) {
        lock.lock();
        try {
            if (closed) throw rejected("Redis read runtime is closed");
            if (request.deliveryClaimed.get() || request.deadline - System.nanoTime() <= 0)
                throw timeout(new TimeoutException("Redis point-read admission deadline elapsed"));
            if (!config.enabled()) bypassed.increment();
            boolean fifo = config.enabled() || request.async;
            boolean earlierOverflow = !request.async && firstSyncOverflow != null;
            if (!request.async && !deliveryAvailable() && callbackOwned())
                throw rejected("Redis reply delivery memory is full inside an inline completion callback");
            if (fifo && !earlierOverflow && queue.isEmpty() && inFlight < maxInFlight && deliveryAvailable()) {
                inFlight++;
                return new Batch(List.of(request), Kind.REGULAR, reserveDelivery());
            }
            if (fifo && !earlierOverflow && queueReservations < config.maxQueuedRequests()
                    && request.retainedBytes <= config.maxQueuedBytes() - queuedBytes) {
                request.queued = true;
                request.queueReserved = true;
                queueReservations++;
                queue.addLast(request);
                queuedBytes += request.retainedBytes;
                return null;
            }
            if (config.enabled()) bypassed.increment();
            if (!request.async) {
                if (firstSyncOverflow != null || !deliveryAvailable()) return awaitSyncOverflow(request);
                syncDirect++;
                return new Batch(List.of(request), Kind.SYNC_DIRECT, reserveDelivery());
            }
            if (!config.enabled())
                throw rejected("Redis async singleton FIFO queue is full");
            if (asyncDirect >= config.maxAsyncDirect() || !deliveryAvailable())
                throw rejected("Redis async direct overflow admission is full");
            asyncDirect++;
            return new Batch(List.of(request), Kind.ASYNC_DIRECT, reserveDelivery());
        } finally {
            lock.unlock();
        }
    }

    /** Called by the original synchronous caller with lock held; awaitNanos releases that lock. */
    private Batch awaitSyncOverflow(Request request) {
        if (callbackOwned())
            throw rejected("Redis sync overflow cannot wait inside an inline completion callback");
        if (syncOverflowWaiters == maxSyncOverflowWaiters
                || request.retainedBytes > maxSyncOverflowBytes - syncOverflowBytes)
            throw rejected("Redis sync overflow waiter count/byte admission is full");
        var waiter = new SyncOverflowWaiter(request);
        waiter.previous = lastSyncOverflow;
        if (lastSyncOverflow == null) firstSyncOverflow = waiter;
        else lastSyncOverflow.next = waiter;
        lastSyncOverflow = waiter;
        waiter.linked = true;
        syncOverflowWaiters++;
        syncOverflowBytes += request.retainedBytes;
        try {
            for (;;) {
                if (closed) throw rejected("Redis read runtime is closed");
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                long remaining = request.deadline - System.nanoTime();
                if (remaining <= 0)
                    throw timeout(new TimeoutException("Redis sync overflow memory deadline elapsed"));
                if (firstSyncOverflow == waiter && deliveryAvailable()) {
                    removeSyncOverflow(waiter);
                    DeliveryLease lease = reserveDelivery();
                    syncDirect++; // Only actual transport owns this counter, never a memory waiter.
                    signalSyncOverflow();
                    return new Batch(List.of(request), Kind.SYNC_DIRECT, lease);
                }
                waiter.ready.awaitNanos(remaining);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new JedisConnectionException("Interrupted waiting for Redis sync overflow memory", interrupted);
        } finally {
            removeSyncOverflow(waiter);
            signalSyncOverflow();
        }
    }

    /** O(1) unlink even when many interrupted/timed-out callers leave out of order. Caller holds lock. */
    private void removeSyncOverflow(SyncOverflowWaiter waiter) {
        if (!waiter.linked) return;
        if (waiter.previous == null) firstSyncOverflow = waiter.next;
        else waiter.previous.next = waiter.next;
        if (waiter.next == null) lastSyncOverflow = waiter.previous;
        else waiter.next.previous = waiter.previous;
        waiter.previous = null;
        waiter.next = null;
        waiter.linked = false;
        syncOverflowWaiters--;
        syncOverflowBytes -= waiter.request.retainedBytes;
    }

    /** Wake one head only. Admissions chain another signal if a whole reply credit remains. */
    private void signalSyncOverflow() {
        if (firstSyncOverflow != null && deliveryAvailable()) firstSyncOverflow.ready.signal();
    }

    private void dispatch(Batch batch) {
        try {
            beforeWorkerStart.run();
            // Only dispatched workers may continue; an original synchronous caller must return independently.
            Thread.ofVirtual().name("cachedb-redis-read").inheritInheritableThreadLocals(false)
                    .start(() -> {
                        Batch current = batch;
                        while (current != null) current = execute(current, true);
                    });
        } catch (Throwable dispatchFailure) {
            finish(batch, null, dispatchFailure);
        }
    }

    private void execute(Batch batch) {
        execute(batch, false);
    }

    private Batch execute(Batch batch, boolean retainSynchronousSuccessor) {
        List<Request> current = batch.requests();
        var active = new ArrayList<Request>(current.size());
        var keys = new ArrayList<RedisBoundedPointRead.KeyPair>(current.size());
        for (Request request : current) {
            if (!request.future.isDone() && request.deadline - System.nanoTime() > 0) {
                active.add(request);
                keys.add(new RedisBoundedPointRead.KeyPair(request.entity, request.tombstone, request.needTtl));
            }
        }
        RedisPointRead[] results = null;
        long contentBytes = 0;
        Throwable failure = null;
        try {
            if (!keys.isEmpty()) {
                batches.increment();
                transported.add(keys.size());
                coalesced.add(Math.max(0, keys.size() - 1));
                var measured = RedisBoundedPointRead.readMeasured(client, keys, config.maxReplyBytes());
                results = measured.reads();
                contentBytes = measured.contentBytes();
            }
        } catch (Throwable transportFailure) {
            if (active.size() > 1 && transportFailure instanceof JedisDataException budget
                    && ("CACHEDB_READ_BUDGET".equals(budget.getMessage())
                            || "ERR CACHEDB_READ_BUDGET".equals(budget.getMessage()))) {
                // Only script-owned admission errors are retryable. Never retry transport/ACL/type failures.
                bypassed.add(active.size());
                active.clear();
                return finish(batch, null, null, 0, true, false);
            }
            failure = transportFailure;
        }
        // Map before delivery; skipped/cancelled requests do not shift other identities' positions.
        var mapped = new RedisPointRead[current.size()];
        if (results != null) {
            int index = 0;
            for (int i = 0; i < current.size(); i++)
                if (index < active.size() && current.get(i) == active.get(index))
                    mapped[i] = results[index++];
        }
        if (results != null) java.util.Arrays.fill(results, null);
        active.clear();
        return finish(batch, mapped, failure, contentBytes, false, retainSynchronousSuccessor);
    }

    private void finish(Batch batch, RedisPointRead[] results, Throwable failure) {
        finish(batch, results, failure, 0, false, false);
    }

    private Batch finish(Batch batch, RedisPointRead[] results, Throwable failure, long contentBytes,
            boolean debatch, boolean retainSynchronousSuccessor) {
        boolean synchronousOnly = !debatch && batch.synchronousOnly();
        boolean retain = retainSynchronousSuccessor && synchronousOnly;
        List<Batch> next;
        List<Request> abandoned = null;
        lock.lock();
        try {
            switch (batch.kind()) {
                case REGULAR -> inFlight--;
                case ASYNC_DIRECT -> asyncDirect--;
                case SYNC_DIRECT -> syncDirect--;
            }
            // Physical admission is independent of retained-result memory. No callback runs under this lock.
            reservedReplyBytes -= batch.lease.bytes - contentBytes;
            batch.lease.bytes = contentBytes;
            if (!debatch) for (Request request : batch.requests()) releaseQueueReservation(request);
            if (debatch) {
                abandoned = new ArrayList<>();
                // Reservations never left this queue's accounting. Reverse insertion preserves FIFO order.
                for (int i = batch.requests().size() - 1; i >= 0; i--) {
                    Request request = batch.requests().get(i);
                    if (!closed && !request.deliveryClaimed.get() && request.deadline - System.nanoTime() > 0) {
                        if (!request.queueReserved) throw new IllegalStateException("Debatch without queue reservation");
                        request.individual = true;
                        request.queued = true;
                        queue.addFirst(request);
                    } else {
                        request.detached = true;
                        releaseQueueReservation(request);
                        abandoned.add(request);
                    }
                }
                batch.requests().clear();
            }
            next = drain();
            signalSyncOverflow();
            if (inFlight == 0 && asyncDirect == 0 && syncDirect == 0) rawIdle.signalAll();
        } finally {
            lock.unlock();
        }
        // Private synchronous futures cannot invoke user callbacks; publish them before worker startup.
        List<Batch> deferred = synchronousOnly ? next : null;
        Batch continuation = null;
        try {
            try {
                if (deferred == null) continuation = dispatchOrRetain(next, retain);
                if (abandoned != null) {
                    Throwable rejection = closed ? rejected("Redis read runtime is closed")
                            : timeout(new TimeoutException("Redis point-read deadline elapsed before debatching"));
                    for (Request request : abandoned) request.physicallyFinished();
                    for (Request request : abandoned) if (!request.async) request.deliver(null, rejection);
                    for (Request request : abandoned) if (request.async) request.deliver(null, rejection);
                    abandoned.clear();
                }
                Boolean outerOwner = callbackOwner.get();
                callbackOwner.set(Boolean.TRUE);
                try { deliverBatch(batch, results, failure); }
                finally {
                    if (outerOwner == null) callbackOwner.remove();
                    else callbackOwner.set(outerOwner);
                }
            } finally {
                try {
                    if (results != null) java.util.Arrays.fill(results, null);
                    batch.requests().clear(); // Drop all framework-owned completed-future references BEFORE returning credit.
                    lock.lock();
                    try {
                        reservedReplyBytes -= batch.lease.bytes;
                        batch.lease.bytes = 0;
                        deliveryBatches--;
                        next = drain();
                        signalSyncOverflow();
                    } finally { lock.unlock(); }
                } finally {
                    // Both drains own reservations, even if delivery, cleanup or a sibling handoff fails.
                    try {
                        if (deferred != null) continuation = dispatchOrRetain(deferred, retain);
                    } finally {
                        if (next != deferred) {
                            if (continuation == null) continuation = dispatchOrRetain(next, retain);
                            else dispatchOrRetain(next, false);
                        }
                    }
                }
            }
            return continuation;
        } catch (RuntimeException | Error deliveryFailure) {
            // A reserved successor must not be stranded if completion or cleanup fails.
            if (continuation != null) {
                try { dispatch(continuation); }
                catch (RuntimeException | Error handoffFailure) {
                    if (handoffFailure != deliveryFailure) deliveryFailure.addSuppressed(handoffFailure);
                }
            }
            throw deliveryFailure;
        }
    }

    private Batch dispatchOrRetain(List<Batch> next, boolean retain) {
        // Retain only one wholly synchronous successor; mixed/async and siblings still need workers.
        if (retain && next.size() == 1 && next.getFirst().synchronousOnly()) {
            Batch successor = next.getFirst();
            next.clear();
            return successor;
        }
        Throwable handoffFailure = null;
        for (Batch successor : next) {
            try { dispatch(successor); }
            catch (RuntimeException | Error failure) {
                if (handoffFailure == null) handoffFailure = failure;
                else if (handoffFailure != failure) handoffFailure.addSuppressed(failure);
            }
        }
        next.clear();
        if (handoffFailure != null) return RedisReadCoalescer.<RuntimeException, Batch>rethrow(handoffFailure);
        return null;
    }


    private void deliverBatch(Batch batch, RedisPointRead[] results, Throwable failure) {
        // Physical stages of every row precede all data callbacks, even when those callbacks block.
        for (Request request : batch.requests()) request.physicallyFinished();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < batch.requests().size(); i++) {
                Request request = batch.requests().get(i);
                if (request == null || request.async != (pass == 1)) continue;
                batch.requests().set(i, null); // Do not retain completed sibling futures behind another callback.
                Throwable error = failure;
                RedisPointRead result = results == null ? null : results[i];
                if (results != null) results[i] = null;
                if (error == null && (result == null || request.deadline - System.nanoTime() <= 0))
                    error = timeout(new TimeoutException("Redis point-read delivery deadline elapsed"));
                request.deliver(result, error);
            }
        }
    }

    private boolean callbackOwned() {
        Boolean owned = callbackOwner.get();
        if (owned == null) callbackOwner.remove();
        return Boolean.TRUE.equals(owned);
    }

    /** Caller holds lock. Reserve a whole possible reply BEFORE any GET materializes it. */
    private boolean deliveryAvailable() {
        return config.maxReplyBytes() <= config.maxLiveReplyBytes() - reservedReplyBytes;
    }

    private DeliveryLease reserveDelivery() {
        if (!deliveryAvailable()) throw new IllegalStateException("Missing reply-memory reservation");
        reservedReplyBytes += config.maxReplyBytes();
        deliveryBatches++;
        return new DeliveryLease(config.maxReplyBytes());
    }

    /** Caller holds lock; callbacks, network I/O and waiting are forbidden in this method. */
    private List<Batch> drain() {
        var next = new ArrayList<Batch>(maxInFlight);
        int identities = config.enabled() ? config.maxBatchKeys() : 1;
        while (!closed && inFlight < maxInFlight && deliveryAvailable()) {
            if (queue.isEmpty()) break;
            var selected = new ArrayList<Request>(Math.min(queue.size(), queue.peekFirst().individual ? 1 : identities));
            long bytes = 0;
            while (!queue.isEmpty() && selected.size() < identities) {
                Request request = queue.peekFirst();
                if (!selected.isEmpty() && (selected.getFirst().individual || request.individual
                        || request.retainedBytes > config.maxQueuedBytes() - bytes)) break;
                queue.removeFirst();
                request.queued = false;
                bytes += request.retainedBytes;
                selected.add(request);
            }
            inFlight++;
            next.add(new Batch(selected, Kind.REGULAR, reserveDelivery()));
        }
        return next;
    }

    private boolean unlink(Request request) {
        lock.lock();
        try {
            if (request.queued && queue.remove(request)) {
                request.queued = false;
                releaseQueueReservation(request);
                request.detached = true;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** Caller holds lock; queued origins retain this reservation through any budget-only retry. */
    private void releaseQueueReservation(Request request) {
        if (!request.queueReserved) return;
        request.queueReserved = false;
        queueReservations--;
        queuedBytes -= request.retainedBytes;
    }

    private void releaseQueueReservationAfterIo(Request request) {
        lock.lock();
        try { releaseQueueReservation(request); }
        finally { lock.unlock(); }
    }

    CompletableFuture<Void> physicalCompletion(CompletableFuture<RedisPointRead> read) {
        if (read instanceof RedisReadCoalescer.ReadFuture owned) {
            if (owned.request.owner != this) throw new IllegalArgumentException("Read belongs to another Redis runtime");
            return owned.request.physical;
        }
        // The Redis adapter passes only this runtime's immediate failedFuture rejections here.
        if (!read.isDone()) throw new IllegalArgumentException("Read is not owned by this Redis runtime");
        return CompletableFuture.completedFuture(null);
    }

    RedisReadRuntime.Metrics metrics() {
        long count = batches.sum();
        return new RedisReadRuntime.Metrics(count, coalesced.sum(),
                count == 0 ? 0 : (double) transported.sum() / count, bypassed.sum());
    }

    RedisReadRuntime.DeliveryMemory deliveryMemory() {
        lock.lock();
        try { return new RedisReadRuntime.DeliveryMemory(reservedReplyBytes, config.maxLiveReplyBytes(), deliveryBatches); }
        finally { lock.unlock(); }
    }


    RedisReadRuntime.SyncOverflowWaits syncOverflowWaits() {
        lock.lock();
        try { return new RedisReadRuntime.SyncOverflowWaits(
                syncOverflowWaiters, syncOverflowBytes, maxSyncOverflowWaiters, maxSyncOverflowBytes); }
        finally { lock.unlock(); }
    }

    State state() {
        lock.lock();
        try {
            return new State(inFlight, asyncDirect, syncDirect, queueReservations, queuedBytes,
                    asyncLimit - asyncAdmission.availablePermits(), deadlines.getQueue().size(), deadlineDeliveries.get(),
                    reservedReplyBytes, deliveryBatches, (int) queue.stream().filter(request -> request.individual).count(),
                    syncOverflowWaiters, syncOverflowBytes);
        } finally {
            lock.unlock();
        }
    }

    @Override public void close() {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        List<Request> rejected;
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            for (SyncOverflowWaiter waiter = firstSyncOverflow; waiter != null; waiter = waiter.next)
                waiter.ready.signalAll(); // Terminal wakeup of every bounded caller; accounting stays honest until exit.
            rejected = new ArrayList<>(queue);
            queue.clear();
            for (Request request : rejected) {
                request.queued = false;
                request.detached = true;
                releaseQueueReservation(request);
            }
        } finally {
            lock.unlock();
        }
        deadlines.shutdown(); // Existing active deadlines survive; no new alarm can be admitted.
        // Every queued/debatched request is charged to the same 4096/16-MiB queue; close uses <=8 chunks.
        for (int offset = 0; offset < rejected.size(); offset += 512) {
            List<Request> chunk = rejected.subList(offset, Math.min(offset + 512, rejected.size()));
            Thread.ofVirtual().name("cachedb-redis-read-close").inheritInheritableThreadLocals(false)
                    .start(() -> {
                        var failure = rejected("Redis read runtime is closed");
                        for (Request request : chunk) if (!request.async) { request.physicallyFinished(); request.deliver(null, failure); }
                        for (Request request : chunk) if (request.async) { request.physicallyFinished(); request.deliver(null, failure); }
                    });
        }
        lock.lock();
        try {
            while (inFlight != 0 || asyncDirect != 0 || syncDirect != 0) {
                long remaining = end - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    rawIdle.awaitNanos(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // No fake decrement on shutdown timeout: live I/O owns its counters until finish().
        } finally {
            lock.unlock();
        }
    }

    private final class Request {
        final String entity;
        final String tombstone;
        final boolean needTtl;
        final boolean async;
        final long retainedBytes;
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.timeoutMillis());
        final RedisReadCoalescer owner = RedisReadCoalescer.this;
        final ReadFuture future = new ReadFuture(this);
        final CompletableFuture<Void> physical = new CompletableFuture<>();
        final AtomicBoolean admissionReleased = new AtomicBoolean();
        final AtomicBoolean deliveryClaimed = new AtomicBoolean();
        final AtomicBoolean physicalClaimed = new AtomicBoolean();
        final AtomicInteger alarmState = new AtomicInteger(); // 0 scheduled, 1 expiry worker owns admission, 2 done/cancelled.
        volatile boolean rawFinished;
        volatile boolean deliveryFinished;
        volatile boolean detached; // A removed queued request has no outstanding I/O.
        volatile ScheduledFuture<?> alarm;
        volatile boolean hasAdmission;
        boolean queued; // Actual deque membership, guarded by lock.
        boolean queueReserved; // Held through definite physical completion, including debatching.
        boolean individual; // A budget-only retry must not be merged again.

        Request(String entity, String tombstone, boolean needTtl, boolean async) {
            this.entity = validKey(entity);
            this.tombstone = validKey(tombstone);
            this.needTtl = needTtl;
            this.async = async;
            retainedBytes = 512L + 2L * (entity.length() + tombstone.length());
        }

        void armDeadline() {
            ScheduledFuture<?> scheduled = deadlines.schedule(this::dispatchExpiry,
                    Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            alarm = scheduled;
            if (deliveryClaimed.get()) scheduled.cancel(false);
        }

        void dispatchExpiry() {
            if (!alarmState.compareAndSet(0, 1)) return;
            if (deliveryClaimed.get()) {
                alarmState.set(2);
                releaseAdmission();
                return;
            }
            deadlineDeliveries.incrementAndGet();
            try {
                // Only expiry gets a per-request delivery VT. Never invoke callbacks on the alarm thread.
                Thread.ofVirtual().name("cachedb-redis-read-expired").inheritInheritableThreadLocals(false)
                        .start(() -> {
                            try { expire(); }
                            finally {
                                deadlineDeliveries.decrementAndGet();
                                alarmState.set(2);
                                releaseAdmission();
                            }
                        });
            } catch (RuntimeException | Error failure) {
                deadlineDeliveries.decrementAndGet();
                alarmState.set(2);
                releaseAdmission();
                LOG.log(System.Logger.Level.ERROR, "Cannot start bounded Redis deadline delivery", failure);
                throw failure; // JVM thread-creation failure cannot be turned into timer-thread callbacks.
            }
        }

        void cancelAlarm() {
            alarmState.compareAndSet(0, 2); // Prevent a cancellation/expiry-start race from bypassing admission.
            ScheduledFuture<?> scheduled = alarm;
            if (scheduled != null) scheduled.cancel(false);
        }

        void expire() {
            if (!deliveryClaimed.compareAndSet(false, true)) return;
            cancelAlarm();
            if (unlink(this) || detached) physicallyFinished();
            completeClaimed(null, timeout(new TimeoutException("Redis point-read delivery deadline elapsed")));
        }

        void physicallyFinished() {
            if (!physicalClaimed.compareAndSet(false, true)) return;
            releaseQueueReservationAfterIo(this);
            try { physical.complete(null); }
            finally {
                rawFinished = true;
                releaseAdmission();
            }
        }

        void deliver(RedisPointRead value, Throwable failure) {
            if (!deliveryClaimed.compareAndSet(false, true)) return;
            cancelAlarm();
            completeClaimed(value, failure);
        }

        void completeClaimed(RedisPointRead value, Throwable failure) {
            try {
                if (failure == null) future.complete(value);
                else future.completeExceptionally(failure);
            } finally {
                // Hold async admission through callbacks and physical completion, without a lock/raw slot.
                deliveryFinished = true;
                releaseAdmission();
            }
        }

        void releaseAdmission() {
            if (hasAdmission && rawFinished && deliveryFinished && alarmState.get() != 1
                    && admissionReleased.compareAndSet(false, true)) asyncAdmission.release();
        }
    }

    private final class ReadFuture extends CompletableFuture<RedisPointRead> {
        private final Request request;
        ReadFuture(Request request) { this.request = request; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            if (isDone() || !request.deliveryClaimed.compareAndSet(false, true)) return isCancelled();
            request.cancelAlarm();
            if (unlink(request) || request.detached) request.physicallyFinished();
            boolean cancelled = false;
            try {
                cancelled = super.cancel(false); // Never interrupt an unrelated request sharing the batch.
                return cancelled;
            } finally {
                request.deliveryFinished = true;
                request.releaseAdmission();
            }
        }
    }

    private static String validKey(String key) {
        if (key == null || key.length() > 16_384) throw new IllegalArgumentException("Invalid Redis point-read key");
        return key;
    }
    private static RejectedExecutionException rejected(String reason) { return new RejectedExecutionException(reason); }
    private static JedisConnectionException timeout(TimeoutException cause) {
        return new JedisConnectionException("Redis point read timed out", cause);
    }
    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T rethrow(Throwable failure) throws E { throw (E) failure; }
}

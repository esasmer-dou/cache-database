package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.protection.ProtectionException;
import com.reactor.cachedb.core.protection.ProtectionKey;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Runtime-owned, bounded physical producer lifetime. No SQL or per-tenant history map.
 * An uncertain transport outcome permanently poisons its slot until runtime recovery.
 */
public final class RedisTenantProducer implements AutoCloseable {
    private static final ThreadLocal<Ticket> CURRENT = new ThreadLocal<>();
    private final RedisGenerationBarrier barrier;
    private final String namespace;
    private final java.util.UUID runtimeId;
    private final Ticket[] active;
    private final ReentrantLock lock = new ReentrantLock();
    private boolean stopped;
    private int next;
    private RedisTenantReadFence reads;
    private com.reactor.cachedb.core.cache.CachePolicy readPolicy;

    public record Drain(int active, int uncertain) {
        public boolean drained() { return active == 0 && uncertain == 0; }
    }

    public RedisTenantProducer(RedisGenerationBarrier barrier, String namespace, int maxInFlight) {
        this.barrier = Objects.requireNonNull(barrier);
        this.namespace = Objects.requireNonNull(namespace);
        runtimeId = barrier.admission() == null ? java.util.UUID.randomUUID() : barrier.admission().runtimeId;
        if (maxInFlight < 1 || maxInFlight > 4096) throw invalid();
        barrier.generationKey(ProtectionKey.of(namespace, "layout-check"));
        active = new Ticket[maxInFlight];
    }

    public String namespace() { return namespace; }
    /** One actual namespace incarnation, shared with its read/L1 participant. */
    public java.util.UUID runtimeId() { return runtimeId; }

    RedisGenerationBarrier barrier() { return barrier; }

    boolean belongsToRuntime(RedisClientOperations redis, RedisKeyStrategy keys) {
        return barrier.belongsToRuntime(redis, keys);
    }

    /** Namespace equality is not ownership: only this producer can reuse its physical ticket. */
    public boolean ownsCurrent(String tenant) {
        Ticket ticket = CURRENT.get();
        if (ticket == null || ticket.producer != this
                || !ticket.key.equals(ProtectionKey.of(namespace, tenant))) return false;
        ticket.requireLive();
        return true;
    }

    public Generation requireCurrent(String tenant) {
        if (!ownsCurrent(tenant)) throw rejected();
        return CURRENT.get().original;
    }

    RedisTenantReadFence bindPointReads(com.reactor.cachedb.core.cache.CachePolicy policy,
            RedisNearCacheCoordinator.Store local) {
        lock.lock();
        try {
            if (stopped) throw rejected();
            if (reads != null) {
                if (!readPolicy.equals(policy)) throw invalid();
                return reads;
            }
            readPolicy = Objects.requireNonNull(policy);
            reads = new RedisTenantReadFence(barrier, active.length, local == null ? java.util.List.of() : java.util.List.of(local), runtimeId);
            return reads;
        } finally { lock.unlock(); }
    }

    /** Borrowed exact local read/L1 participant for native distributed closure binding. */
    public RedisTenantReadFence readFence() {
        lock.lock();
        try { if (reads == null) throw rejected(); return reads; }
        finally { lock.unlock(); }
    }

    public <R> R execute(String tenant, Supplier<R> operation) {
        Objects.requireNonNull(operation);
        ProtectionKey key = ProtectionKey.of(namespace, tenant);
        Ticket previous = CURRENT.get();
        if (previous != null) {
            if (previous.producer != this || !previous.key.equals(key)) throw invalid();
            previous.requireLive();
            return operation.get();
        }
        Ticket ticket;
        lock.lock();
        try {
            if (stopped) throw rejected();
            int slot = -1;
            for (int i = 0; i < active.length; i++) {
                int candidate = (next + i) % active.length;
                if (active[candidate] == null) { slot = candidate; break; }
            }
            if (slot < 0) throw new ProtectionException(ProtectionException.Code.BUSY);
            ticket = new Ticket(this, key, slot);
            active[slot] = ticket;
            next = (slot + 1) % active.length;
        } finally { lock.unlock(); }
        try {
            // Registered before the first network call; drain also observes an unknown capture.
            ticket.original = barrier.captureOpen(key);
            ticket.requireLive();
            CURRENT.set(ticket);
            return operation.get();
        } catch (RuntimeException | Error failure) {
            if (uncertain(failure)) ticket.uncertain = true;
            throw failure;
        } finally {
            CURRENT.remove();
            lock.lock();
            try {
                if (!ticket.uncertain) active[ticket.slot] = null;
                else ticket.revoked = true;
            } finally { lock.unlock(); }
        }
    }

    /** Revoke only this namespace+tenant. Call after the actual distributed barrier is CLOSED. */
    public Drain drain(ProtectionKey key) {
        barrier.generationKey(key);
        int running = 0, uncertain = 0;
        lock.lock();
        try {
            for (Ticket ticket : active) if (ticket != null && ticket.key.equals(key)) {
                ticket.revoked = true;
                if (ticket.uncertain) uncertain++; else running++;
            }
        } finally { lock.unlock(); }
        return new Drain(running, uncertain);
    }

    public static boolean active(String namespace, String tenant) {
        Ticket ticket = CURRENT.get();
        return ticket != null && ticket.key.equals(ProtectionKey.of(namespace, tenant));
    }

    public static Generation current(String namespace, String tenant) {
        Ticket ticket = CURRENT.get();
        if (ticket == null || !ticket.key.equals(ProtectionKey.of(namespace, tenant))) throw rejected();
        ticket.requireLive();
        return ticket.original;
    }

    static RedisGenerationBarrier currentBarrier(String namespace, String tenant) {
        current(namespace, tenant);
        return CURRENT.get().producer.barrier;
    }

    /** Stops admission. Does not erase in-flight/uncertain evidence or claim distributed closure. */
    @Override public void close() {
        RedisTenantReadFence local;
        lock.lock();
        try {
            stopped = true;
            for (Ticket ticket : active) if (ticket != null) ticket.revoked = true;
            local = reads;
        } finally { lock.unlock(); }
        if (local != null) local.close();
    }

    /** Preserve uncertainty before bounded bulk APIs deliberately replace driver causes with redacted outcomes. */
    static void transportFailure(Throwable failure) {
        Ticket ticket = CURRENT.get();
        if (ticket != null && uncertain(failure)) ticket.uncertain = true;
    }

    private static boolean uncertain(Throwable failure) {
        for (int i = 0; failure != null && i < 16; i++, failure = failure.getCause()) {
            if (failure instanceof redis.clients.jedis.exceptions.JedisConnectionException
                    || failure instanceof java.io.IOException
                    || failure instanceof java.util.concurrent.CancellationException
                    || failure instanceof InterruptedException) return true;
        }
        return Thread.currentThread().isInterrupted();
    }

    private static final class Ticket {
        final RedisTenantProducer producer;
        final ProtectionKey key;
        final int slot;
        final Thread owner = Thread.currentThread();
        volatile Generation original;
        volatile boolean revoked, uncertain;
        Ticket(RedisTenantProducer producer, ProtectionKey key, int slot) {
            this.producer = producer; this.key = key; this.slot = slot;
        }
        void requireLive() {
            if (owner != Thread.currentThread() || revoked || original == null) throw rejected();
        }
    }
    private static ProtectionException rejected() {
        return new ProtectionException(ProtectionException.Code.UNWIRED_PROTOCOL);
    }
    private static ProtectionException invalid() {
        return new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
    }
}

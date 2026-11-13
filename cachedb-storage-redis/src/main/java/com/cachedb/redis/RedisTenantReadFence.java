package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.model.OptimisticWriteConflictException;
import com.reactor.cachedb.core.protection.ProtectionException;
import com.reactor.cachedb.core.protection.ProtectionKey;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Bounded runtime participant, not a generation authority. The purge coordinator must close
 * durable admission and the Redis barrier before requesting acknowledgement from EVERY runtime.
 */
public final class RedisTenantReadFence implements AutoCloseable {
    interface Authority {
        Generation capture(ProtectionKey key);
        void validate(Generation original);
        Object execute(Generation original, Publication publication);
        void assertClosed(Lease lease, long generation);
    }

    /** A canonical producer's reviewed same-slot plan. Never reconstruct its stamp at publication. */
    public record Publication(Generation original, String ownerKey, String expectedOwner,
            String body, List<String> keys, List<String> arguments) {
        public Publication {
            Objects.requireNonNull(original);
            if (ownerKey == null || ownerKey.length() > 4096 || expectedOwner == null
                    || expectedOwner.length() > 256 || body == null || body.length() > 131072
                    || keys == null || keys.size() > 4096 || arguments == null || arguments.size() > 8192)
                throw failure(ProtectionException.Code.INVALID_REQUEST);
            long bytes = body.length();
            for (String value : keys) {
                if (value == null || value.length() > 4096) throw failure(ProtectionException.Code.INVALID_REQUEST);
                bytes += com.reactor.cachedb.core.codec.Utf8Length.of(value);
            }
            for (String value : arguments) {
                if (value == null || value.length() > 1048576) throw failure(ProtectionException.Code.INVALID_REQUEST);
                bytes += com.reactor.cachedb.core.codec.Utf8Length.of(value);
                if (bytes > 8L * 1024 * 1024) throw failure(ProtectionException.Code.INVALID_REQUEST);
            }
            if (bytes > 8L * 1024 * 1024) throw failure(ProtectionException.Code.INVALID_REQUEST);
            keys = List.copyOf(keys);
            arguments = List.copyOf(arguments);
        }
        @Override public String toString() { return "ProtectedReadPublication[redacted]"; }
    }

    public record Drain(UUID runtimeId, Generation closedGeneration, boolean drained, int active) {}

    private final Authority authority;
    private final Ticket[] active;
    private final List<RedisNearCacheCoordinator.Store> localStores;
    private final UUID runtimeId;
    private boolean stopped;

    public RedisTenantReadFence(RedisGenerationBarrier barrier, int maxInFlight,
            List<RedisNearCacheCoordinator.Store> localStores) {
        this(barrier, maxInFlight, localStores, UUID.randomUUID());
    }
    RedisTenantReadFence(RedisGenerationBarrier barrier, int maxInFlight,
            List<RedisNearCacheCoordinator.Store> localStores, UUID runtimeId) {
        this(new Authority() {
            public Generation capture(ProtectionKey key) { return barrier.captureOpen(key); }
            public void validate(Generation original) {
                if (!original.equals(barrier.captureOpen(original.key())))
                    throw failure(ProtectionException.Code.GENERATION_MISMATCH);
            }
            public Object execute(Generation original, Publication plan) {
                return barrier.evalOpen(original, plan.ownerKey(), plan.expectedOwner(),
                        plan.body(), plan.keys(), plan.arguments());
            }
            public void assertClosed(Lease lease, long generation) { barrier.assertClosed(lease, generation); }
        }, maxInFlight, localStores, runtimeId);
    }

    RedisTenantReadFence(Authority authority, int maxInFlight,
            List<RedisNearCacheCoordinator.Store> localStores) {
        this(authority, maxInFlight, localStores, UUID.randomUUID());
    }
    private RedisTenantReadFence(Authority authority, int maxInFlight,
            List<RedisNearCacheCoordinator.Store> localStores, UUID runtimeId) {
        this.runtimeId = Objects.requireNonNull(runtimeId);
        this.authority = Objects.requireNonNull(authority);
        if (maxInFlight < 1 || maxInFlight > 4096 || localStores == null || localStores.size() > 64)
            throw failure(ProtectionException.Code.INVALID_REQUEST);
        this.active = new Ticket[maxInFlight];
        this.localStores = List.copyOf(localStores);
    }

    public UUID runtimeId() { return runtimeId; }

    /** Register BEFORE capture I/O, so closure cannot miss a capture already in flight. */
    public Ticket begin(ProtectionKey key) {
        Objects.requireNonNull(key);
        Ticket ticket;
        synchronized (active) {
            if (stopped) throw failure(ProtectionException.Code.RECOVERY_REQUIRED);
            for (Ticket current : active) {
                if (current == null || current.owner != Thread.currentThread()
                        || !current.key.equals(key) || current.sharing == 0) continue;
                current.generation();
                if (current.borrowers == 64) throw failure(ProtectionException.Code.BUSY);
                var borrowed = new Ticket(current);
                current.borrowers++;
                return borrowed;
            }
            int slot = 0;
            while (slot < active.length && active[slot] != null) slot++;
            if (slot == active.length) throw failure(ProtectionException.Code.BUSY);
            ticket = new Ticket(key, slot);
            active[slot] = ticket;
        }
        try {
            Generation original = safe(() -> authority.capture(key));
            if (!key.equals(original.key())) throw failure(ProtectionException.Code.GENERATION_MISMATCH);
            ticket.original = original;
            ticket.generation();
            return ticket;
        } catch (RuntimeException | Error error) {
            ticket.close();
            throw error;
        }
    }

    /**
     * Nonblocking poll: DRAINING is not acknowledgement. Revocation never releases a ticket
     * while its synchronous I/O still runs. Call again after physical completion.
     */
    public Drain closeAndDrain(Lease lease, long generation) {
        if (generation <= 0) throw failure(ProtectionException.Code.INVALID_REQUEST);
        safe(() -> { authority.assertClosed(lease, generation); return null; });
        int count = 0;
        synchronized (active) {
            for (Ticket ticket : active) {
                if (ticket == null || !ticket.key.equals(lease.key())) continue;
                // Unknown capture and all older work must drain, not only exactly g.
                ticket.revoked = true;
                count++;
            }
        }
        localStores.forEach(RedisNearCacheCoordinator.Store::clear);
        return new Drain(runtimeId, new Generation(lease.key(), generation), count == 0, count);
    }

    private static String frame(Ticket ticket) {
        Generation original = ticket.generation();
        return "~G1:" + original.key().scopeToken() + ":" + original.key().tenantToken()
                + ":" + original.value() + ":";
    }

    private static void identity(String id) {
        if (id == null || id.length() > 1024) throw failure(ProtectionException.Code.INVALID_REQUEST);
    }

    /** Keep raw IDs so existing T2 per-ID mutation invalidations still work. Framing is L1-only. */
    public RedisNearCacheCoordinator.Store.Entry lookup(Ticket ticket,
            RedisNearCacheCoordinator.Store store, String id, long minimumVersion) {
        requireStore(store);
        requireTicket(ticket);
        identity(id);
        return ticket.compute(() -> {
            var entry = store.lookup(id, minimumVersion);
            if (entry == null) return null;
            String prefix = frame(ticket);
            if (!entry.payload().startsWith(prefix)) { store.invalidate(id); return null; }
            return new RedisNearCacheCoordinator.Store.Entry(entry.payload().substring(prefix.length()),
                    null, entry.expiresAt(), entry.softExpiresAt(), entry.bytes(), entry.version());
        });
    }

    public void cache(Ticket ticket, RedisNearCacheCoordinator.Store store, String id,
            String payload, long ttlMillis, long version, RedisNearCacheCoordinator.Store.Stamp stamp) {
        requireStore(store);
        requireTicket(ticket);
        identity(id);
        Objects.requireNonNull(stamp);
        try {
            ticket.read(() -> { store.put(id, frame(ticket) + payload, null, ttlMillis, stamp, version); return null; });
        } catch (RuntimeException rejected) {
            store.invalidate(id);
            throw rejected;
        }
    }

    private void requireTicket(Ticket ticket) {
        if (ticket.fence() != this) throw failure(ProtectionException.Code.INVALID_REQUEST);
    }

    private void requireStore(RedisNearCacheCoordinator.Store store) {
        if (!localStores.contains(store)) throw failure(ProtectionException.Code.INCOMPLETE_INVENTORY);
    }

    public final class Ticket implements AutoCloseable {
        private final ProtectionKey key;
        private final int slot;
        private final Thread owner = Thread.currentThread();
        private final Ticket physical;
        private volatile Generation original;
        private volatile boolean revoked;
        private volatile boolean released;
        private int borrowers;
        private int sharing;
        private Ticket(ProtectionKey key, int slot) { this.key = key; this.slot = slot; physical = this; }
        private Ticket(Ticket physical) { this.key = physical.key; this.slot = physical.slot; this.physical = physical; }

        private RedisTenantReadFence fence() { return RedisTenantReadFence.this; }

        public Generation generation() {
            if (owner != Thread.currentThread()) throw failure(ProtectionException.Code.INVALID_REQUEST);
            if (released || physical.released || physical.revoked || physical.original == null)
                throw failure(ProtectionException.Code.GENERATION_MISMATCH);
            return physical.original;
        }

        /** Only an owned synchronous delivery boundary may lend its exact permit to nested point reads. */
        <V> V withReentrantReads(Supplier<V> action) {
            Objects.requireNonNull(action);
            generation();
            if (physical != this) throw failure(ProtectionException.Code.INVALID_REQUEST);
            synchronized (active) {
                if (sharing == 64) throw failure(ProtectionException.Code.BUSY);
                sharing++;
            }
            try { return read(action); }
            finally {
                synchronized (active) { sharing--; releasePhysicalIfCompleted(); }
            }
        }

        public void check() {
            Generation captured = generation();
            safe(() -> { authority.validate(captured); return null; });
            generation();
        }

        /** Read-only callback. Fill/publication MUST go through publish, not this callback. */
        public <V> V read(Supplier<V> io) {
            check();
            V value = callback(io);
            check();
            return value;
        }

        /** CPU-only callbacks do not add Redis round trips; complete still certifies the result. */
        public <V> V compute(Supplier<V> action) {
            generation();
            V value = callback(action);
            generation();
            return value;
        }

        private <V> V callback(Supplier<V> action) {
            try { return action.get(); }
            catch (OptimisticWriteConflictException conflict) {
                // Preserve the public write contract only while this exact owned generation is still open.
                check();
                throw conflict;
            }
            catch (ProtectionException known) { throw known; }
            catch (RuntimeException unavailable) { throw failure(ProtectionException.Code.STORAGE_UNAVAILABLE); }
        }

        public Object publish(Publication plan) {
            generation();
            if (!generation().equals(plan.original()))
                throw failure(ProtectionException.Code.GENERATION_MISMATCH);
            Object value = safe(() -> authority.execute(generation(), plan));
            generation();
            return value;
        }

        /** Last validation before the repository's result is exposed. */
        public <V> V complete(V value) { check(); return value; }

        @Override public void close() {
            if (owner != Thread.currentThread()) throw failure(ProtectionException.Code.INVALID_REQUEST);
            synchronized (active) {
                if (released) return;
                released = true;
                if (physical != this) physical.borrowers--;
                physical.releasePhysicalIfCompleted();
            }
        }
        // Called only under the bounded bookkeeping lock; never releases a physically executing scope.
        private void releasePhysicalIfCompleted() {
            if (released && borrowers == 0 && sharing == 0 && active[slot] == this) active[slot] = null;
        }
        @Override public String toString() { return "ProtectedReadTicket[redacted]"; }
    }

    @Override public void close() {
        synchronized (active) {
            stopped = true;
            for (Ticket ticket : active) if (ticket != null) ticket.revoked = true;
        }
        localStores.forEach(RedisNearCacheCoordinator.Store::clear);
    }

    private static <V> V safe(Supplier<V> action) {
        try { return action.get(); }
        catch (ProtectionException known) { throw known; }
        catch (RuntimeException unavailable) { throw failure(ProtectionException.Code.STORAGE_UNAVAILABLE); }
    }

    static ProtectionException failure(ProtectionException.Code code) { return new ProtectionException(code); }
}

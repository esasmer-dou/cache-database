package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.config.CacheRuntimeRole;
import com.reactor.cachedb.core.config.ReadThroughMode;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.redis.RedisTenantRuntimeControl.ApprovedRoster;
import com.reactor.cachedb.redis.RedisTenantRuntimeControl.ClosedPair;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Bounded runtime-control binding for explicitly approved, owned point-delivery deployments.
 * First profile: CLIENT_ONLY, REDIS_ONLY, canonical producers, no projections or source fills.
 * This is NOT source-owner onboarding, approval of historical stores, or application-wide erasure.
 * All certified response delivery must stay inside deliver's synchronous callback lifetime.
 */
public final class CacheTenantRuntimeEnrollment implements AutoCloseable {
    public record Descriptor(String entityName, UUID runtimeId, String coverageToken) {}
    /** A local ACK is not distributed closure. Idle/pending catalogue entries are not ACKs. */
    public record PollResult(int examined, int locallyAcknowledged, int pending, int incomplete) {}

    private final CacheDatabase database;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<Dispatch> catalogue = new ArrayList<>();
    private final ReentrantLock administrative = new ReentrantLock();
    private volatile boolean closed;
    private int next;

    static CacheTenantRuntimeEnrollment prepare(CacheDatabase database, int maxInFlight) {
        requireAdmin();
        if (maxInFlight < 1 || maxInFlight > 4096) throw invalid();
        var enrollment = new CacheTenantRuntimeEnrollment(database);
        var bindings = database.entityRegistry().tenantProtectedBindings();
        if (bindings.isEmpty() || bindings.size() > 64) throw invalid();
        // Every producer MUST be configured before the session globally freezes on its first repository.
        var producers = new LinkedHashMap<String, RedisTenantProducer>();
        try {
            for (var binding : bindings) {
                enrollment.requireProfile(binding);
                var producer = database.tenantProducerForEnrollment(binding.metadata(), maxInFlight);
                if (producers.putIfAbsent(binding.metadata().entityName(), producer) != null) throw invalid();
            }
            for (var binding : bindings) enrollment.add(binding, producers.get(binding.metadata().entityName()));
            return enrollment;
        } catch (RuntimeException | Error unsafe) {
            producers.values().forEach(RedisTenantProducer::close);
            enrollment.close(); throw unsafe;
        }
    }

    private CacheTenantRuntimeEnrollment(CacheDatabase database) { this.database = Objects.requireNonNull(database); }

    private <T, ID> void add(EntityBinding<T, ID> binding, RedisTenantProducer producer) {
        var policy = binding.cachePolicy() == null ? database.config().resourceLimits().defaultCachePolicy() : binding.cachePolicy();
        var repository = database.repository(binding.metadata(), binding.codec(), policy);
        if (!(repository instanceof RedisEntityRepository<?, ?> actual)) throw incomplete();
        var entry = new Entry(binding, repository, actual, producer);
        entries.put(binding.metadata().entityName(), entry);
    }

    public Descriptor descriptor(String entityName) {
        var entry = entry(entityName);
        return new Descriptor(entityName, entry.participant.runtimeId(), entry.coverageToken);
    }
    public RedisGenerationBarrier barrier(String entityName) { return entry(entityName).participant.barrier(); }
    /** Actual point-profile manager layout. Empty page list does NOT certify absence of historical pages. */
    public RedisPurgeTarget pointProfileTarget(String entityName) { return entry(entityName).target; }
    /** Pass this exact adapter into the real facade plan before sealing its manifest fingerprint. */
    public TenantReadFence readFence(String entityName) { return entry(entityName).fence; }
    /** Actual retained security-store participant; it verifies, but never deletes, the approved roster. */
    public PurgeParticipant controlParticipant(String entityName) { return entry(entityName).controlParticipant; }
    /** Borrowed exact retained security-store descriptor for administrative inventory tooling. */
    public String controlStoreKey(String entityName) { return entry(entityName).participant.control().rosterKey(); }

    <I> RedisSourceOwnerInstaller<I> sourceOwnerInstaller(CacheDatabase root, RedisTenantPurgePlan plan,
            ProtectionKey key, SourceOwnerOnboarding.IdCodec<I> ids, List<SourceOwnerOnboarding.Page> pages,
            String worklist) {
        requireAdmin(); var entry=entry(plan.entityName); requireProfile(entry.binding);
        if (root!=database || plan.redis!=database.redisOperations() || plan.keys!=database.redisKeyStrategy()
                || plan.barrier!=entry.participant.barrier() || plan.readFence!=entry.fence
                || plan.targets.size()!=1 || plan.targets.getFirst()!=entry.target) throw incomplete();
        var dispatch=entry.dispatch.get(key); if (dispatch==null) throw incomplete();
        return new RedisSourceOwnerInstaller<>(entry.participant.control(),dispatch.fence,plan.barrier,plan.keys,
                plan.scope(),plan.manifestToken(),plan.queues.stream().map(RedisTenantPurgePlan.QueueRoute::stream).toList(),ids,
                entry.binding.metadata().columns(),pages,worklist,
                Math.min(plan.binding.maxBatchBytes(),1_048_576));
    }

    /** Actual temporary INITIAL-store participant; register this exact instance in the entity manifest. */
    public <T,ID> PurgeParticipant initialSourceParticipant(EntityMetadata<T,ID> metadata,InitialSourceHotWindow<T,ID> window) {
        return initialSourceHydrator(metadata,window).participant();
    }
    @SuppressWarnings("unchecked")
    <T,ID> RedisInitialSourceHydrator<T,ID> initialSourceHydrator(EntityMetadata<T,ID> metadata,InitialSourceHotWindow<T,ID> window) {
        requireAdmin();var entry=entry(metadata.entityName());requireProfile(entry.binding);
        if(window.metadata()!=entry.binding.metadata() || window.codec()!=entry.binding.codec())throw incomplete();
        administrative.lock();
        try {
            if(entry.initialSource==null)entry.initialSource=((RedisEntityRepository<T,ID>)entry.repository)
                    .initialSourceHydrator(window,entry.participant.control());
            var actual=(RedisInitialSourceHydrator<T,ID>)entry.initialSource;
            if(actual.window()!=window)throw incomplete();
            return actual;
        } finally {administrative.unlock();}
    }

    /**
     * Explicit approved roster + bounded pair catalogue. All pairs must hold actual CLOSED leases.
     * The caller still owns approval of full source/history/store/queue inventory in the facade plan.
     * Registration neither opens a generation nor writes owner markers/allocator grants.
     */
    public void sealClosed(RedisTenantPurgePlan plan, ApprovedRoster approval, List<ClosedPair> pairs) {
        requireAdmin();
        if (plan == null || approval == null) throw invalid();
        var entry = entry(plan.entityName);
        if (!administrative.tryLock()) throw busy();
        try {
            requireProfile(entry.binding);
            if (plan.redis != database.redisOperations() || plan.keys != database.redisKeyStrategy()
                    || plan.barrier != entry.participant.barrier() || plan.readFence != entry.fence
                    || plan.entityType() != entry.binding.metadata().entityType()
                    || !plan.scope().equals(entry.binding.metadata().redisNamespace())
                    || plan.targets.size() != 1 || plan.targets.getFirst() != entry.target
                    || !plan.namespaces.equals(Set.of(plan.scope()))
                    || !approval.manifestToken().equals(plan.manifestToken())
                    || !approval.coverageToken().equals(entry.coverageToken)) throw incomplete();
            if (!plan.manifest.requireAll().contains(entry.controlParticipant)) throw incomplete();
            if (!entry.dispatch.isEmpty()) {
                if (!entry.participant.control().approval().equals(approval)) throw incomplete();
            } else if (catalogue.size() + approval.pairs().size() > 64) throw invalid();
            entry.participant.control().sealClosed(approval, pairs);
            if (entry.dispatch.isEmpty()) {
                var pending = new LinkedHashMap<ProtectionKey, Dispatch>();
                for (var key : approval.pairs()) pending.put(key,
                        new Dispatch(entry, key, entry.participant.fence(key, 1000)));
                catalogue.addAll(pending.values()); entry.dispatch = Map.copyOf(pending);
            }
        } finally { administrative.unlock(); }
    }

    /**
     * Poll only the <=64 explicitly sealed pairs, round-robin, never a Redis keyspace scan.
     * Invoke from each actual root's administrative loop, including CLIENT_ONLY deployments.
     * Elapsed budget is checked BETWEEN physical commands; it is not a socket abort deadline.
     * No private pool, executor, Pub/Sub proof, automatic retry or TTL-based retirement is hidden here.
     */
    public PollResult pollControl(int maxRequests, int maxMillis) {
        requireAdmin(); requireOpen();
        if (maxRequests < 1 || maxRequests > 64 || maxMillis < 1 || maxMillis > 5000) throw invalid();
        if (!administrative.tryLock()) throw busy();
        try {
            int examined = 0, acknowledged = 0, pending = 0, incomplete = 0;
            long deadline = System.nanoTime() + maxMillis * 1_000_000L;
            int limit = Math.min(maxRequests, catalogue.size());
            for (int i = 0; i < limit && System.nanoTime() < deadline; i++) {
                requireOpen(); var dispatch = catalogue.get(next++ % catalogue.size());
                if (next == Integer.MAX_VALUE) next = 0;
                examined++;
                try {
                    requireProfile(dispatch.entry.binding);
                    dispatch.entry.participant.control().requireSealed();
                    int remaining = (int)Math.max(1, (deadline - System.nanoTime()) / 1_000_000L);
                    var state = dispatch.fence.poll(Math.min(5000, remaining));
                    if (state == TenantReadFence.State.CLOSED) acknowledged++;
                    else if (state == TenantReadFence.State.INCOMPLETE) incomplete++;
                    else pending++;
                } catch (RuntimeException unsafe) { incomplete++; }
            }
            if (catalogue.isEmpty() || entries.values().stream().anyMatch(entry -> entry.dispatch.isEmpty())) incomplete++;
            return new PollResult(examined, acknowledged, pending, incomplete);
        } finally { administrative.unlock(); }
    }

    /** Only this explicit synchronous scope certifies owned application delivery, not arbitrary raw repository usage. */
    @SuppressWarnings("unchecked")
    public <T, ID, R> void deliver(EntityMetadata<T, ID> metadata, String tenant,
            Function<EntityRepository<T, ID>, R> read, Consumer<R> delivery) {
        Objects.requireNonNull(read); Objects.requireNonNull(delivery);
        var entry = entry(metadata.entityName()); requireProfile(entry.binding);
        if (entry.binding.metadata().entityType() != metadata.entityType()
                || !entry.binding.metadata().redisNamespace().equals(metadata.redisNamespace())) throw invalid();
        var key = ProtectionKey.of(metadata.redisNamespace(), tenant);
        if (!entry.dispatch.containsKey(key)) throw incomplete();
        try (var ignored = CacheTenantContext.withTenant(tenant)) {
            entry.participant.deliver(key, () -> read.apply((EntityRepository<T, ID>)entry.repository), delivery);
        }
    }

    private void requireProfile(EntityBinding<?, ?> binding) {
        requireOpen();
        if (database.config().runtimeRole() != CacheRuntimeRole.CLIENT_ONLY
                || database.config().readThrough().mode() != ReadThroughMode.REDIS_ONLY
                || !database.entityRegistry().projections(binding.metadata().entityName()).isEmpty()
                || !binding.metadata().relations().isEmpty() || !binding.metadata().tenantPurgeEnabled()) throw incomplete();
    }
    private Entry entry(String name) {
        requireOpen(); var entry = entries.get(name); if (entry == null) throw incomplete(); return entry;
    }
    private void requireOpen() { if (closed) throw incomplete(); }
    private static void requireAdmin() {
        if (!CacheTenantContext.isAdministrative()) throw new ProtectionException(ProtectionException.Code.ADMIN_REQUIRED);
    }
    @Override public void close() { closed = true; } // Admission only; never ACK, clear a roster, or claim drain.

    private final class Entry {
        final EntityBinding<?, ?> binding;
        final EntityRepository<?, ?> repository;
        final RedisPurgeTarget target;
        final String coverageToken;
        final RedisTenantRuntimeParticipant participant;
        final TenantReadFence fence = new BoundFence(this);
        final PurgeParticipant controlParticipant;
        RedisInitialSourceHydrator<?,?> initialSource;
        volatile Map<ProtectionKey, Dispatch> dispatch = Map.of();
        Entry(EntityBinding<?, ?> binding, EntityRepository<?, ?> repository,
                RedisEntityRepository<?, ?> actual, RedisTenantProducer producer) {
            this.binding = binding; this.repository = repository;
            target = actual.tenantPurgeTarget(List.of());
            coverageToken = ProtectionKey.hash("owned-synchronous-point-v1:" + target.fingerprint()
                    + ":CLIENT_ONLY:REDIS_ONLY:no-source-fill:no-projections:no-relations");
            participant = new RedisTenantRuntimeParticipant(database.redisOperations(), database.redisKeyStrategy(), producer,
                    () -> { try { requireProfile(binding); return true; } catch (RuntimeException unsafe) { return false; } });
            var declaration = new PurgeParticipant.Declaration("runtime-control-roster", coverageToken,
                    Set.of(binding.metadata().entityName()), true);
            controlParticipant = new PurgeParticipant() {
                public Declaration declaration() { return declaration; }
                public Result step(Request request) {
                    var approval = participant.control().approval();
                    var evidence = fence.closeAndDrain(request.lease(), request.generation(), approval.manifestToken(), "", 128, 5000);
                    if (evidence.state() != TenantReadFence.State.CLOSED)
                        return new Result(evidence.state() == TenantReadFence.State.INCOMPLETE ? State.INCOMPLETE : State.MORE,
                                "", 0, 0, null);
                    fence.requireClosed(request.lease(), request.generation(), approval.manifestToken(), evidence);
                    return new Result(State.VERIFIED, "", 0, 0, evidence.token());
                }
            };
        }
    }
    private record Dispatch(Entry entry, ProtectionKey key, RedisNativeTenantReadFence fence) {}
    private final class BoundFence implements TenantReadFence {
        private final Entry entry;
        BoundFence(Entry entry) { this.entry = entry; }
        private RedisNativeTenantReadFence nativeFence(Lease lease, String manifest) {
            requireAdmin(); requireProfile(entry.binding); entry.participant.control().requireSealed();
            if (lease == null || !entry.participant.control().approval().manifestToken().equals(manifest)) throw incomplete();
            var dispatch = entry.dispatch.get(lease.key()); if (dispatch == null) throw incomplete(); return dispatch.fence;
        }
        public Evidence closeAndDrain(Lease lease, long generation, String manifest, String checkpoint, int operations, int millis) {
            var evidence = nativeFence(lease, manifest).closeAndDrain(lease, generation, manifest, checkpoint, operations, millis);
            entry.participant.control().requireSealed();
            return evidence;
        }
        public void requireClosed(Lease lease, long generation, String manifest, Evidence evidence) {
            nativeFence(lease, manifest).requireClosed(lease, generation, manifest, evidence);
            entry.participant.control().requireSealed();
        }
        public void requireActivationCoverage(ProtectionKey key, String manifest) {
            requireAdmin(); requireProfile(entry.binding); entry.participant.control().requireSealed();
            if (!entry.participant.control().approval().manifestToken().equals(manifest)
                    || !entry.dispatch.containsKey(key)) throw incomplete();
        }
        public void release(Lease lease) { /* Borrowed participants; release never reopens or retires membership. */ }
    }
    private static ProtectionException invalid() { return new ProtectionException(ProtectionException.Code.INVALID_REQUEST); }
    private static ProtectionException incomplete() { return new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY); }
    private static ProtectionException busy() { return new ProtectionException(ProtectionException.Code.BUSY); }
}

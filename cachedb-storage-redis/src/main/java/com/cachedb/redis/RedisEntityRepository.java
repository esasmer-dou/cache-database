package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.observe.*;
import com.reactor.cachedb.core.api.ProjectionRepository;
import com.reactor.cachedb.core.api.RepositoryCapabilities;
import com.reactor.cachedb.core.api.RepositoryCapability;
import com.reactor.cachedb.core.cache.CacheAdmissionSource;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.change.ExternalChangeHydrationRepository;
import com.reactor.cachedb.core.config.PageCacheConfig;
import com.reactor.cachedb.core.config.QueryIndexConfig;
import com.reactor.cachedb.core.config.ReadShapeGuardrailConfig;
import com.reactor.cachedb.core.config.ReadThroughConfig;
import com.reactor.cachedb.core.config.RedisGuardrailConfig;
import com.reactor.cachedb.core.config.RelationConfig;
import com.reactor.cachedb.core.guardrail.ReadShapeGuardrails;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.model.WriteDependency;
import com.reactor.cachedb.core.model.WriteOperation;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.page.EntityByIdLoader;
import com.reactor.cachedb.core.page.EntityPageLoader;
import com.reactor.cachedb.core.page.EntityQueryLoader;
import com.reactor.cachedb.core.page.NoOpEntityByIdLoader;
import com.reactor.cachedb.core.page.NoOpEntityPageLoader;
import com.reactor.cachedb.core.page.NoOpEntityQueryLoader;
import com.reactor.cachedb.core.page.VersionedEntity;
import com.reactor.cachedb.core.page.VersionedEntitySourceLoader;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.projection.EntityProjectionBinding;
import com.reactor.cachedb.core.projection.ProjectionEntityCodec;
import com.reactor.cachedb.core.projection.ProjectionEntityMetadata;
import com.reactor.cachedb.core.query.PartitionedQuerySpec;
import com.reactor.cachedb.core.query.QueryEvaluator;
import com.reactor.cachedb.core.query.QueryExplainPlan;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.queue.PerformanceObservationContext;
import com.reactor.cachedb.core.queue.StoragePerformanceCollector;
import com.reactor.cachedb.core.queue.WriteBehindQueue;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;
import com.reactor.cachedb.core.relation.NoOpRelationBatchLoader;
import com.reactor.cachedb.core.relation.RelationBatchContext;
import com.reactor.cachedb.core.relation.RelationBatchLoader;
import com.reactor.cachedb.core.repository.HotLookup;
import com.reactor.cachedb.core.route.RouteCacheContext;
import com.reactor.cachedb.core.route.RouteCacheContract;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import redis.clients.jedis.resps.Tuple;

public final class RedisEntityRepository<T, ID>
        implements EntityRepository<T, ID>, ExternalChangeHydrationRepository<T, ID>,
                com.reactor.cachedb.core.api.RedisLexicalWindowRepository<T> {
    private final java.util.concurrent.locks.ReentrantLock watcherRegistrationLock = new java.util.concurrent.locks.ReentrantLock();
    private static final RepositoryCapabilities CAPABILITIES = RepositoryCapabilities.all();
    private static final RepositoryCapabilities LEGACY_CAPABILITIES = RepositoryCapabilities.of(
            java.util.Arrays.stream(RepositoryCapability.values())
                    .filter(capability -> capability != RepositoryCapability.BULK_DELETE)
                    .toArray(RepositoryCapability[]::new));

    private CacheObservationRecorder observationRecorder = NoOpCacheObservationRecorder.INSTANCE;
    private boolean observationEnabled;
    private Object observationOwner;
    private com.reactor.cachedb.core.trace.TraceContextPropagator observationWriteTrace;

    /** Configure once, before publication. Concrete maintenance capabilities remain intact. */
    public RedisEntityRepository<T, ID> withObservationRecorder(CacheObservationRecorder recorder) {
        java.util.Objects.requireNonNull(recorder, "recorder");
        if (observationRecorder != NoOpCacheObservationRecorder.INSTANCE && observationRecorder != recorder)
            throw new IllegalStateException("Observation recorder is already assigned");
        if (observationRecorder != recorder) {
            observationEnabled = recorder.enabled();
            observationOwner = observationEnabled ? new Object() : null;
            observationRecorder = recorder;
        }
        return this;
    }
    public RedisEntityRepository<T, ID> withObservationRecorder(CacheObservationRecorder recorder,
            com.reactor.cachedb.core.trace.TraceContextPropagator nativeWriteTrace) {
        withObservationRecorder(recorder);
        observationWriteTrace = observationEnabled ? nativeWriteTrace : null; return this;
    }
    private <V> V observeWrite(CacheOperation operation, java.util.function.Supplier<V> action) {
        return CacheObservations.call(observationRecorder, operation, metadata.entityName(), observationOwner, () -> {
            if (observationWriteTrace == null) return action.get();
            try (var trace = com.reactor.cachedb.core.trace.TraceContexts.withMetadata(observationWriteTrace.capture())) {
                return action.get();
            }
        });
    }
    private RedisEntityRepository<T, ID> withObservationState(RedisEntityRepository<T, ID> source) {
        observationRecorder = source.observationRecorder;
        observationWriteTrace = source.observationWriteTrace;
        observationEnabled = source.observationEnabled; observationOwner = source.observationOwner;
        return this;
    }
    private void observationTier(Tier tier) {
        if (observationEnabled) try {
            CacheObservationContext.tier(tier);
            if (tier == Tier.SOURCE) CacheObservationContext.current().databaseSystem("other");
        } catch (RuntimeException ignored) { }
    }
    private void observationOutcome(Outcome outcome) {
        if (observationEnabled) try { CacheObservationContext.outcome(outcome); } catch (RuntimeException ignored) { }
    }
    @Override public com.reactor.cachedb.core.api.AsyncEntityRepository<T, ID> async() {
        return async(asyncExecutor());
    }
    @Override public long count(QuerySpec spec) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return CacheObservations.call(observationRecorder, CacheOperation.COUNT, metadata.entityName(),
                    observationOwner, () -> count(spec));
        return EntityRepository.super.count(spec);
    }

    private final RedisClientOperations jedis;
    private final RedisClientOperations backgroundJedis;
    private final EntityMetadata<T, ID> metadata;
    private final EntityCodec<T> codec;
    private final WriteBehindQueue writeBehindQueue;
    private final RedisKeyStrategy keyStrategy;
    private final FetchPlan fetchPlan;
    private final CachePolicy cachePolicy;
    private final RedisFunctionExecutor functionExecutor;
    private final RedisProducerGuard producerGuard;
    private final RedisGuardrailConfig guardrailConfig;
    private final String streamKey;
    private final String compactionStreamKey;
    private final int compactionShardCount;
    private final EntityRegistry entityRegistry;
    private final RelationBatchLoader<T> relationBatchLoader;
    private final RelationConfig relationConfig;
    private final QueryIndexConfig queryIndexConfig;
    private final EntityPageLoader<T> pageLoader;
    private final EntityByIdLoader<T, ID> byIdLoader;
    private final EntityQueryLoader<T> queryLoader;
    private final PageCacheConfig pageCacheConfig;
    private final ReadThroughConfig readThroughConfig;
    private com.reactor.cachedb.core.cache.SingleFlightLoader<Object, Object> readFlights;
    private RedisEntityRepository<T, ID> withReadFlights(com.reactor.cachedb.core.cache.SingleFlightLoader<Object, Object> flights) {
        readFlights = flights; return this;
    }
    private final ReadShapeGuardrailConfig readShapeGuardrailConfig;
    private final RedisPageCacheManager<T, ID> pageCacheManager;
    private final QueryEvaluator queryEvaluator;
    private final RedisQueryIndexManager<T, ID> queryIndexManager;
    private final StoragePerformanceCollector performanceCollector;
    private final ProjectionRefreshDispatcher projectionRefreshDispatcher;
    private final RedisProjectionRefreshQueue projectionRefreshQueue;
    private final RedisVersionedHydrator versionedHydrator;
    private final RedisDurabilityTracker durabilityTracker;
    private RedisNearCacheCoordinator nearCacheCoordinator;
    private RedisNearCacheCoordinator.Store nearCache;
    private RedisProtectedPointReads<T, ID> tenantReadBridge;
    private RedisTenantProducer tenantProducer;

    /** Attach before repository publication; the owning runtime closes the producer. */
    public RedisEntityRepository<T, ID> withTenantProducer(RedisTenantProducer producer) {
        if (!metadata.tenantPurgeEnabled() || producer == null
                || !metadata.redisNamespace().equals(producer.namespace())
                || !producer.belongsToRuntime(jedis, keyStrategy)
                || tenantProducer != null && tenantProducer != producer)
            throw new com.reactor.cachedb.core.protection.ProtectionException(
                    com.reactor.cachedb.core.protection.ProtectionException.Code.INVALID_REQUEST);
        if (readThroughConfig.mode().byIdEnabled()
                && readThroughConfig.hydrateLoadedEntities())
            throw new com.reactor.cachedb.core.protection.ProtectionException(
                    com.reactor.cachedb.core.protection.ProtectionException.Code.UNWIRED_PROTOCOL);
        RedisProtectedPointReads.Source<T, ID> source = null;
        if (readThroughConfig.mode().byIdEnabled()) {
            var versioned = versionedSourceLoader(currentByIdLoader());
            if (versioned == null) throw new com.reactor.cachedb.core.protection.ProtectionException(
                    com.reactor.cachedb.core.protection.ProtectionException.Code.UNWIRED_PROTOCOL);
            source = (original, id) -> versioned.loadVersionedById(id);
        }
        if (functionExecutor == null) throw new com.reactor.cachedb.core.protection.ProtectionException(
                com.reactor.cachedb.core.protection.ProtectionException.Code.UNWIRED_PROTOCOL);
        functionExecutor.withProtectedRuntime(keyStrategy);
        var localReads = producer.bindPointReads(cachePolicy, nearCache);
        withTenantReadFence(localReads, source, null, java.util.Set.of());
        tenantProducer = producer;
        return this;
    }

    /** Actual manager descriptor, not a guessed layout or an approval of the supplied complete page inventory. */
    public RedisPurgeTarget tenantPurgeTarget(List<RedisPurgeTarget.Page> completePages) {
        if (!metadata.tenantPurgeEnabled() || tenantProducer == null)
            throw new com.reactor.cachedb.core.protection.ProtectionException(
                    com.reactor.cachedb.core.protection.ProtectionException.Code.UNWIRED_PROTOCOL);
        return pageCacheManager.purgeTarget(completePages);
    }

    /** Lends these ACTUAL managers and the registered codec; no second index manager or transport is created. */
    public RedisInitialSourceHydrator<T,ID> initialSourceHydrator(
            com.reactor.cachedb.core.protection.InitialSourceHotWindow<T,ID> window,RedisTenantRuntimeControl control) {
        if(tenantProducer==null || window.metadata()!=metadata || window.codec()!=codec)
            throw new com.reactor.cachedb.core.protection.ProtectionException(
                    com.reactor.cachedb.core.protection.ProtectionException.Code.UNWIRED_PROTOCOL);
        return new RedisInitialSourceHydrator<>(jedis,keyStrategy,metadata,codec,cachePolicy,queryIndexManager,
                pageCacheManager,nearCache,tenantProducer,control,window);
    }

    /** Borrowed actual namespace store. Null means near cache was disabled for this runtime. */
    public RedisNearCacheCoordinator.Store tenantNearCache() { return nearCache; }

    private void requireProtectedRuntime(String tenant) {
        if (tenantProducer == null || tenant == null || functionExecutor == null || !functionExecutor.enabled()
                || !tenantProducer.belongsToRuntime(jedis, keyStrategy)
                || watching() || !fetchPlan.includes().isEmpty()
                || entityRegistry != null && !entityRegistry.projections(metadata.entityName()).isEmpty())
            throw new com.reactor.cachedb.core.protection.ProtectionException(
                    com.reactor.cachedb.core.protection.ProtectionException.Code.UNWIRED_PROTOCOL);
    }

    private boolean ownedProtectedMutation(String tenant) {
        // Also validate cloned views and changed projection/watcher state when a ticket already exists.
        requireProtectedRuntime(tenant);
        return tenantProducer.ownsCurrent(tenant);
    }

    private <R> R protectedMutation(String tenant, java.util.function.Supplier<R> action) {
        requireProtectedRuntime(tenant);
        return tenantProducer.execute(tenant, () -> {
            try (var scope = com.reactor.cachedb.core.tenant.CacheTenantContext.withTenant(tenant)) {
                return action.get();
            }
        });
    }

    private void requireProtectedMutation() {
        if (metadata.tenantPurgeEnabled()) {
            String tenant = activeTenant();
            requireProtectedRuntime(tenant);
            tenantProducer.requireCurrent(tenant);
        }
    }


    /** Bind once, after L1 setup, using the same facade-owned generation barrier. */
    public RedisEntityRepository<T, ID> withTenantReadFence(RedisTenantReadFence fence,
            RedisProtectedPointReads.Source<T, ID> source, RedisProtectedPointReads.Fill<T> fill,
            java.util.Set<String> reviewedIdentityProjections) {
        if (tenantReadBridge != null)
            throw RedisTenantReadFence.failure(com.reactor.cachedb.core.protection.ProtectionException.Code.INVALID_REQUEST);
        tenantReadBridge = new RedisProtectedPointReads<>(fence, metadata, codec, keyStrategy,
                nearCache, pageCacheManager::shouldServeCachedEntity, source, fill, reviewedIdentityProjections);
        return this;
    }

    public <P> int refreshProtectedProjection(EntityProjection<T, P, ID> projection,
            Collection<ID> ids, RedisProtectedPointReads.ProjectionFill<P> planner) {
        return protectedPointReads().refreshProjection(activeTenant(), projection, ids, planner);
    }

    private RedisEntityRepository<T, ID> withTenantReadBridgeState(RedisEntityRepository<T, ID> original) {
        tenantReadBridge = original.tenantReadBridge;
        tenantProducer = original.tenantProducer;
        return this;
    }

    private RedisProtectedPointReads<T, ID> protectedPointReads() {
        if (pointReads != null) pointReads.ensureOpen();
        if (tenantReadBridge == null || !fetchPlan.includes().isEmpty())
            throw RedisTenantReadFence.failure(com.reactor.cachedb.core.protection.ProtectionException.Code.UNWIRED_PROTOCOL);
        return tenantReadBridge;
    }

    private void rejectUncertifiedProtectedRead() {
        if (metadata.tenantPurgeEnabled())
            throw RedisTenantReadFence.failure(com.reactor.cachedb.core.protection.ProtectionException.Code.INCOMPLETE_INVENTORY);
    }
    private RedisRepositoryReadState pointReads;
    public RedisEntityRepository<T, ID> withReadRuntime(RedisRepositoryReadState state) {
        if (pointReads != null && pointReads != state) throw new IllegalStateException("Repository read runtime is already assigned");
        pointReads = state; return this;
    }
    /** Null only for legacy standalone repositories without an injected runtime owner. */
    public RedisReadRuntime redisReadRuntime() { return pointReads == null ? null : pointReads.runtime(); }
    private VerifiedOwners verifiedOwners = new VerifiedOwners();
    private RedisEntityRepository<T, ID> withVerifiedOwners(VerifiedOwners owners) { verifiedOwners = owners; return this; }
    private static final class VerifiedOwners {
        private final java.util.concurrent.locks.ReentrantLock ownersLock = new java.util.concurrent.locks.ReentrantLock();
        private final LinkedHashMap<String, String> owners = new LinkedHashMap<>(16, 0.75f, true);
        private long bytes;
        String get(Object id) { ownersLock.lock(); try { return owners.get(String.valueOf(id)); } finally { ownersLock.unlock(); } }
        void put(Object id, String owner) {
            ownersLock.lock();
            try {
                String key = String.valueOf(id);
                if (key.length() > 4096) return;
                String previous = owners.put(key, owner);
                if (previous != null) bytes -= 128L + 2L * (key.length() + previous.length());
                bytes += 128L + 2L * (key.length() + owner.length());
                while (owners.size() > 16_384 || bytes > 4L * 1024 * 1024) {
                    var first = owners.entrySet().iterator(); var victim = first.next();
                    bytes -= 128L + 2L * (victim.getKey().length() + victim.getValue().length()); first.remove();
                }
            } finally {
                ownersLock.unlock();
            }
        }
    }
    private static final class WatchState<T, ID> {
        private final java.util.concurrent.locks.ReentrantLock registrationLock = new java.util.concurrent.locks.ReentrantLock();
        volatile RedisWatchRegistry<T, ID> registry;
        int limit = 64;
    }
    private WatchState<T, ID> watchState = new WatchState<>();
    private RedisEntityRepository<T, ID> withWatchState(WatchState<T, ID> state) { watchState = state; return this; }

    public RedisEntityRepository<T, ID> withWatcherLimit(int limit) {
        if (limit < 1 || limit > 64 || watchState.registry != null) throw new IllegalArgumentException("Invalid watcher limit or late configuration");
        watchState.limit = limit; return this;
    }
    @Override public com.reactor.cachedb.core.watch.CacheWatcherRegistration registerWatcher(QuerySpec spec,
            com.reactor.cachedb.core.watch.CacheWatcherListener<T, ID> listener) {
        rejectUncertifiedProtectedRead();
        watcherRegistrationLock.lock();
        try {
            if (metadata.tenantFieldName() != null && activeTenant() == null)
                throw new IllegalStateException("Tenant watcher requires an explicit tenant context");
            watchState.registrationLock.lock();
            try {
                if (watchState.registry == null) watchState.registry = new RedisWatchRegistry<>(metadata, codec, queryEvaluator, watchState.limit);
                return watchState.registry.register(tenantQuery(spec), listener);
            } finally {
                watchState.registrationLock.unlock();
            }
        } finally {
            watcherRegistrationLock.unlock();
        }
    }
    public long droppedWatcherNotifications() { var registry = watchState.registry; return registry == null ? 0 : registry.dropped(); }
    private boolean watching() { var registry = watchState.registry; return registry != null && registry.active(); }
    private record WatchBefore(String payload, long version) {}
    private WatchBefore watchBefore(ID id) {
        if (!watching()) return null;
        var values = jedis.mget(keyStrategy.entityKey(metadata.redisNamespace(), id),
                keyStrategy.versionKey(metadata.redisNamespace(), id), keyStrategy.tombstoneKey(metadata.redisNamespace(), id));
        long version = values.get(1) == null ? 0 : Long.parseLong(values.get(1));
        if (values.get(0) == null && values.get(2) == null && version > 0)
            throw new com.reactor.cachedb.core.repository.HotUpdateUnavailableException(
                    com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata) ? null : id);
        return new WatchBefore(values.get(2) == null ? values.get(0) : null, version);
    }
    private void notifyWatchers(ID id, String before, String after) {
        var registry = watchState.registry;
        if (registry != null) try { registry.changed(id, before, after); }
        catch (RuntimeException failure) { System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Watcher notification failed after write acceptance", failure); }
    }

    public RedisEntityRepository<T, ID> withNearCache(RedisNearCacheCoordinator coordinator) {
        var policy = cachePolicy.nearCachePolicy();
        if (!policy.enabled()) return this;
        if (coordinator == null || !functionExecutor.enabled())
            throw new IllegalStateException("Near cache requires a runtime coordinator and atomic Redis Functions");
        nearCacheCoordinator = coordinator;
        nearCache = coordinator.register(metadata.redisNamespace(), policy);
        return this;
    }

    private com.reactor.cachedb.core.async.CacheAsyncExecutor asyncGate;

    public RedisEntityRepository<T, ID> withAsyncExecutor(com.reactor.cachedb.core.async.CacheAsyncExecutor gate) {
        if (asyncGate != null && asyncGate != gate) throw new IllegalStateException("Async gate is already attached");
        asyncGate = gate;
        return this;
    }

    @Override public com.reactor.cachedb.core.async.CacheAsyncExecutor asyncExecutor() {
        if (asyncGate == null) throw new UnsupportedOperationException("Attach a runtime-owned CacheAsyncExecutor");
        return asyncGate;
    }

    @Override public com.reactor.cachedb.core.api.AsyncEntityRepository<T, ID> async(
            com.reactor.cachedb.core.async.CacheAsyncExecutor gate) {
        java.util.Objects.requireNonNull(gate, "executor");
        com.reactor.cachedb.core.api.AsyncEntityRepository<T, ID> delegate = pointReads == null || metadata.tenantPurgeEnabled()
                ? new com.reactor.cachedb.core.async.OffloadingAsyncEntityRepository<>(this, gate)
                : new NativeAsyncRepository(gate);
        return observationEnabled
                ? ObservingAsyncEntityRepository.wrap(delegate, metadata.entityName(), observationRecorder, observationOwner, observationWriteTrace)
                : delegate;
    }

    private HotLookup<T> decodeAsyncPoint(ID id, String entityKey, RedisPointRead read,
            boolean local, long epoch, boolean touch) {
        if (read.tombstone() != null) {
            if (touch) observationOutcome(Outcome.TOMBSTONE);
            return HotLookup.tombstoned();
        }
        String payload = read.payload();
        if (payload == null) return HotLookup.notCached();
        T entity = codec.fromRedisValue(payload);
        verifyTenant(entity);
        if (!pageCacheManager.shouldServeCachedEntity(entity)) {
            if (effectiveCachePolicy().hotPolicy().evictWhenRejected()) pageCacheManager.removeEntity(id);
            return HotLookup.outsidePolicy();
        }
        if (touch) pageCacheManager.recordEntityAccess(id);
        if (local && nearCacheActive()) nearCache.put(String.valueOf(id), payload,
                shareableNearEntity(entity, payload), pointReadTtl(read, entityKey), epoch, 0);
        return HotLookup.hit(entity);
    }

    private RedisAsyncReadWindow.Row<T> nearAsyncRow(ID id, RedisAsyncReadDelivery<?> delivery) {
        if (!nearCacheActive()) return null;
        String identity = String.valueOf(id);
        var entry = nearCache.lookup(identity, requiredVersion(id));
        if (entry == null || !nearCacheActive()) return null;
        delivery.charge(com.reactor.cachedb.core.codec.Utf8Length.of(entry.payload()));
        @SuppressWarnings("unchecked") T entity = entry.decoded() == null || !canShareNear(entry.payload())
                ? codec.fromRedisValue(entry.payload()) : (T) entry.decoded();
        verifyTenant(entity);
        if (!pageCacheManager.shouldServeCachedEntity(entity)) { invalidateNear(id); return null; }
        if (entry.softExpired()) {
            Runnable refresh = com.reactor.cachedb.core.tenant.CacheTenantContext.wrap(() -> refreshNear(id));
            nearCacheCoordinator.refresh(nearCache, identity, refresh);
        }
        observationTier(Tier.L1);
        return new RedisAsyncReadWindow.Row<>(entity,
                com.reactor.cachedb.core.codec.Utf8Length.of(entry.payload()), false, entry.payload());
    }

    private final class NativeAsyncRepository implements com.reactor.cachedb.core.api.AsyncEntityRepository<T, ID> {
        private final com.reactor.cachedb.core.async.CacheAsyncExecutor gate;
        private final com.reactor.cachedb.core.async.OffloadingAsyncEntityRepository<T, ID> fallback;
        NativeAsyncRepository(com.reactor.cachedb.core.async.CacheAsyncExecutor gate) {
            this.gate = gate;
            fallback = new com.reactor.cachedb.core.async.OffloadingAsyncEntityRepository<>(RedisEntityRepository.this, gate);
        }

        private <R> java.util.concurrent.CompletableFuture<R> guarded(
                java.util.function.Supplier<java.util.concurrent.CompletableFuture<R>> call) {
            try {
                if (pointReads != null) pointReads.ensureOpen();
                if (com.reactor.cachedb.core.api.CacheUnitOfWork.active())
                    throw new IllegalStateException("Async CacheDB operations are unavailable inside a synchronous unit of work");
                if (!gate.accepting()) throw new com.reactor.cachedb.core.async.CacheAsyncRejectedException(
                        com.reactor.cachedb.core.async.CacheAsyncRejectedException.Reason.CLOSED);
                activeTenant();
                return call.get();
            } catch (RuntimeException failure) { return java.util.concurrent.CompletableFuture.failedFuture(failure); }
        }

        private RedisAsyncReadBudget readBudget() {
            if (pointReads == null) throw new UnsupportedOperationException("Bounded native reads require runtime read state");
            return pointReads.asyncReadBudget();
        }

        private <R> java.util.concurrent.CompletableFuture<R> worker(java.util.concurrent.Callable<R> call) {
            var delivery = new RedisAsyncReadDelivery<R>(readBudget().reserveWorker());
            try {
                delivery.checkActive();
                var operation = gate.submitTracked(() -> {
                    delivery.checkActive();
                    return call.call();
                });
                delivery.bind(operation.result(), operation.physical());
            } catch (Throwable failure) { delivery.failSetup(failure); }
            finally { delivery.finishSetup(); }
            return delivery.future();
        }

        private boolean nativePoint(ID id) {
            java.util.Objects.requireNonNull(id, "id");
            return pointReads != null && fetchPlan.includes().isEmpty()
                    && pointReads.useAsyncTransport(requiredVersion(id));
        }

        private java.util.concurrent.CompletableFuture<HotLookup<T>> hot(ID id) {
            long started = System.nanoTime();
            boolean local = nearCacheActive();
            long epoch = local ? nearCache.epoch() : 0; // Conservative global invalidation stamp, captured before I/O.
            T cached = nearValue(id);
            if (cached != null) {
                recordRedisRead(started);
                return java.util.concurrent.CompletableFuture.completedFuture(HotLookup.hit(cached));
            }
            String entityKey = keyStrategy.entityKey(metadata.redisNamespace(), id);
            String tombstone = keyStrategy.tombstoneKey(metadata.redisNamespace(), id);
            var delivery = new RedisAsyncReadDelivery<HotLookup<T>>(readBudget().reservePoint());
            try {
                delivery.checkActive();
                var result = gate.submitExternal(() -> {
                    delivery.checkActive();
                    observationTier(Tier.REDIS);
                    var raw = pointReads.runtime().readAsync(entityKey, tombstone, local);
                    return new com.reactor.cachedb.core.async.CacheAsyncExecutor.ExternalOperation<>(
                            raw, pointReads.runtime().physicalCompletion(raw));
                }, read -> {
                    delivery.checkActive();
                    return decodeAsyncPoint(id, entityKey, read, local, epoch, true);
                });
                delivery.bind(gate, result);
            } catch (Throwable failure) { delivery.failSetup(failure); }
            finally { delivery.finishSetup(); }
            delivery.future().whenComplete((ignored, failure) -> recordRedisRead(started));
            return delivery.future();
        }

        @Override public java.util.concurrent.CompletableFuture<HotLookup<T>> findHotByIdAsync(ID id) {
            return guarded(() -> nativePoint(id) ? hot(id) : worker(() -> RedisEntityRepository.this.findHotById(id)));
        }

        @Override public java.util.concurrent.CompletableFuture<Optional<T>> findByIdAsync(ID id) {
            return guarded(() -> {
                if (!nativePoint(id)) return worker(() -> RedisEntityRepository.this.findById(id));
                return RedisAsyncContinuation.compose(gate, hot(id), lookup -> {
                    if (!lookup.isNotCached()) return java.util.concurrent.CompletableFuture.completedFuture(lookup.optional());
                    // These two outcomes require no source, fetch-plan, extra Redis read or worker.
                    if (!readThroughConfig.mode().byIdEnabled()) {
                        recordReadThroughEvent("by-id-disabled", false);
                        return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
                    }
                    if (currentByIdLoader() instanceof NoOpEntityByIdLoader) {
                        if (readThroughConfig.failOnMissingLoader()) return java.util.concurrent.CompletableFuture.failedFuture(
                                new IllegalStateException("Redis miss for " + metadata.entityName() + " id=" + id
                                        + " but no EntityByIdLoader is registered"));
                        recordReadThroughEvent("by-id-loader-missing", false);
                        return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
                    }
                    // hot's physical/contextual permit was released before this continuation; maxConcurrent=1 is safe.
                    return worker(() -> {
                        var loaded = loadByIdFromSource(id);
                        loaded.ifPresent(RedisEntityRepository.this::verifyTenant);
                        return loaded;
                    });
                });
            });
        }

        private com.reactor.cachedb.core.async.CacheAsyncExecutor.ExternalOperation<RedisAsyncReadWindow.Row<T>> row(ID id, RedisAsyncReadDelivery<?> delivery) {
            boolean local = nearCacheActive();
            long epoch = local ? nearCache.epoch() : 0;
            delivery.checkActive();
            var cached = nearAsyncRow(id, delivery);
            if (cached != null) return new com.reactor.cachedb.core.async.CacheAsyncExecutor.ExternalOperation<>(
                    java.util.concurrent.CompletableFuture.completedFuture(cached),
                    java.util.concurrent.CompletableFuture.completedFuture(null));
            String entityKey = keyStrategy.entityKey(metadata.redisNamespace(), id);
            observationTier(Tier.REDIS);
            var raw = pointReads.runtime().readAsync(entityKey,
                    keyStrategy.tombstoneKey(metadata.redisNamespace(), id), local);
            return gate.mapExternal(new com.reactor.cachedb.core.async.CacheAsyncExecutor.ExternalOperation<>(
                    raw, pointReads.runtime().physicalCompletion(raw)), read -> {
                if (read.payload() != null && read.tombstone() == null)
                    delivery.charge(com.reactor.cachedb.core.codec.Utf8Length.of(read.payload()));
                else delivery.checkActive();
                var lookup = decodeAsyncPoint(id, entityKey, read, local, epoch, false);
                return new RedisAsyncReadWindow.Row<>(lookup.value(), lookup.isHit()
                        ? com.reactor.cachedb.core.codec.Utf8Length.of(read.payload()) : 0, lookup.isHit(),
                        lookup.isHit() ? read.payload() : null);
            });
        }

        private List<T> finishBulk(List<ID> ids, List<ID> unique, Map<ID, Integer> indexes,
                List<RedisAsyncReadWindow.Row<T>> rows, RedisAsyncReadDelivery<?> delivery) {
            long emittedBytes = 0;
            for (ID id : ids) {
                var row = rows.get(indexes.get(id));
                if (row.value() != null) {
                    if (row.bytes() > RedisAsyncReadBudget.CONTENT_BYTES - emittedBytes)
                        throw new IllegalArgumentException("Async bulk output including duplicate IDs exceeds 16 MiB");
                    emittedBytes += row.bytes();
                }
            }
            var entities = new ArrayList<T>();
            var accessed = new ArrayList<ID>();
            for (int i = 0; i < unique.size(); i++) if (rows.get(i).accessed()) accessed.add(unique.get(i));
            var emitted = new java.util.HashSet<ID>();
            for (ID id : ids) {
                delivery.checkActive();
                var row = rows.get(indexes.get(id));
                if (row.value() == null) continue;
                T value = row.value();
                if (!emitted.add(id) && !metadata.deeplyImmutable()) {
                    if (row.encoded() == null) throw new IllegalStateException("Missing bounded duplicate payload");
                    delivery.chargeDuplicate(row.bytes());
                    value = codec.fromRedisValue(row.encoded());
                    verifyTenant(value);
                }
                entities.add(value);
            }
            if (!accessed.isEmpty()) pageCacheManager.recordEntityAccessBatch(accessed);
            return List.copyOf(entities);
        }

        private List<RedisAsyncReadWindow.Row<T>> causalBulk(
                List<ID> unique, RedisAsyncReadDelivery<?> delivery) {
            var rows = new ArrayList<RedisAsyncReadWindow.Row<T>>(unique.size());
            for (int start = 0; start < unique.size(); start += 128) {
                delivery.checkActive();
                var batch = unique.subList(start, Math.min(start + 128, unique.size()));
                var versions = RedisAsyncBoundedValues.read(jedis, batch.stream()
                        .map(id -> keyStrategy.versionKey(metadata.redisNamespace(), id)).toList(), 4096);
                for (int i = 0; i < batch.size(); i++) verifyCausalVersion(batch.get(i), versions.get(i));
                var keys = new ArrayList<RedisBoundedPointRead.KeyPair>(batch.size());
                for (ID id : batch) keys.add(new RedisBoundedPointRead.KeyPair(
                        keyStrategy.entityKey(metadata.redisNamespace(), id),
                        keyStrategy.tombstoneKey(metadata.redisNamespace(), id), false));
                RedisPointRead[] values;
                try { values = RedisBoundedPointRead.read(jedis, keys, RedisAsyncReadBudget.CONTENT_BYTES); }
                catch (redis.clients.jedis.exceptions.JedisDataException budget) {
                    if (keys.size() == 1 || !("CACHEDB_READ_BUDGET".equals(budget.getMessage())
                            || "ERR CACHEDB_READ_BUDGET".equals(budget.getMessage()))) throw budget;
                    for (int i = 0; i < batch.size(); i++) {
                        delivery.checkActive();
                        var read = RedisBoundedPointRead.read(jedis, List.of(keys.get(i)),
                                RedisAsyncReadBudget.CONTENT_BYTES)[0];
                        rows.add(causalRow(batch.get(i), keys.get(i).entity(), read, delivery));
                    }
                    continue;
                }
                for (int i = 0; i < batch.size(); i++) {
                    RedisPointRead read = values[i];
                    values[i] = null;
                    rows.add(causalRow(batch.get(i), keys.get(i).entity(), read, delivery));
                }
            }
            return rows;
        }

        private RedisAsyncReadWindow.Row<T> causalRow(
                ID id, String key, RedisPointRead read, RedisAsyncReadDelivery<?> delivery) {
            delivery.checkActive();
            if (requiredVersion(id) > 0 && read.payload() == null && read.tombstone() == null)
                throw new IllegalStateException("Causal bulk entity payload unavailable; source cannot certify it");
            if (read.payload() != null && read.tombstone() == null)
                delivery.charge(com.reactor.cachedb.core.codec.Utf8Length.of(read.payload()));
            var lookup = decodeAsyncPoint(id, key, read, false, 0, false);
            return new RedisAsyncReadWindow.Row<>(lookup.value(), lookup.isHit()
                    ? com.reactor.cachedb.core.codec.Utf8Length.of(read.payload()) : 0, lookup.isHit(),
                    lookup.isHit() ? read.payload() : null);
        }

        @Override public java.util.concurrent.CompletableFuture<List<T>> findAllAsync(Collection<ID> input) {
            return guarded(() -> {
                java.util.Objects.requireNonNull(input, "ids");
                if (!fetchPlan.includes().isEmpty()) throw new UnsupportedOperationException(
                        "Bounded async bulk fetch plans require a relation-result size contract");
                var delivery = new RedisAsyncReadDelivery<List<T>>(readBudget().reserveBulk());
                try {
                    // Reserve globally BEFORE even iterating or copying the caller collection.
                    var ids = new ArrayList<ID>();
                    for (ID id : input) {
                        delivery.checkActive();
                        if (ids.size() == QuerySpec.MAX_LIMIT)
                            throw new IllegalArgumentException("Async bulk input exceeds 100000 IDs");
                        ids.add(java.util.Objects.requireNonNull(id, "id"));
                    }
                    if (ids.isEmpty()) {
                        delivery.bind(java.util.concurrent.CompletableFuture.completedFuture(List.of()),
                                java.util.concurrent.CompletableFuture.completedFuture(null));
                        return delivery.future();
                    }
                    validateEntityRouteContract("Entity bulk read", ids.size());
                    var indexes = new LinkedHashMap<ID, Integer>();
                    for (ID id : ids) if (!indexes.containsKey(id)) indexes.put(id, indexes.size());
                    var unique = List.copyOf(indexes.keySet());
                    if (ids.stream().anyMatch(id -> requiredVersion(id) > 0)) {
                        var operation = gate.submitTracked(() ->
                                finishBulk(ids, unique, indexes, causalBulk(unique, delivery), delivery));
                        delivery.bind(operation.result(), operation.physical());
                    } else {
                        if (!pointReads.useAsyncTransport(0)) throw new IllegalStateException("Native transport unavailable");
                        var window = new RedisAsyncReadWindow<T, ID>(unique, gate, id -> row(id, delivery));
                        var prepared = window.prepare();
                        var result = gate.submitExternal(() -> prepared,
                                rows -> finishBulk(ids, unique, indexes, rows, delivery));
                        var producer = java.util.concurrent.CompletableFuture.allOf(
                                window.deliveryCompletion(), gate.deliveryCompletion(result));
                        delivery.bind(result, producer);
                        if (result.isCompletedExceptionally() || delivery.future().isDone()) prepared.result().cancel(false);
                        window.activate();
                    }
                } catch (Throwable failure) { delivery.failSetup(failure); }
                finally { delivery.finishSetup(); }
                return delivery.future();
            });
        }

        @Override public java.util.concurrent.CompletableFuture<T> saveAsync(T entity) {
            return guarded(() -> fallback.saveAsync(entity));
        }
        @Override public java.util.concurrent.CompletableFuture<Void> deleteAsync(ID id) {
            return guarded(() -> fallback.deleteAsync(id));
        }
        @Override public java.util.concurrent.CompletableFuture<List<T>> queryAsync(QuerySpec query) {
            return guarded(() -> fallback.queryAsync(query));
        }
        @Override public java.util.concurrent.CompletableFuture<com.reactor.cachedb.core.query.AggregateResult> aggregateAsync(
                com.reactor.cachedb.core.query.AggregateSpec spec) {
            return guarded(() -> fallback.aggregateAsync(spec));
        }
        @Override public java.util.concurrent.CompletableFuture<Map<String, com.reactor.cachedb.core.query.AggregateResult>> aggregateByAsync(
                String field, com.reactor.cachedb.core.query.AggregateSpec spec) {
            return guarded(() -> fallback.aggregateByAsync(field, spec));
        }
    }

    private boolean nearCacheActive() {
        var route = RouteCacheContext.currentContract();
        return nearCache != null && nearCacheCoordinator.connected()
                && (route == null || !route.tenantQuota().bounded());
    }

    private void invalidateNear(ID id) { if (nearCache != null) nearCache.invalidate(String.valueOf(id)); }
    private T nearValue(ID id) {
        if (!nearCacheActive()) return null;
        String identity = String.valueOf(id);
        var entry = nearCache.lookup(identity, requiredVersion(id));
        if (entry == null || !nearCacheActive()) return null;
        @SuppressWarnings("unchecked") T entity = entry.decoded() == null || !canShareNear(entry.payload())
                ? codec.fromRedisValue(entry.payload()) : (T) entry.decoded();
        verifyTenant(entity);
        if (!pageCacheManager.shouldServeCachedEntity(entity)) { invalidateNear(id); return null; }
        if (entry.softExpired()) {
            Runnable refresh = com.reactor.cachedb.core.tenant.CacheTenantContext.wrap(() -> refreshNear(id));
            nearCacheCoordinator.refresh(nearCache, identity, refresh);
        }
        observationTier(Tier.L1);
        return entity;
    }

    private long requiredVersion(ID id) {
        return com.reactor.cachedb.core.causal.CacheCausalContext.requiredVersion(metadata.redisNamespace(), id);
    }

    private void requireUnversionedReadAllowed() {
        rejectUncertifiedProtectedRead();
        if (com.reactor.cachedb.core.causal.CacheCausalContext.hasRequirements(metadata.redisNamespace()))
            throw new com.reactor.cachedb.core.exception.CacheCausalReadUnsupportedException();
    }

    private void verifyCausalVersion(ID id, String version) {
        long required = requiredVersion(id);
        if (required > 0 && (version == null || Long.parseLong(version) < required))
            throw new IllegalStateException("Causal version is not available for " + metadata.entityName());
    }

    private List<String> pointValues(ID id, boolean versioned) {
        String entity = keyStrategy.entityKey(metadata.redisNamespace(), id);
        String deleted = keyStrategy.tombstoneKey(metadata.redisNamespace(), id);
        if (!versioned) return jedis.mget(entity, deleted);
        String version = keyStrategy.versionKey(metadata.redisNamespace(), id);
        if (!jedis.clustered()) return jedis.mget(entity, deleted, version);
        return RedisCausalReads.read(jedis, List.of(entity), List.of(deleted), List.of(version),
                (index, observed) -> verifyCausalVersion(id, observed));
    }

    private long pointReadTtl(RedisPointRead read, String entityKey) {
        if (read == null) return jedis.commands().pttl(entityKey); // Unchanged legacy bypass path.
        long ttl = read.ttlMillis();
        if (ttl == RedisPointRead.TTL_NOT_REQUESTED) return 0; // Never cache an unqualified TTL.
        if (ttl > 0) ttl = Math.max(0, ttl - (System.nanoTime() - read.observedAtNanos() + 999_999L) / 1_000_000L);
        return ttl;
    }

    private void refreshNear(ID id) {
        if (!nearCacheActive()) return;
        var stamp = nearCache.capture(String.valueOf(id));
        long started = System.nanoTime();
        var values = pointValues(id, true);
        if (values.get(0) == null || values.get(1) != null) { invalidateNear(id); return; }
        T entity = codec.fromRedisValue(values.get(0)); verifyTenant(entity);
        if (!pageCacheManager.shouldServeCachedEntity(entity)) { invalidateNear(id); return; }
        long ttl = jedis.commands().pttl(keyStrategy.entityKey(metadata.redisNamespace(), id));
        if (ttl > 0) ttl = Math.max(0, ttl - (System.nanoTime() - started + 999_999L) / 1_000_000L);
        if (nearCacheActive()) nearCache.put(String.valueOf(id), values.get(0), shareableNearEntity(entity, values.get(0)),
                ttl, stamp, values.get(2) == null ? 0 : Long.parseLong(values.get(2)));
    }

    private Object shareableNearEntity(T entity, String payload) {
        return canShareNear(payload) ? entity : null;
    }
    private boolean canShareNear(String payload) {
        return metadata.deeplyImmutable() && metadata.schemaVersion() == 1 && fetchPlan.includes().isEmpty()
                && !payload.startsWith("~Z:") && !payload.startsWith("~v");
    }

    private String activeTenant() {
        return com.reactor.cachedb.core.tenant.TenantAccess.active(metadata);
    }

    private void requireTenantMaintenanceScope() {
        com.reactor.cachedb.core.protection.TenantProtectionGate.requireLegacy(metadata);
        if (metadata.tenantFieldName() != null
                && !com.reactor.cachedb.core.tenant.CacheTenantContext.isAdministrative())
            throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException(metadata.entityName());
    }

    private void verifyTenant(T entity) {
        requireProtectedMutation();
        com.reactor.cachedb.core.tenant.TenantAccess.verify(metadata, entity);
    }

    private QuerySpec tenantQuery(QuerySpec query) {
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.validateQuery(metadata, query);
        return com.reactor.cachedb.core.tenant.TenantAccess.scoped(metadata, query);
    }

    private void ensureTenantOwner(ID id, boolean create) {
        requireProtectedMutation();
        if (metadata.tenantPurgeEnabled()) {
            String tenant = activeTenant();
            if (!tenant.equals(jedis.commands().get(keyStrategy.versionKey(metadata.redisNamespace(), id) + ":tenant")))
                throw new com.reactor.cachedb.core.protection.ProtectionException(
                        com.reactor.cachedb.core.protection.ProtectionException.Code.MIGRATION_REQUIRED);
            return;
        }
        verifyTenantOwner(id, create, true);
    }

    private boolean verifyTenantOwner(ID id, boolean create, boolean initialize) {
        String tenant = activeTenant();
        if (tenant == null) return true;
        if (!functionExecutor.enabled()) throw new IllegalStateException("Tenant writes require atomic Redis Functions");
        String known = verifiedOwners.get(id);
        if (known != null) {
            if (!known.equals(tenant)) throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException(metadata.entityName());
            return true;
        }
        String ownerKey = keyStrategy.versionKey(metadata.redisNamespace(), id) + ":tenant";
        String owner = jedis.commands().get(ownerKey);
        return verifyObservedOwner(id, tenant, create, initialize, owner);
    }

    private boolean verifyObservedOwner(ID id, String tenant, boolean create, boolean initialize, String owner) {
        String ownerKey = keyStrategy.versionKey(metadata.redisNamespace(), id) + ":tenant";
        if (owner == null) {
            var owners = loadSourceOwners(List.of(id));
            String sourceOwner = owners.get(id);
            if (sourceOwner != null && !tenant.equals(sourceOwner) || !create && sourceOwner == null)
                throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException(metadata.entityName());
            if (!initialize) return false;
            jedis.commands().setnx(ownerKey, tenant);
            owner = jedis.commands().get(ownerKey);
        }
        if (!tenant.equals(owner)) throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException(metadata.entityName());
        verifiedOwners.put(id, owner);
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<ID, String> loadSourceOwners(Collection<ID> ids) {
        var loader = currentByIdLoader();
        if (!(loader instanceof com.reactor.cachedb.core.page.EntityOwnershipLoader<?>))
            throw new IllegalStateException("Tenant ownership bootstrap requires a trusted EntityOwnershipLoader; registerJdbcBacked supplies one");
        Map<ID, String> result;
        try (var admin = com.reactor.cachedb.core.tenant.CacheTenantContext.administrative()) {
            result = ((com.reactor.cachedb.core.page.EntityOwnershipLoader<ID>) loader).loadTenantOwners(ids);
        }
        if (result == null || result.size() > ids.size() || !new java.util.HashSet<>(ids).containsAll(result.keySet()))
            throw new IllegalStateException("Invalid complete ownership source result");
        for (String owner : result.values()) if (owner == null || owner.isBlank() || owner.length() > 256)
            throw new IllegalStateException("Invalid source tenant identity");
        return result;
    }

    private void verifyProtectedWriteOwner(ID id, String tenant) {
        requireProtectedMutation();
        if (metadata.tenantPurgeEnabled()) { ensureTenantOwner(id, false); return; }
        if (tenant == null || !com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata)) return;
        String known = verifiedOwners.get(id);
        if (known == null) known = jedis.commands().get(keyStrategy.versionKey(metadata.redisNamespace(), id) + ":tenant");
        if (!tenant.equals(known)) throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException(metadata.entityName());
        verifiedOwners.put(id, known);
    }

    private void prepareEntityTenantOwner(T entity) {
        if (com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata) && metadata.tenantFieldName() != null) {
            verifyProtectedWriteOwner(metadata.idAccessor().apply(entity), metadata.extractTenantId(entity));
            return;
        }
        if (metadata.tenantFieldName() != null && com.reactor.cachedb.core.tenant.CacheTenantContext.isAdministrative()) {
            try (var tenant = com.reactor.cachedb.core.tenant.CacheTenantContext.withTenant(metadata.extractTenantId(entity))) {
                ensureTenantOwner(metadata.idAccessor().apply(entity), true);
            }
        } else ensureTenantOwner(metadata.idAccessor().apply(entity), true);
    }

    private void claimVerifiedTenantOwner(ID id) {
        String tenant = activeTenant();
        if (tenant == null) return;
        String key = keyStrategy.versionKey(metadata.redisNamespace(), id) + ":tenant";
        jedis.commands().setnx(key, tenant);
        if (!tenant.equals(jedis.commands().get(key)))
            throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException(metadata.entityName());
        verifiedOwners.put(id, tenant);
    }

    private void prepareTenantOwners(List<T> entities) {
        if (metadata.tenantPurgeEnabled()) {
            requireProtectedMutation();
            String tenant = activeTenant();
            for (int start = 0; start < entities.size(); start += 128) {
                var replies = new ArrayList<redis.clients.jedis.Response<String>>();
                int end = Math.min(entities.size(), start + 128);
                try (var pipeline = jedis.pipeline()) {
                    for (int i = start; i < end; i++) replies.add(pipeline.get(
                            keyStrategy.versionKey(metadata.redisNamespace(), metadata.idAccessor().apply(entities.get(i))) + ":tenant"));
                    pipeline.sync();
                    for (var reply : replies) if (!tenant.equals(reply.get()))
                        throw new com.reactor.cachedb.core.protection.ProtectionException(
                                com.reactor.cachedb.core.protection.ProtectionException.Code.MIGRATION_REQUIRED);
                }
            }
            return;
        }
        if (metadata.tenantFieldName() != null && com.reactor.cachedb.core.tenant.CacheTenantContext.isAdministrative()) {
            var groups = new LinkedHashMap<String, List<T>>();
            for (T row : entities) groups.computeIfAbsent(java.util.Objects.requireNonNull(metadata.extractTenantId(row)), ignored -> new ArrayList<>()).add(row);
            for (var group : groups.entrySet()) try (var scope = com.reactor.cachedb.core.tenant.CacheTenantContext.withTenant(group.getKey())) {
                prepareTenantOwners(group.getValue());
            }
            return;
        }
        String tenant = activeTenant();
        if (tenant == null) return;
        if (!functionExecutor.enabled()) throw new IllegalStateException("Tenant writes require atomic Redis Functions");
        var unknown = new ArrayList<ID>();
        for (int start = 0; start < entities.size(); start += 128) {
            var responses = new ArrayList<redis.clients.jedis.Response<String>>();
            int end = Math.min(entities.size(), start + 128);
            try (var pipeline = jedis.pipeline()) {
                for (int i = start; i < end; i++) responses.add(pipeline.get(
                        keyStrategy.versionKey(metadata.redisNamespace(), metadata.idAccessor().apply(entities.get(i))) + ":tenant"));
                pipeline.sync();
                for (int i = start; i < end; i++) {
                    String owner = responses.get(i - start).get();
                    if (owner == null) unknown.add(metadata.idAccessor().apply(entities.get(i)));
                    else if (!tenant.equals(owner)) throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException(metadata.entityName());
                }
            }
        }
        if (!unknown.isEmpty() && com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata))
            throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException(metadata.entityName());
        for (int start = 0; start < unknown.size(); start += 500) {
            var ids = unknown.subList(start, Math.min(unknown.size(), start + 500));
            var existing = loadSourceOwners(ids);
            for (String owner : existing.values()) if (!tenant.equals(owner))
                throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException(metadata.entityName());
        }
        for (int start = 0; start < unknown.size(); start += 128) {
            var confirmations = new ArrayList<redis.clients.jedis.Response<String>>();
            try (var pipeline = jedis.pipeline()) {
                for (ID id : unknown.subList(start, Math.min(unknown.size(), start + 128))) {
                    String key = keyStrategy.versionKey(metadata.redisNamespace(), id) + ":tenant";
                    pipeline.setnx(key, tenant); confirmations.add(pipeline.get(key));
                }
                pipeline.sync();
                for (var response : confirmations) if (!tenant.equals(response.get()))
                    throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException(metadata.entityName());
            }
        }
    }
    private volatile com.reactor.cachedb.core.config.MutationConfig mutationConfig = com.reactor.cachedb.core.config.MutationConfig.defaults();

    public RedisEntityRepository<T, ID> withMutationConfig(com.reactor.cachedb.core.config.MutationConfig config) {
        mutationConfig = java.util.Objects.requireNonNull(config);
        return this;
    }
    private final Map<String, ProjectionSupport<T, ID, ?>> projectionSupportCache =
            new ConcurrentHashMap<>();

    @Override
    public RepositoryCapabilities capabilities() {
        return pointReads != null && functionExecutor != null && functionExecutor.supportsBulkDelete()
                ? CAPABILITIES : LEGACY_CAPABILITIES;
    }

    @Override public com.reactor.cachedb.core.api.EntityQueryCapacity entityQueryCapacity() {
        CachePolicy policy = producerGuard == null ? cachePolicy : producerGuard.cachedEffectiveCachePolicy(cachePolicy);
        return ReadShapeGuardrails.entityQueryCapacity(policy, readShapeGuardrailConfig);
    }

    public RedisEntityRepository(
            JedisPooled jedis,
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            WriteBehindQueue writeBehindQueue,
            RedisKeyStrategy keyStrategy,
            FetchPlan fetchPlan,
            CachePolicy cachePolicy,
            RedisFunctionExecutor functionExecutor,
            RedisProducerGuard producerGuard,
            RedisGuardrailConfig guardrailConfig,
            String streamKey,
            String compactionStreamKey,
            int compactionShardCount,
            EntityRegistry entityRegistry,
            RelationBatchLoader<T> relationBatchLoader,
            RelationConfig relationConfig,
            QueryIndexConfig queryIndexConfig,
            EntityPageLoader<T> pageLoader,
            PageCacheConfig pageCacheConfig,
            ReadShapeGuardrailConfig readShapeGuardrailConfig,
            RedisPageCacheManager<T, ID> pageCacheManager,
            QueryEvaluator queryEvaluator,
            RedisQueryIndexManager<T, ID> queryIndexManager,
            StoragePerformanceCollector performanceCollector,
            JedisPooled backgroundJedis,
            ProjectionRefreshDispatcher projectionRefreshDispatcher,
            RedisProjectionRefreshQueue projectionRefreshQueue) {
        this(
                JedisPooledOperations.of(jedis),
                metadata,
                codec,
                writeBehindQueue,
                keyStrategy,
                fetchPlan,
                cachePolicy,
                functionExecutor,
                producerGuard,
                guardrailConfig,
                streamKey,
                compactionStreamKey,
                compactionShardCount,
                entityRegistry,
                relationBatchLoader,
                relationConfig,
                queryIndexConfig,
                pageLoader,
                pageCacheConfig,
                readShapeGuardrailConfig,
                pageCacheManager,
                queryEvaluator,
                queryIndexManager,
                performanceCollector,
                JedisPooledOperations.of(backgroundJedis),
                projectionRefreshDispatcher,
                projectionRefreshQueue);
    }

    public RedisEntityRepository(
            RedisClientOperations jedis,
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            WriteBehindQueue writeBehindQueue,
            RedisKeyStrategy keyStrategy,
            FetchPlan fetchPlan,
            CachePolicy cachePolicy,
            RedisFunctionExecutor functionExecutor,
            RedisProducerGuard producerGuard,
            RedisGuardrailConfig guardrailConfig,
            String streamKey,
            String compactionStreamKey,
            int compactionShardCount,
            EntityRegistry entityRegistry,
            RelationBatchLoader<T> relationBatchLoader,
            RelationConfig relationConfig,
            QueryIndexConfig queryIndexConfig,
            EntityPageLoader<T> pageLoader,
            PageCacheConfig pageCacheConfig,
            ReadShapeGuardrailConfig readShapeGuardrailConfig,
            RedisPageCacheManager<T, ID> pageCacheManager,
            QueryEvaluator queryEvaluator,
            RedisQueryIndexManager<T, ID> queryIndexManager,
            StoragePerformanceCollector performanceCollector,
            RedisClientOperations backgroundJedis,
            ProjectionRefreshDispatcher projectionRefreshDispatcher,
            RedisProjectionRefreshQueue projectionRefreshQueue) {
        this(
                jedis,
                metadata,
                codec,
                writeBehindQueue,
                keyStrategy,
                fetchPlan,
                cachePolicy,
                functionExecutor,
                producerGuard,
                guardrailConfig,
                streamKey,
                compactionStreamKey,
                compactionShardCount,
                entityRegistry,
                relationBatchLoader,
                relationConfig,
                queryIndexConfig,
                pageLoader,
                new NoOpEntityByIdLoader<>(),
                new NoOpEntityQueryLoader<>(),
                pageCacheConfig,
                ReadThroughConfig.defaults(),
                readShapeGuardrailConfig,
                pageCacheManager,
                queryEvaluator,
                queryIndexManager,
                performanceCollector,
                backgroundJedis,
                projectionRefreshDispatcher,
                projectionRefreshQueue);
    }

    public RedisEntityRepository(
            JedisPooled jedis,
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            WriteBehindQueue writeBehindQueue,
            RedisKeyStrategy keyStrategy,
            FetchPlan fetchPlan,
            CachePolicy cachePolicy,
            RedisFunctionExecutor functionExecutor,
            RedisProducerGuard producerGuard,
            RedisGuardrailConfig guardrailConfig,
            String streamKey,
            String compactionStreamKey,
            int compactionShardCount,
            EntityRegistry entityRegistry,
            RelationBatchLoader<T> relationBatchLoader,
            RelationConfig relationConfig,
            QueryIndexConfig queryIndexConfig,
            EntityPageLoader<T> pageLoader,
            EntityByIdLoader<T, ID> byIdLoader,
            EntityQueryLoader<T> queryLoader,
            PageCacheConfig pageCacheConfig,
            ReadThroughConfig readThroughConfig,
            ReadShapeGuardrailConfig readShapeGuardrailConfig,
            RedisPageCacheManager<T, ID> pageCacheManager,
            QueryEvaluator queryEvaluator,
            RedisQueryIndexManager<T, ID> queryIndexManager,
            StoragePerformanceCollector performanceCollector,
            JedisPooled backgroundJedis,
            ProjectionRefreshDispatcher projectionRefreshDispatcher,
            RedisProjectionRefreshQueue projectionRefreshQueue) {
        this(
                JedisPooledOperations.of(jedis),
                metadata,
                codec,
                writeBehindQueue,
                keyStrategy,
                fetchPlan,
                cachePolicy,
                functionExecutor,
                producerGuard,
                guardrailConfig,
                streamKey,
                compactionStreamKey,
                compactionShardCount,
                entityRegistry,
                relationBatchLoader,
                relationConfig,
                queryIndexConfig,
                pageLoader,
                byIdLoader,
                queryLoader,
                pageCacheConfig,
                readThroughConfig,
                readShapeGuardrailConfig,
                pageCacheManager,
                queryEvaluator,
                queryIndexManager,
                performanceCollector,
                JedisPooledOperations.of(backgroundJedis),
                projectionRefreshDispatcher,
                projectionRefreshQueue);
    }

    public RedisEntityRepository(
            RedisClientOperations jedis,
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            WriteBehindQueue writeBehindQueue,
            RedisKeyStrategy keyStrategy,
            FetchPlan fetchPlan,
            CachePolicy cachePolicy,
            RedisFunctionExecutor functionExecutor,
            RedisProducerGuard producerGuard,
            RedisGuardrailConfig guardrailConfig,
            String streamKey,
            String compactionStreamKey,
            int compactionShardCount,
            EntityRegistry entityRegistry,
            RelationBatchLoader<T> relationBatchLoader,
            RelationConfig relationConfig,
            QueryIndexConfig queryIndexConfig,
            EntityPageLoader<T> pageLoader,
            EntityByIdLoader<T, ID> byIdLoader,
            EntityQueryLoader<T> queryLoader,
            PageCacheConfig pageCacheConfig,
            ReadThroughConfig readThroughConfig,
            ReadShapeGuardrailConfig readShapeGuardrailConfig,
            RedisPageCacheManager<T, ID> pageCacheManager,
            QueryEvaluator queryEvaluator,
            RedisQueryIndexManager<T, ID> queryIndexManager,
            StoragePerformanceCollector performanceCollector,
            RedisClientOperations backgroundJedis,
            ProjectionRefreshDispatcher projectionRefreshDispatcher,
            RedisProjectionRefreshQueue projectionRefreshQueue) {
        this.jedis = jedis;
        this.backgroundJedis = backgroundJedis == null ? jedis : backgroundJedis;
        this.metadata = metadata;
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.requireBound(metadata, codec);
        this.codec = com.reactor.cachedb.core.codec.PayloadEntityCodec.wrap(codec, metadata,
                Math.max(metadata.compressionThresholdBytes(), cachePolicy.compressionThresholdBytes()));
        this.writeBehindQueue = writeBehindQueue;
        this.keyStrategy = keyStrategy;
        this.fetchPlan = fetchPlan;
        this.cachePolicy = !cachePolicy.nearCachePolicy().enabled() && metadata.nearCachePolicy().enabled()
                ? cachePolicy.withNearCachePolicy(metadata.nearCachePolicy()) : cachePolicy;
        this.functionExecutor = functionExecutor;
        this.producerGuard = producerGuard;
        this.guardrailConfig = guardrailConfig;
        this.streamKey = streamKey;
        this.compactionStreamKey = compactionStreamKey;
        this.compactionShardCount = compactionShardCount;
        this.entityRegistry = entityRegistry;
        this.relationBatchLoader = relationBatchLoader;
        this.relationConfig = relationConfig;
        this.queryIndexConfig = queryIndexConfig;
        this.pageLoader = pageLoader == null ? new NoOpEntityPageLoader<>() : pageLoader;
        this.byIdLoader = byIdLoader == null ? new NoOpEntityByIdLoader<>() : byIdLoader;
        this.queryLoader = queryLoader == null ? new NoOpEntityQueryLoader<>() : queryLoader;
        this.pageCacheConfig =
                pageCacheConfig == null ? PageCacheConfig.defaults() : pageCacheConfig;
        this.readThroughConfig =
                readThroughConfig == null ? ReadThroughConfig.defaults() : readThroughConfig;
        this.readFlights = new com.reactor.cachedb.core.cache.SingleFlightLoader<>(this.readThroughConfig.singleFlight());
        this.readShapeGuardrailConfig = readShapeGuardrailConfig;
        this.pageCacheManager = pageCacheManager;
        this.queryEvaluator = queryEvaluator;
        this.queryIndexManager = queryIndexManager;
        this.performanceCollector = performanceCollector;
        this.projectionRefreshDispatcher = projectionRefreshDispatcher;
        this.projectionRefreshQueue = projectionRefreshQueue;
        this.versionedHydrator = new RedisVersionedHydrator(jedis);
        this.durabilityTracker = new RedisDurabilityTracker(this.backgroundJedis, keyStrategy);
    }

    @Override
    public Optional<T> findById(ID id) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return CacheObservations.call(observationRecorder, CacheOperation.FIND_BY_ID, metadata.entityName(),
                    observationOwner, () -> findById(id));
        if (metadata.tenantPurgeEnabled()) return protectedPointReads().findById(activeTenant(), id, requiredVersion(id));
        activeTenant();
        if (pointReads != null) pointReads.ensureOpen();
        long startedAt = System.nanoTime();
        try {
            boolean local = nearCacheActive();
            String localId = local ? String.valueOf(id) : null;
            T cached = nearValue(id);
            if (cached != null) { applyFetchPlan(List.of(cached)); return Optional.of(cached); }
            var stamp = local ? nearCache.capture(localId) : null;
            String entityKey = keyStrategy.entityKey(metadata.redisNamespace(), id);
            String tombstoneKey = keyStrategy.tombstoneKey(metadata.redisNamespace(), id);
            long minimumVersion = requiredVersion(id);
            RedisPointRead read = null;
            List<String> values = null;
            observationTier(Tier.REDIS);
            if (pointReads != null && pointReads.coalesce(minimumVersion)) {
                read = pointReads.runtime().readSync(entityKey, tombstoneKey, local);
            } else {
                values = pointValues(id, minimumVersion > 0);
            }
            if (minimumVersion > 0) verifyCausalVersion(id, values.get(2));
            if ((read == null ? values.get(1) : read.tombstone()) != null) {
                observationOutcome(Outcome.TOMBSTONE);
                return Optional.empty();
            }
            String encoded = read == null ? values.get(0) : read.payload();
            if (encoded == null) {
                if (minimumVersion > 0) throw new IllegalStateException("Causal entity payload is unavailable; SQL fallback cannot certify this version");
                Optional<T> loaded = loadByIdFromSource(id);
                loaded.ifPresent(this::verifyTenant);
                loaded.ifPresent(entity -> applyFetchPlan(List.of(entity)));
                return loaded;
            }
            T entity = codec.fromRedisValue(encoded);
            verifyTenant(entity);
            if (!pageCacheManager.shouldServeCachedEntity(entity)) {
                if (effectiveCachePolicy().hotPolicy().evictWhenRejected()) {
                    pageCacheManager.removeEntity(id);
                }
                return Optional.empty();
            }
            pageCacheManager.recordEntityAccess(id);
            if (local && nearCacheActive()) nearCache.put(localId, encoded, shareableNearEntity(entity, encoded),
                    pointReadTtl(read, entityKey), stamp, values != null && values.size() > 2 && values.get(2) != null ? Long.parseLong(values.get(2)) : 0);
            applyFetchPlan(List.of(entity));
            return Optional.of(entity);
        } finally {
            recordRedisRead(startedAt);
        }
    }

    @Override
    public List<T> findAll(Collection<ID> ids) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return CacheObservations.call(observationRecorder, CacheOperation.FIND_ALL, metadata.entityName(),
                    observationOwner, () -> findAll(ids));
        if (metadata.tenantPurgeEnabled()) {
            if (ids == null) throw RedisTenantReadFence.failure(com.reactor.cachedb.core.protection.ProtectionException.Code.INVALID_REQUEST);
            validateEntityRouteContract("Entity bulk read", ids.size());
            return protectedPointReads().findAll(activeTenant(), ids, this::requiredVersion);
        }
        activeTenant();
        long startedAt = System.nanoTime();
        try {
            if (ids.isEmpty()) {
                return List.of();
            }
            validateEntityRouteContract("Entity bulk read", ids.size());

            var found = new LinkedHashMap<ID, T>();
            for (ID id : ids) if (id != null) found.putIfAbsent(id, null);
            var missing = new ArrayList<ID>();
            for (ID id : found.keySet()) {
                T value = nearValue(id);
                if (value == null) missing.add(id); else found.put(id, value);
            }
            List<ID> accessed = new ArrayList<>(missing.size());
            for (int start = 0; start < missing.size(); start += 512) {
                var batch = missing.subList(start, Math.min(missing.size(), start + 512));
                boolean local = nearCacheActive();
                var stamps = local ? new RedisNearCacheCoordinator.Store.Stamp[batch.size()] : null;
                if (local) for (int i = 0; i < batch.size(); i++)
                    stamps[i] = nearCache.capture(String.valueOf(batch.get(i)));
                long fetched = System.nanoTime();
                boolean causal = batch.stream().anyMatch(id -> requiredVersion(id) > 0);
                var keys = new ArrayList<String>(batch.size() * (causal ? 3 : 2));
                for (ID id : batch) keys.add(keyStrategy.entityKey(metadata.redisNamespace(), id));
                for (ID id : batch) keys.add(keyStrategy.tombstoneKey(metadata.redisNamespace(), id));
                if (causal) {
                    for (ID id : batch) keys.add(keyStrategy.versionKey(metadata.redisNamespace(), id));
                }
                observationTier(Tier.REDIS);
                var values = causal && jedis.clustered() ? RedisCausalReads.read(jedis, keys.subList(0, batch.size()),
                        keys.subList(batch.size(), batch.size() * 2), keys.subList(batch.size() * 2, keys.size()),
                        (index, observed) -> verifyCausalVersion(batch.get(index), observed))
                        : jedis.mget(keys.toArray(String[]::new));
                var ttls = new ArrayList<redis.clients.jedis.Response<Long>>();
                if (local) try (var pipeline = jedis.pipeline()) {
                    for (int i = 0; i < batch.size(); i++) ttls.add(pipeline.pttl(keys.get(i)));
                    pipeline.sync();
                }
                for (int i = 0; i < batch.size(); i++) {
                    ID id = batch.get(i);
                    String version = causal ? values.get(batch.size() * 2 + i) : null;
                    if (causal) verifyCausalVersion(id, version);
                    String encoded = values.get(i);
                    if (encoded == null || values.get(batch.size() + i) != null) continue;
                    T entity = codec.fromRedisValue(encoded); verifyTenant(entity);
                    if (pageCacheManager.shouldServeCachedEntity(entity)) {
                        found.put(id, entity); accessed.add(id);
                        if (local && nearCacheActive()) {
                            long ttl = ttls.get(i).get();
                            if (ttl > 0) ttl = Math.max(0, ttl - (System.nanoTime() - fetched + 999_999L) / 1_000_000L);
                            nearCache.put(String.valueOf(id), encoded, shareableNearEntity(entity, encoded), ttl, stamps[i],
                                    version == null ? 0 : Long.parseLong(version));
                        }
                    } else if (effectiveCachePolicy().hotPolicy().evictWhenRejected()) pageCacheManager.removeEntity(id);
                }
            }
            List<T> entities = new ArrayList<>(ids.size());
            var emitted = new java.util.HashSet<ID>();
            for (ID id : ids) {
                T value = id == null ? null : found.get(id);
                if (value != null) entities.add(!emitted.add(id) && !metadata.deeplyImmutable()
                        ? codec.fromRedisValue(codec.toRedisValue(value)) : value);
            }
            pageCacheManager.recordEntityAccessBatch(accessed);
            applyFetchPlan(entities);
            return entities;
        } finally {
            recordRedisRead(startedAt);
        }
    }

    @Override
    public List<T> findPage(PageWindow pageWindow) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return CacheObservations.call(observationRecorder, CacheOperation.FIND_PAGE, metadata.entityName(),
                    observationOwner, () -> findPage(pageWindow));
        requireUnversionedReadAllowed();
        if (activeTenant() != null) return query(QuerySpec.where(QueryFilter.eq(metadata.tenantFieldName(), activeTenant()))
                .offsetBy(pageWindow.pageNumber() * pageWindow.pageSize()).limitTo(pageWindow.pageSize()));
        long startedAt = System.nanoTime();
        try {
            rejectProjectionOnlyEntityRoute("Entity page read");
            if (pageWindow != null) {
                validateEntityRouteContract("Entity page read", pageWindow.pageSize());
            }
            ReadShapeGuardrails.validatePageRequest(
                    metadata.entityName(),
                    pageWindow,
                    effectiveCachePolicy(),
                    readShapeGuardrailConfig);
            observationTier(Tier.REDIS);
            Optional<List<T>> cachedPage = pageCacheManager.getCachedPage(pageWindow);
            if (cachedPage.isPresent()) {
                List<T> entities = cachedPage.get();
                applyFetchPlan(entities);
                return entities;
            }

            List<T> loaded = loadPageCoalesced(pageWindow);
            applyFetchPlan(loaded);
            return loaded;
        } finally {
            recordRedisRead(startedAt);
        }
    }

    private List<T> loadPageCoalesced(PageWindow pageWindow) {
        if (!readThroughConfig.singleFlight().enabled()) return loadPageFromSource(pageWindow);
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) readFlights.load(
                new ReadFlightKey("page", pageWindow, RouteCacheContext.currentContract()),
                () -> encodeFlightRows(pageCacheManager.getCachedPage(pageWindow)
                        .orElseGet(() -> loadPageFromSource(pageWindow))));
        return decodeFlightRows(values);
    }

    private List<T> loadPageFromSource(PageWindow pageWindow) {
            EntityPageLoader<T> effectivePageLoader = currentPageLoader();
            if (shouldFailOnMissingPageLoader()
                    && effectivePageLoader instanceof NoOpEntityPageLoader) {
                throw new IllegalStateException(
                        "Page cache miss for "
                                + metadata.entityName()
                                + " but no EntityPageLoader is registered");
            }
            if (!pageCacheConfig.readThroughEnabled()) {
                return List.of();
            }
            if (!(effectivePageLoader instanceof NoOpEntityPageLoader)) observationTier(Tier.SOURCE);
            VersionedEntitySourceLoader<T, ID> versionedLoader =
                    versionedSourceLoader(effectivePageLoader);
            if (producerGuard != null
                    && producerGuard.shouldShedReadThroughCache(metadata.redisNamespace())) {
                List<T> loaded =
                        versionedLoader == null
                                ? effectivePageLoader.load(pageWindow)
                                : entities(versionedLoader.loadVersionedPage(pageWindow));
                ReadShapeGuardrails.validateLoadedPage(
                        metadata.entityName(),
                        loaded.size(),
                        effectiveCachePolicy(),
                        readShapeGuardrailConfig);
                return loaded;
            }

            List<VersionedEntity<T>> versioned =
                    versionedLoader == null
                            ? List.of()
                            : versionedLoader.loadVersionedPage(pageWindow);
            List<T> loaded =
                    versionedLoader == null
                            ? effectivePageLoader.load(pageWindow)
                            : entities(versioned);
            ReadShapeGuardrails.validateLoadedPage(
                    metadata.entityName(),
                    loaded.size(),
                    effectiveCachePolicy(),
                    readShapeGuardrailConfig);
            if (versionedLoader == null) {
                recordReadThroughEvent("page-version-unavailable", false);
            } else if (ReadShapeGuardrails.shouldCacheLoadedPage(
                    loaded.size(), effectiveCachePolicy(), readShapeGuardrailConfig)) {
                List<T> hydrated =
                        hydrateWarmBatchInternal(loaded, versions(versioned), true, true);
                if (hydrated.size() == loaded.size()) {
                    pageCacheManager.cachePageMembership(pageWindow, loaded);
                }
            }
            return loaded;
    }

    @Override
    public QueryExplainPlan explain(QuerySpec querySpec) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return CacheObservations.call(observationRecorder, CacheOperation.EXPLAIN, metadata.entityName(),
                    observationOwner, () -> explain(querySpec));
        rejectUncertifiedProtectedRead();
        return queryIndexManager.explain(tenantQuery(querySpec));
    }

    @Override
    public Object aggregateFieldValue(T entity, String column) {
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.rejectProtectedRole(metadata, column);
        return codec.entityColumnValue(entity, column);
    }

    private List<String> lazyIndexMetadataKeys(List<String> ids) {
        // COUNT(*) needs alignment only; custom transports can still resolve the actual keys.
        return Collections.unmodifiableList(new java.util.AbstractList<String>() {
            @Override public String get(int index) {
                return keyStrategy.indexMetaKey(metadata.redisNamespace(), ids.get(index));
            }
            @Override public int size() { return ids.size(); }
        });
    }

    @Override
    public com.reactor.cachedb.core.query.AggregateResult aggregate(com.reactor.cachedb.core.query.AggregateSpec spec) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return CacheObservations.call(observationRecorder, CacheOperation.AGGREGATE, metadata.entityName(),
                    observationOwner, () -> aggregate(spec));
        requireUnversionedReadAllowed();
        java.util.Objects.requireNonNull(spec, "spec");
        rejectProjectionOnlyEntityRoute("Entity aggregate");
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.rejectProtectedRole(metadata, spec.fieldName());
        boolean count = spec.function() == com.reactor.cachedb.core.query.AggregateFunction.COUNT;
        boolean countRows = count && (spec.fieldName() == null || spec.fieldName().isBlank() || spec.fieldName().equals("*"));
        if (!countRows && !metadata.columns().contains(spec.fieldName())) throw new IllegalArgumentException("Unknown aggregate column");
        var query = tenantQuery(QuerySpec.where(spec.filter()));
        observationTier(Tier.REDIS);
        var indexed = queryIndexManager.indexOnlyCandidates(query);
        var accumulator = new com.reactor.cachedb.core.query.AggregateResult.Accumulator();
        boolean indexValues = indexed.isPresent() && (countRows || queryIndexManager.hasIndexedColumn(spec.fieldName())) && !ambiguousNullFilter(query);
        boolean countOnlyPolicy = effectiveCachePolicy().hotPolicy().mode()
                == com.reactor.cachedb.core.cache.EntityHotPolicyMode.COUNT_WINDOW;
        String tenant = activeTenant();
        List<String> ids = indexed.orElseGet(() -> queryIndexManager.resolveCandidateIds(query));
        if (countRows && indexValues && countOnlyPolicy && ids.size() > 512
                && (tenant != null || jedis.supportsUnownedIndexCounts())) {
            var pipelined = RedisBinaryCountKeys.supports(jedis, keyStrategy)
                    ? ((JedisPooledOperations) jedis).binaryIndexCounts(
                            new RedisBinaryCountKeys(keyStrategy, metadata.redisNamespace(), ids, tenant), tenant)
                    : jedis.aggregateIndexCounts(ids.size(), start -> {
                var batch = ids.subList(start, Math.min(ids.size(), start + 512));
                return new RedisClientOperations.IndexReadKeys(
                        batch.stream().map(id -> keyStrategy.entityKey(metadata.redisNamespace(), id)).toList(),
                        batch.stream().map(id -> keyStrategy.tombstoneKey(metadata.redisNamespace(), id)).toList(),
                        lazyIndexMetadataKeys(batch),
                        tenant == null ? null : batch.stream()
                                .map(id -> keyStrategy.versionKey(metadata.redisNamespace(), id) + ":tenant").toList());
            }, tenant);
            if (pipelined.isPresent()) {
                accumulator.addCount(pipelined.getAsLong());
                return accumulator.result();
            }
        }
        for (int start = 0; start < ids.size(); start += 512) {
            var batch = ids.subList(start, Math.min(ids.size(), start + 512));
            if (indexValues && countOnlyPolicy) {
                var entities = batch.stream().map(id -> keyStrategy.entityKey(metadata.redisNamespace(), id)).toList();
                var tombstones = batch.stream().map(id -> keyStrategy.tombstoneKey(metadata.redisNamespace(), id)).toList();
                var indexMetadata = countRows ? lazyIndexMetadataKeys(batch)
                        : batch.stream().map(id -> keyStrategy.indexMetaKey(metadata.redisNamespace(), id)).toList();
                var aggregated = tenant == null
                        ? jedis.aggregateIndexBatch(entities, tombstones, indexMetadata, countRows ? null : spec.fieldName(), count)
                        : jedis.aggregateIndexBatch(entities, tombstones, indexMetadata,
                                batch.stream().map(id -> keyStrategy.versionKey(metadata.redisNamespace(), id) + ":tenant").toList(),
                                tenant, countRows ? null : spec.fieldName(), count);
                if (aggregated.isPresent()) {
                    if (count) accumulator.addCount(aggregated.get().count());
                    else for (String value : aggregated.get().values()) accumulator.add(indexNumber(value));
                    // SUM/MIN/MAX/AVG still report all live rows, including null numeric fields.
                    if (!count && aggregated.get().count() > aggregated.get().values().size())
                        accumulator.addCount(aggregated.get().count() - aggregated.get().values().size());
                    if (count && !aggregated.get().nullCandidates().isEmpty()) {
                        var ambiguous = aggregated.get().nullCandidates().stream().map(batch::get).toList();
                        for (String payload : aggregateLivePayloads(ambiguous, tenant)) {
                            if (payload == null) continue;
                            T row = codec.fromRedisValue(payload);
                            if (!pageCacheManager.shouldServeCachedEntity(row)
                                    || !queryEvaluator.matches(row, codec, query.rootGroup())) continue;
                            verifyTenant(row);
                            if (codec.entityColumnValue(row, spec.fieldName()) != null) accumulator.addCount(1);
                        }
                    }
                    continue;
                }
            }
            // A tenant fallback must inspect the payload, not trust injected index membership.
            boolean pipelineIndexValues = indexValues && tenant == null;
            var live = new ArrayList<redis.clients.jedis.Response<Boolean>>(batch.size());
            var deleted = new ArrayList<redis.clients.jedis.Response<Boolean>>(batch.size());
            var fields = new ArrayList<redis.clients.jedis.Response<String>>(batch.size());
            try (var pipeline = jedis.pipeline()) {
                for (String id : batch) {
                    live.add(pipeline.exists(keyStrategy.entityKey(metadata.redisNamespace(), id)));
                    deleted.add(pipeline.exists(keyStrategy.tombstoneKey(metadata.redisNamespace(), id)));
                    fields.add(countRows || !pipelineIndexValues ? null : pipeline.hget(keyStrategy.indexMetaKey(metadata.redisNamespace(), id), spec.fieldName()));
                }
                pipeline.sync();
            }
            // Release the pipeline lease before the guarded payload read borrows a connection.
            List<String> payloads = !countOnlyPolicy || !pipelineIndexValues || count && !countRows
                    ? jedis.boundedMget(batch.stream().map(id -> keyStrategy.entityKey(metadata.redisNamespace(), id)).toList(), 16L * 1024 * 1024) : null;
            for (int i = 0; i < batch.size(); i++) {
                if (!Boolean.TRUE.equals(live.get(i).get()) || Boolean.TRUE.equals(deleted.get(i).get())) continue;
                T entity = payloads == null || payloads.get(i) == null ? null : codec.fromRedisValue(payloads.get(i));
                if (payloads != null && (entity == null || !pageCacheManager.shouldServeCachedEntity(entity))) continue;
                if (entity != null && !queryEvaluator.matches(entity, codec, query.rootGroup())) continue;
                if (entity != null) verifyTenant(entity);
                Object value = null;
                if (!countRows) {
                    if (count && entity != null) value = codec.entityColumnValue(entity, spec.fieldName());
                    else if (pipelineIndexValues) {
                        String raw = fields.get(i).get();
                        if (raw == null) throw new IllegalStateException("Aggregate index metadata is incomplete; rebuild indexes");
                        if (!raw.equals("__NULL__")) value = count ? raw : indexNumber(raw);
                    } else value = codec.entityColumnValue(entity, spec.fieldName());
                }
                if (count && !countRows && value == null) continue;
                accumulator.add(count ? null : value);
            }
        }
        return accumulator.result();
    }

    @Override public Map<String, com.reactor.cachedb.core.query.AggregateResult> aggregateBy(String group,
            com.reactor.cachedb.core.query.AggregateSpec spec) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return CacheObservations.call(observationRecorder, CacheOperation.AGGREGATE_BY, metadata.entityName(),
                    observationOwner, () -> aggregateBy(group, spec));
        try {
            return aggregateByScoped(group, spec);
        } catch (com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException denied) {
            throw new IllegalStateException("Tenant facet access denied", denied);
        }
    }

    private Map<String, com.reactor.cachedb.core.query.AggregateResult> aggregateByScoped(String group,
            com.reactor.cachedb.core.query.AggregateSpec spec) {
        requireUnversionedReadAllowed();
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.rejectProtectedRole(metadata, group);
        if (spec != null) com.reactor.cachedb.core.crypto.EncryptedStorageChecks.rejectProtectedRole(metadata, spec.fieldName());
        if (group == null || group.isBlank() || !metadata.indexedColumns().contains(group))
            throw new IllegalArgumentException("Facet group must be an indexed column");
        java.util.Objects.requireNonNull(spec);
        boolean count = spec.function() == com.reactor.cachedb.core.query.AggregateFunction.COUNT;
        boolean field = spec.fieldName() != null && !spec.fieldName().isBlank() && !spec.fieldName().equals("*");
        if ((!count || field) && !metadata.columns().contains(spec.fieldName())) throw new IllegalArgumentException("Unknown aggregate column");
        QuerySpec query = tenantQuery(QuerySpec.where(spec.filter()));
        observationTier(Tier.REDIS);
        var indexed = queryIndexManager.indexOnlyCandidates(query);
        String tenant = activeTenant();
        boolean indexValues = indexed.isPresent() && queryIndexManager.hasIndexedColumn(group)
                && (!field || queryIndexManager.hasIndexedColumn(spec.fieldName()))
                && !ambiguousNullFilter(query)
                && effectiveCachePolicy().hotPolicy().mode() == com.reactor.cachedb.core.cache.EntityHotPolicyMode.COUNT_WINDOW;
        List<String> ids = indexed.orElseGet(() -> queryIndexManager.resolveCandidateIds(query));
        if (ids.size() > 100_000) throw new IllegalStateException("Facet candidate limit exceeded: max 100000");
        var result = new com.reactor.cachedb.core.query.FacetAccumulator();
        long bytes = 0;
        int batchSize = indexValues && field ? 256 : 512;
        var binaryKeys = indexValues && ids.size() > batchSize && RedisBinaryFacetKeys.supports(jedis, keyStrategy)
                ? new RedisBinaryFacetKeys(keyStrategy, metadata.redisNamespace(), ids, tenant) : null;
        java.util.function.IntFunction<RedisClientOperations.IndexReadKeys> windows = start -> {
            var batch = ids.subList(start, Math.min(ids.size(), start + batchSize));
            return new RedisClientOperations.IndexReadKeys(
                    batch.stream().map(id -> keyStrategy.entityKey(metadata.redisNamespace(), id)).toList(),
                    batch.stream().map(id -> keyStrategy.tombstoneKey(metadata.redisNamespace(), id)).toList(),
                    batch.stream().map(id -> keyStrategy.indexMetaKey(metadata.redisNamespace(), id)).toList(),
                    tenant == null ? null : batch.stream()
                            .map(id -> keyStrategy.versionKey(metadata.redisNamespace(), id) + ":tenant").toList());
        };
        var compactPhase = indexValues && count && !field && ids.size() > batchSize
                ? binaryKeys == null ? jedis.facetIndexCountsPhase(ids.size(), windows, tenant, group)
                        : ((JedisPooledOperations) jedis).binaryFacetIndexCountsPhase(binaryKeys, tenant, group)
                : RedisClientOperations.FacetPhaseResult.<RedisClientOperations.FacetIndexCounts>declined(
                        RedisClientOperations.FacetPhaseDecline.UNSUPPORTED);
        var compacted = compactPhase.batches();
        Optional<List<RedisClientOperations.FacetIndexBatch>> pipelined = compacted.isEmpty() && !compactPhase.skipRowsPhase()
                && indexValues && ids.size() > batchSize
                ? binaryKeys == null ? jedis.facetIndexBatches(ids.size(), windows, tenant, group, field ? spec.fieldName() : null)
                        : ((JedisPooledOperations) jedis).binaryFacetIndexBatches(binaryKeys, tenant, group, field ? spec.fieldName() : null)
                : Optional.empty();
        for (int start = 0; start < ids.size(); start += batchSize) {
            var batch = ids.subList(start, Math.min(ids.size(), start + batchSize));
            if (compacted.isPresent()) {
                var summary = compacted.get().get(start / batchSize);
                for (var entry : summary.counts().entrySet()) {
                    bytes += entry.getValue() * (2L * entry.getKey().length() + 16);
                    if (bytes > 16L * 1024 * 1024) throw new IllegalStateException("Facet byte budget exceeded");
                    result.addCount(entry.getKey(), entry.getValue());
                }
                if (!summary.nullCandidates().isEmpty()) {
                    var ambiguous = summary.nullCandidates().stream().map(batch::get).toList();
                    for (String payload : aggregateLivePayloads(ambiguous, tenant)) {
                        if (payload == null) continue;
                        bytes += 2L * payload.length();
                        if (bytes > 16L * 1024 * 1024) throw new IllegalStateException("Facet byte budget exceeded");
                        T entity = codec.fromRedisValue(payload);
                        if (!pageCacheManager.shouldServeCachedEntity(entity)
                                || !queryEvaluator.matches(entity, codec, query.rootGroup())) continue;
                        verifyTenant(entity);
                        result.add(codec.entityColumnValue(entity, group), null, false);
                    }
                }
                continue;
            }
            if (indexValues) {
                var faceted = pipelined.isPresent() ? Optional.of(pipelined.get().get(start / batchSize)) : jedis.facetIndexBatch(
                        batch.stream().map(id -> keyStrategy.entityKey(metadata.redisNamespace(), id)).toList(),
                        batch.stream().map(id -> keyStrategy.tombstoneKey(metadata.redisNamespace(), id)).toList(),
                        batch.stream().map(id -> keyStrategy.indexMetaKey(metadata.redisNamespace(), id)).toList(),
                        tenant == null ? null : batch.stream()
                                .map(id -> keyStrategy.versionKey(metadata.redisNamespace(), id) + ":tenant").toList(),
                        tenant, group, field ? spec.fieldName() : null);
                if (faceted.isPresent()) {
                    var ambiguous = new ArrayList<String>();
                    for (var row : faceted.get().rows())
                        if ("__NULL__".equals(row.groupRaw()) || count && field && "__NULL__".equals(row.valueRaw()))
                            ambiguous.add(batch.get(row.candidate()));
                    var payloads = ambiguous.isEmpty() ? List.<String>of() : aggregateLivePayloads(ambiguous, tenant);
                    int payloadIndex = 0;
                    for (var row : faceted.get().rows()) {
                        Object groupValue, value;
                        if ("__NULL__".equals(row.groupRaw()) || count && field && "__NULL__".equals(row.valueRaw())) {
                            String payload = payloads.get(payloadIndex++);
                            if (payload == null) continue;
                            bytes += 2L * payload.length();
                            if (bytes > 16L * 1024 * 1024) throw new IllegalStateException("Facet byte budget exceeded");
                            T entity = codec.fromRedisValue(payload);
                            if (!pageCacheManager.shouldServeCachedEntity(entity)
                                    || !queryEvaluator.matches(entity, codec, query.rootGroup())) continue;
                            verifyTenant(entity);
                            groupValue = codec.entityColumnValue(entity, group);
                            value = field ? codec.entityColumnValue(entity, spec.fieldName()) : null;
                        } else {
                            bytes += 2L * row.groupRaw().length() + (field ? 2L * row.valueRaw().length() : 16);
                            groupValue = row.groupRaw();
                            value = !field || "__NULL__".equals(row.valueRaw()) ? null
                                    : count ? row.valueRaw() : indexNumber(row.valueRaw());
                        }
                        if (bytes > 16L * 1024 * 1024) throw new IllegalStateException("Facet byte budget exceeded");
                        result.add(groupValue, value, count && field);
                    }
                    continue;
                }
            }
            boolean pipelineIndexValues = indexValues && tenant == null;
            try (var pipeline = jedis.pipeline()) {
                var live = new ArrayList<redis.clients.jedis.Response<Boolean>>();
                var deleted = new ArrayList<redis.clients.jedis.Response<Boolean>>();
                var groups = new ArrayList<redis.clients.jedis.Response<String>>();
                var values = new ArrayList<redis.clients.jedis.Response<String>>();
                for (String id : batch) {
                    live.add(pipeline.exists(keyStrategy.entityKey(metadata.redisNamespace(), id)));
                    deleted.add(pipeline.exists(keyStrategy.tombstoneKey(metadata.redisNamespace(), id)));
                    if (pipelineIndexValues) {
                        String key = keyStrategy.indexMetaKey(metadata.redisNamespace(), id);
                        groups.add(pipeline.hget(key, group));
                        if (field) values.add(pipeline.hget(key, spec.fieldName()));
                    }
                }
                pipeline.sync();
                List<String> boundedPayloads = !pipelineIndexValues ? jedis.boundedMget(batch.stream()
                        .map(id -> keyStrategy.entityKey(metadata.redisNamespace(), id)).toList(), 16L * 1024 * 1024) : List.of();
                var ambiguous = new LinkedHashMap<Integer, String>();
                if (pipelineIndexValues) {
                    var positions = new ArrayList<Integer>();
                    for (int i = 0; i < batch.size(); i++) if ("__NULL__".equals(groups.get(i).get())
                            || count && field && "__NULL__".equals(values.get(i).get())) positions.add(i);
                    if (!positions.isEmpty()) {
                        var raw = jedis.boundedMget(positions.stream().map(i -> keyStrategy.entityKey(metadata.redisNamespace(), batch.get(i))).toList(), 16L * 1024 * 1024);
                        for (int i = 0; i < positions.size(); i++) ambiguous.put(positions.get(i), raw.get(i));
                    }
                }
                for (int i = 0; i < batch.size(); i++) {
                    if (!Boolean.TRUE.equals(live.get(i).get()) || Boolean.TRUE.equals(deleted.get(i).get())) continue;
                    Object groupValue, value;
                    if (pipelineIndexValues && !ambiguous.containsKey(i)) {
                        String raw = groups.get(i).get(), numeric = field ? values.get(i).get() : "__NULL__";
                        if (raw == null || numeric == null) throw new IllegalStateException("Facet index metadata is incomplete; rebuild indexes");
                        bytes += 2L * (raw.length() + numeric.length());
                        groupValue = raw.equals("__NULL__") ? null : raw;
                        value = numeric.equals("__NULL__") ? null : count ? numeric : indexNumber(numeric);
                    } else {
                        String raw = pipelineIndexValues ? ambiguous.get(i) : boundedPayloads.get(i);
                        if (raw == null) continue;
                        bytes += 2L * raw.length();
                        if (bytes > 16L * 1024 * 1024) throw new IllegalStateException("Facet byte budget exceeded");
                        T row = codec.fromRedisValue(raw);
                        if (!pageCacheManager.shouldServeCachedEntity(row) || !queryEvaluator.matches(row, codec, query.rootGroup())) continue;
                        verifyTenant(row);
                        groupValue = codec.entityColumnValue(row, group);
                        value = field ? codec.entityColumnValue(row, spec.fieldName()) : null;
                    }
                    if (bytes > 16L * 1024 * 1024) throw new IllegalStateException("Facet byte budget exceeded");
                    result.add(groupValue, value, count && field);
                }
            }
        }
        return result.result();
    }

    private List<String> aggregateLivePayloads(List<String> ids, String tenant) {
        return jedis.liveIndexPayloadBatch(
                ids.stream().map(id -> keyStrategy.entityKey(metadata.redisNamespace(), id)).toList(),
                ids.stream().map(id -> keyStrategy.tombstoneKey(metadata.redisNamespace(), id)).toList(),
                tenant == null ? null : ids.stream()
                        .map(id -> keyStrategy.versionKey(metadata.redisNamespace(), id) + ":tenant").toList(),
                tenant).orElseThrow(() -> new IllegalStateException("Aggregate sentinel re-read requires a common Redis slot"));
    }

    private static java.math.BigDecimal indexNumber(String value) {
        if (value.length() > 4096) throw new IllegalArgumentException("Aggregate numeric text exceeds 4096 characters");
        return com.reactor.cachedb.core.model.NumericValues.boundedDecimal(new java.math.BigDecimal(value));
    }
    private static boolean ambiguousNullFilter(QuerySpec query) {
        return query.filters().stream().anyMatch(filter -> "__NULL__".equals(filter.value()) || filter.values().contains("__NULL__"));
    }

    @Override
    public List<T> query(QuerySpec requestedQuery) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return CacheObservations.call(observationRecorder, CacheOperation.QUERY, metadata.entityName(),
                    observationOwner, () -> query(requestedQuery));
        requireUnversionedReadAllowed();
        final QuerySpec querySpec = tenantQuery(requestedQuery);
        long startedAt = System.nanoTime();
        try {
            rejectProjectionOnlyEntityRoute("Entity query");
            if (querySpec != null) {
                validateEntityRouteContract("Entity query", querySpec.limit());
            }
            ReadShapeGuardrails.validateEntityQuery(
                    metadata.entityName(),
                    querySpec,
                    effectiveCachePolicy(),
                    readShapeGuardrailConfig);
            observationTier(Tier.REDIS);
            if (queryIndexManager.hasCompositeSortIndexes() && !readThroughConfig.mode().queryEnabled()) {
                var rejected = new java.util.LinkedHashSet<ID>();
                java.util.Optional<RedisQueryIndexManager.CompositeWindow<T>> compositeWindow;
                try {
                    compositeWindow = queryIndexManager.resolveCompositeQueryWindow(querySpec, entity -> {
                        verifyTenant(entity);
                        if (pageCacheManager.shouldServeCachedEntity(entity)) return true;
                        rejected.add(metadata.idAccessor().apply(entity)); return false;
                    });
                } catch (RedisQueryIndexManager.CompositePayloadDivergenceException divergence) {
                    compositeWindow = java.util.Optional.empty();
                }
                compositeReadDeadline(querySpec, startedAt);
                if (compositeWindow.isPresent()) {
                    if (effectiveCachePolicy().hotPolicy().evictWhenRejected())
                        for (ID id : rejected) pageCacheManager.removeEntity(id);
                    var rows = compositeWindow.get().rows().stream().map(RedisQueryIndexManager.CompositeRow::entity).toList();
                    pageCacheManager.recordEntityAccessBatch(rows.stream().map(metadata.idAccessor()).toList());
                    queryIndexManager.observe(querySpec, rows.size());
                    applyFetchPlan(rows, querySpec.fetchPlan());
                    compositeReadDeadline(querySpec, startedAt);
                    return rows;
                }
            }
            var compositeCandidates = queryIndexManager.resolveCompositeRangeCandidates(querySpec);
            if (compositeCandidates.isEmpty() && effectiveCachePolicy().hotPolicy().mode() == com.reactor.cachedb.core.cache.EntityHotPolicyMode.COUNT_WINDOW
                    && !readThroughConfig.mode().queryEnabled()) {
                boolean local = nearCacheActive();
                long epoch = local ? nearCache.epoch() : 0;
                long fetchStarted = System.nanoTime();
                var ordered = queryIndexManager.resolveOrderedPayloadWindow(querySpec);
                if (ordered.isPresent()) {
                    var entities = new ArrayList<T>(ordered.get().size());
                    for (var value : ordered.get()) {
                        String payload = value.payload();
                        T entity = codec.fromRedisValue(payload); verifyTenant(entity); entities.add(entity);
                        if (local && nearCacheActive()) {
                            long ttl = value.ttlMillis();
                            if (ttl > 0) ttl = Math.max(0, ttl - (System.nanoTime() - fetchStarted + 999_999L) / 1_000_000L);
                            nearCache.put(String.valueOf(metadata.idAccessor().apply(entity)), payload,
                                    shareableNearEntity(entity, payload), ttl, epoch);
                        }
                    }
                    pageCacheManager.recordEntityAccessBatch(entities.stream().map(metadata.idAccessor()).toList());
                    applyFetchPlan(entities, querySpec.fetchPlan());
                    return List.copyOf(entities);
                }
            }
            if (compositeCandidates.isEmpty()) queryIndexManager.warm(querySpec);
            boolean sortedIndexScan = compositeCandidates.isEmpty() && queryIndexManager.supportsSortedIndexScan(querySpec);
            boolean primarySortedScan =
                    compositeCandidates.isEmpty() && !sortedIndexScan && queryIndexManager.shouldUsePrimarySortedScan(querySpec);
            List<String> candidateIds = compositeCandidates.isPresent() ? compositeCandidates.get() :
                    sortedIndexScan
                            ? queryIndexManager.resolveSortedIds(querySpec)
                            : (primarySortedScan
                                    ? queryIndexManager.resolvePrimarySortedIds(querySpec)
                                    : queryIndexManager.resolveCandidateIds(querySpec));
            boolean completeSortOrderResolved =
                    primarySortedScan
                            && queryIndexManager.resolvesCompleteSortOrder(
                                    querySpec, candidateIds.size());
            boolean requiresResidualEvaluation =
                    compositeCandidates.isPresent() || !queryIndexManager.fullyIndexed(querySpec.rootGroup());
            boolean requiresInMemorySort =
                    !querySpec.sorts().isEmpty() && !sortedIndexScan && !completeSortOrderResolved;
            if (candidateIds.isEmpty()) {
                List<T> loaded = loadQueryFromSource(querySpec);
                queryIndexManager.observe(querySpec, loaded.size());
                if (!loaded.isEmpty()) {
                    applyFetchPlan(loaded, querySpec.fetchPlan());
                }
                return loaded;
            }

            List<String> payloadIds =
                    windowResolvedCandidateIds(
                            querySpec,
                            candidateIds,
                            sortedIndexScan,
                            completeSortOrderResolved,
                            requiresResidualEvaluation);

            List<String> keys =
                    payloadIds.stream()
                            .map(id -> keyStrategy.entityKey(metadata.redisNamespace(), id))
                            .toList();
            List<String> tombstoneKeys =
                    payloadIds.stream()
                            .map(id -> keyStrategy.tombstoneKey(metadata.redisNamespace(), id))
                            .toList();
            var compositePayloads = compositeCandidates.isPresent() ? compositeCandidatePayloads(payloadIds, querySpec, startedAt) : null;
            List<String> values = compositePayloads == null ? mgetEntityAndTombstones(keys, tombstoneKeys) : compositePayloads.values();
            List<T> entities = new ArrayList<>();
            List<String> staleIndexIds = new ArrayList<>();
            Map<T, Map<String, Object>> resolvedColumns =
                    new IdentityHashMap<>(Math.max(16, keys.size()));
            for (int index = 0; index < keys.size(); index++) {
                if (compositePayloads != null && compositePayloads.denied().contains(payloadIds.get(index))) continue;
                String payload = values.get(index);
                if (payload == null || values.get(keys.size() + index) != null) {
                    staleIndexIds.add(payloadIds.get(index));
                    continue;
                }
                T entity = codec.fromRedisValue(payload);
                verifyTenant(entity);
                if (!pageCacheManager.shouldServeCachedEntity(entity)) {
                    if (effectiveCachePolicy().hotPolicy().evictWhenRejected()) {
                        pageCacheManager.removeEntity((ID) payloadIds.get(index));
                    }
                    continue;
                }
                boolean matches = true;
                if (requiresResidualEvaluation) {
                    if (codec.directColumnAccess()) {
                        matches = queryEvaluator.matches(entity, codec, querySpec.rootGroup());
                    } else {
                        Map<String, Object> columns = codec.toColumns(entity);
                        resolvedColumns.put(entity, columns);
                        matches = queryEvaluator.matches(columns, querySpec.rootGroup());
                    }
                }
                if (matches) {
                    entities.add(entity);
                }
            }

            if (!staleIndexIds.isEmpty()) {
                for (String staleIndexId : staleIndexIds) {
                    queryIndexManager.removeById(staleIndexId);
                }
                if (readThroughConfig.mode().queryEnabled()) {
                    List<T> loaded = loadQueryFromSource(querySpec);
                    queryIndexManager.observe(querySpec, loaded.size());
                    applyFetchPlan(loaded, querySpec.fetchPlan());
                    return loaded;
                }
                throw new IllegalStateException(
                        "Redis query index/payload divergence detected for "
                                + metadata.entityName()
                                + "; removed "
                                + staleIndexIds.size()
                                + " stale index entries, but query read-through is disabled");
            }

            queryIndexManager.observe(querySpec, entities.size());

            if (requiresInMemorySort) {
                if (codec.directColumnAccess()) {
                    entities.sort((left, right) -> compareEntitiesBySorts(left, right, querySpec.sorts()));
                } else {
                Map<T, Map<String, Object>> sortColumns =
                        new IdentityHashMap<>(Math.max(entities.size(), resolvedColumns.size()));
                sortColumns.putAll(resolvedColumns);
                for (T entity : entities) {
                    sortColumns.computeIfAbsent(entity, codec::toColumns);
                }
                entities.sort(
                        (left, right) ->
                                compareBySorts(
                                        sortColumns.get(left),
                                        sortColumns.get(right),
                                        querySpec.sorts()));
                }
            }

            List<T> sliced;
            if (sortedIndexScan) {
                sliced = new ArrayList<>(entities);
            } else {
                int fromIndex = Math.min(querySpec.offset(), entities.size());
                int toIndex = Math.min(fromIndex + querySpec.limit(), entities.size());
                sliced = new ArrayList<>(entities.subList(fromIndex, toIndex));
            }
            if (compositeCandidates.isPresent())
                pageCacheManager.recordEntityAccessBatch(sliced.stream().map(metadata.idAccessor()).toList());
            applyFetchPlan(sliced, querySpec.fetchPlan());
            return sliced;
        } finally {
            recordRedisRead(startedAt);
        }
    }

    private static void compositeReadDeadline(QuerySpec query, long startedAt) {
        if (query.queryTimeoutSeconds() > 0 && System.nanoTime() - startedAt
                >= java.util.concurrent.TimeUnit.SECONDS.toNanos(query.queryTimeoutSeconds()))
            throw new IllegalStateException("Composite query deadline exceeded");
    }

    private record CompositeCandidatePayloads(List<String> values, java.util.Set<String> denied) {}

    @SuppressWarnings("unchecked")
    private CompositeCandidatePayloads compositeCandidatePayloads(List<String> ids, QuerySpec query, long startedAt) {
        if (com.reactor.cachedb.core.causal.CacheCausalContext.hasRequirements(metadata.redisNamespace()))
            throw new IllegalStateException("Sorted composite materialization cannot certify minimum versions; use point/findAll");
        if (ids.size() > queryIndexManager.compositeMaterializationLimit())
            throw new IllegalStateException("Composite candidate limit exceeded");
        var values = new ArrayList<String>(Collections.nCopies(ids.size() * 2, null));
        var denied = new java.util.HashSet<String>();
        String tenant = activeTenant();
        long bytes = 0;
        for (String id : ids) {
            RedisCompositeIndexes.validateMember(id);
            bytes += id.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        for (int base = 0; base < ids.size(); base += 128) {
            compositeReadDeadline(query, startedAt);
            int end = Math.min(base + 128, ids.size());
            var keys = new ArrayList<String>((end - base) * (tenant == null ? 2 : 3));
            for (int i = base; i < end; i++) {
                keys.add(keyStrategy.entityKey(metadata.redisNamespace(), ids.get(i)));
                keys.add(keyStrategy.tombstoneKey(metadata.redisNamespace(), ids.get(i)));
                if (tenant != null) keys.add(keyStrategy.versionKey(metadata.redisNamespace(), ids.get(i)) + ":tenant");
            }
            if (bytes >= 16L * 1024 * 1024) throw new IllegalStateException("Composite payload byte budget exceeded");
            var batch = jedis.boundedMget(keys, 16L * 1024 * 1024 - bytes);
            for (String value : batch) if (value != null) bytes += value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            int stride = tenant == null ? 2 : 3;
            for (int i = base; i < end; i++) {
                int offset = (i - base) * stride;
                String payload = batch.get(offset), tombstone = batch.get(offset + 1);
                if (tenant != null && !tenant.equals(batch.get(offset + 2))) denied.add(ids.get(i));
                values.set(i, payload); values.set(ids.size() + i, tombstone);
            }
        }
        compositeReadDeadline(query, startedAt);
        return new CompositeCandidatePayloads(values, java.util.Set.copyOf(denied));
    }

    @Override
    public com.reactor.cachedb.core.repository.CursorPage<T> queryRedisLongLexicalWindow(QuerySpec requested,
            com.reactor.cachedb.core.repository.WindowRequest request, String indexName,
            String route, String scope, Long afterValue, String afterId) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return CacheObservations.call(observationRecorder, CacheOperation.QUERY, metadata.entityName(),
                    observationOwner, () -> queryRedisLongLexicalWindow(requested, request, indexName, route, scope, afterValue, afterId));
        rejectUncertifiedProtectedRead();
        long startedAt = System.nanoTime();
        var window = java.util.Objects.requireNonNull(request, "window");
        QuerySpec query = tenantQuery(java.util.Objects.requireNonNull(requested, "query")).limitTo(window.limit());
        rejectProjectionOnlyEntityRoute("Redis Long lexical window");
        validateEntityRouteContract("Redis Long lexical window", window.limit());
        ReadShapeGuardrails.validateEntityQuery(metadata.entityName(), query, effectiveCachePolicy(), readShapeGuardrailConfig);
        queryIndexManager.requireRedisLongLexicalContract(query, indexName);
        if (com.reactor.cachedb.core.causal.CacheCausalContext.hasRequirements(metadata.redisNamespace()))
            throw new IllegalStateException("Redis Long lexical cursor cannot certify a causal scope; use causal entity reads");
        String contract = queryIndexManager.redisLongLexicalCursorContract(query, indexName, route, scope);
        if ((afterValue == null) != (afterId == null))
            throw new IllegalArgumentException("Redis Long lexical score and ID must both be null or both supplied");
        if (window.after() != null && afterValue != null)
            throw new IllegalArgumentException("Choose an opaque cursor or explicit seek values, not both");
        RedisQueryIndexManager.CompositeLongPosition after = null;
        if (window.after() != null) {
            var position = com.reactor.cachedb.core.repository.WindowCursor.decodeRedisLongLexical(window.after(), contract);
            after = new RedisQueryIndexManager.CompositeLongPosition(position.sortValue(), position.id());
        } else if (afterValue != null) after = new RedisQueryIndexManager.CompositeLongPosition(afterValue, afterId);
        boolean sourceAllowed = readThroughConfig.mode().queryEnabled()
                && (RouteCacheContext.currentContract() == null || RouteCacheContext.currentContract().sourceFallbackAllowed());
        var rejected = new java.util.LinkedHashSet<ID>();
        java.util.function.Predicate<T> admission = entity -> {
            verifyTenant(entity);
            if (pageCacheManager.shouldServeCachedEntity(entity)) return true;
            if (rejected.size() >= 10_000) throw new IllegalStateException("Composite admission rejection budget exceeded");
            rejected.add(metadata.idAccessor().apply(entity)); return false;
        };
        try {
            RedisQueryIndexManager.CompositeLongWindow<T> result;
            try {
                observationTier(Tier.REDIS);
                var fast = queryIndexManager.resolveRedisLongLexicalWindow(query, after, admission, indexName, sourceAllowed);
                compositeReadDeadline(query, startedAt);
                if (fast.isPresent()) result = fast.get();
                else {
                    rejected.clear();
                    result = queryIndexManager.resolveRedisLongLexicalFallback(query, after, admission, indexName, sourceAllowed);
                    compositeReadDeadline(query, startedAt);
                }
                if (result.rows().isEmpty() && sourceAllowed)
                    result = loadRedisLongLexicalSource(query, after, indexName, startedAt);
            } catch (RedisQueryIndexManager.CompositePayloadDivergenceException divergence) {
                if (!sourceAllowed) throw divergence;
                result = loadRedisLongLexicalSource(query, after, indexName, startedAt);
            }
            compositeReadDeadline(query, startedAt);
            if (effectiveCachePolicy().hotPolicy().evictWhenRejected())
                for (ID id : rejected) pageCacheManager.removeEntity(id);
            var rows = result.rows().stream().map(RedisQueryIndexManager.CompositeLongRow::entity).toList();
            pageCacheManager.recordEntityAccessBatch(rows.stream().map(metadata.idAccessor()).toList());
            queryIndexManager.observe(query, rows.size());
            applyFetchPlan(rows, query.fetchPlan());
            compositeReadDeadline(query, startedAt);
            String next = result.hasMore() && result.continuation() != null
                    ? com.reactor.cachedb.core.repository.WindowCursor.encodeRedisLongLexical(
                            result.continuation().sortValue(), result.continuation().id(), contract) : null;
            return new com.reactor.cachedb.core.repository.CursorPage<>(rows, next);
        } finally { recordRedisRead(startedAt); }
    }

    @Override
    public com.reactor.cachedb.core.repository.CursorPage<T> queryRedisLexicalWindow(QuerySpec requested,
            com.reactor.cachedb.core.repository.WindowRequest request, String indexName,
            String route, String scope, Double afterScore, String afterId) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return CacheObservations.call(observationRecorder, CacheOperation.QUERY, metadata.entityName(),
                    observationOwner, () -> queryRedisLexicalWindow(requested, request, indexName, route, scope, afterScore, afterId));
        rejectUncertifiedProtectedRead();
        long startedAt = System.nanoTime();
        var window = java.util.Objects.requireNonNull(request, "window");
        QuerySpec query = tenantQuery(java.util.Objects.requireNonNull(requested, "query")).limitTo(window.limit());
        rejectProjectionOnlyEntityRoute("Redis lexical window");
        validateEntityRouteContract("Redis lexical window", window.limit());
        ReadShapeGuardrails.validateEntityQuery(metadata.entityName(), query, effectiveCachePolicy(), readShapeGuardrailConfig);
        queryIndexManager.requireRedisLexicalContract(query, indexName);
        if (com.reactor.cachedb.core.causal.CacheCausalContext.hasRequirements(metadata.redisNamespace()))
            throw new IllegalStateException("Redis lexical cursor cannot certify a causal scope; use causal entity reads");
        String contract = queryIndexManager.redisLexicalCursorContract(query, indexName, route, scope);
        if ((afterScore == null) != (afterId == null))
            throw new IllegalArgumentException("Redis lexical score and ID must both be null or both supplied");
        if (window.after() != null && afterScore != null)
            throw new IllegalArgumentException("Choose an opaque cursor or explicit seek values, not both");
        RedisQueryIndexManager.CompositePosition after = null;
        if (window.after() != null) {
            var position = com.reactor.cachedb.core.repository.WindowCursor.decodeRedisLexical(window.after(), contract);
            after = new RedisQueryIndexManager.CompositePosition(position.score(), position.id());
        } else if (afterScore != null) after = new RedisQueryIndexManager.CompositePosition(afterScore, afterId);
        boolean sourceAllowed = readThroughConfig.mode().queryEnabled()
                && (RouteCacheContext.currentContract() == null || RouteCacheContext.currentContract().sourceFallbackAllowed());
        var rejected = new java.util.LinkedHashSet<ID>();
        java.util.function.Predicate<T> admission = entity -> {
            verifyTenant(entity);
            if (pageCacheManager.shouldServeCachedEntity(entity)) return true;
            if (rejected.size() >= 10_000) throw new IllegalStateException("Composite admission rejection budget exceeded");
            rejected.add(metadata.idAccessor().apply(entity)); return false;
        };
        try {
            RedisQueryIndexManager.CompositeWindow<T> result;
            try {
                observationTier(Tier.REDIS);
                var fast = queryIndexManager.resolveCompositeWindowForIndex(query, after, admission, indexName, sourceAllowed);
                compositeReadDeadline(query, startedAt);
                if (fast.isPresent()) result = fast.get();
                else {
                    rejected.clear();
                    result = queryIndexManager.resolveRedisLexicalFallback(query, after, admission, indexName, sourceAllowed);
                    compositeReadDeadline(query, startedAt);
                }
                if (result.rows().isEmpty() && sourceAllowed)
                    result = loadRedisLexicalSource(query, after, indexName, startedAt);
            } catch (RedisQueryIndexManager.CompositePayloadDivergenceException divergence) {
                if (!sourceAllowed) throw divergence;
                result = loadRedisLexicalSource(query, after, indexName, startedAt);
            }
            compositeReadDeadline(query, startedAt);
            if (effectiveCachePolicy().hotPolicy().evictWhenRejected())
                for (ID id : rejected) pageCacheManager.removeEntity(id);
            var rows = result.rows().stream().map(RedisQueryIndexManager.CompositeRow::entity).toList();
            pageCacheManager.recordEntityAccessBatch(rows.stream().map(metadata.idAccessor()).toList());
            queryIndexManager.observe(query, rows.size());
            applyFetchPlan(rows, query.fetchPlan());
            compositeReadDeadline(query, startedAt);
            String next = result.hasMore() && result.continuation() != null
                    ? com.reactor.cachedb.core.repository.WindowCursor.encodeRedisLexical(
                            result.continuation().score(), result.continuation().id(), contract) : null;
            return new com.reactor.cachedb.core.repository.CursorPage<>(rows, next);
        } finally { recordRedisRead(startedAt); }
    }

    private record CompositeSourceRows<E>(List<E> rows, long bytes) {}

    private RedisQueryIndexManager.CompositeWindow<T> loadRedisLexicalSource(QuerySpec query,
            RedisQueryIndexManager.CompositePosition after, String indexName, long startedAt) {
        var materialized = loadCompositeLexicalSource(query, startedAt);
        return queryIndexManager.redisLexicalWindowFromRows(query, after, materialized.rows(), entity -> true,
                indexName, materialized.bytes(), false);
    }

    private RedisQueryIndexManager.CompositeLongWindow<T> loadRedisLongLexicalSource(QuerySpec query,
            RedisQueryIndexManager.CompositeLongPosition after, String indexName, long startedAt) {
        var materialized = loadCompositeLexicalSource(query, startedAt);
        return queryIndexManager.redisLongLexicalWindowFromRows(query, after, materialized.rows(), entity -> true,
                indexName, materialized.bytes(), false);
    }

    private CompositeSourceRows<T> loadCompositeLexicalSource(QuerySpec query, long startedAt) {
        compositeReadDeadline(query, startedAt);
        int capacity = Math.min(queryIndexManager.compositeMaterializationLimit(), readThroughConfig.maxQueryLoadRows());
        if (capacity <= query.limit()) throw new IllegalStateException("Source lexical fallback needs a bounded completeness lookahead");
        QuerySpec source = QuerySpec.where(query.rootGroup()).limitTo(capacity)
                .withQueryTimeoutSeconds(query.queryTimeoutSeconds()).fetching(query.fetchPlan());
        List<T> loaded = loadQueryFromSource(source);
        compositeReadDeadline(query, startedAt);
        if (loaded.size() >= capacity)
            throw new IllegalStateException("Source lexical fallback cannot prove complete materialization within capacity");
        loaded.forEach(this::verifyTenant);
        var accepted = new ArrayList<T>(loaded.size());
        long bytes = 0;
        for (T entity : loaded) {
            String id = String.valueOf(metadata.idAccessor().apply(entity));
            RedisCompositeIndexes.validateMember(id);
            bytes += id.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 32L;
            bytes += codec.toRedisValue(entity).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > 16L * 1024 * 1024) throw new IllegalStateException("Source lexical materialization byte budget exceeded");
        }
        for (int base = 0; base < loaded.size(); base += 128) {
            compositeReadDeadline(query, startedAt);
            int end = Math.min(base + 128, loaded.size());
            var keys = new ArrayList<String>((end - base) * 2);
            for (int i = base; i < end; i++) {
                ID id = metadata.idAccessor().apply(loaded.get(i));
                keys.add(keyStrategy.tombstoneKey(metadata.redisNamespace(), id));
                keys.add(keyStrategy.versionKey(metadata.redisNamespace(), id) + ":tenant");
            }
            if (bytes >= 16L * 1024 * 1024) throw new IllegalStateException("Source lexical ownership byte budget exceeded");
            var values = jedis.boundedMget(keys, 16L * 1024 * 1024 - bytes);
            for (String value : values) if (value != null) bytes += value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            String tenant = activeTenant();
            for (int i = base; i < end; i++) {
                int offset = (i - base) * 2;
                if (values.get(offset) != null || tenant != null && values.get(offset + 1) != null
                        && !tenant.equals(values.get(offset + 1))) continue;
                accepted.add(loaded.get(i));
            }
        }
        return new CompositeSourceRows<>(accepted, bytes);
    }

    /** Every writer must use the cooperative composite protocol before this explicit migration. */
    public com.reactor.cachedb.core.queue.QueryIndexRebuildResult rebuildCompositeIndexesFenced(boolean automatic, String note) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return CacheObservations.call(observationRecorder, CacheOperation.REBUILD_INDEXES, metadata.entityName(),
                    observationOwner, () -> rebuildCompositeIndexesFenced(automatic, note));
        rejectUncertifiedProtectedRead();
        observationTier(Tier.REDIS);
        return queryIndexManager.rebuildCompositeIndexesFenced(automatic, note);
    }

    @Override
    public HotLookup<T> findHotById(ID id) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return CacheObservations.call(observationRecorder, CacheOperation.FIND_HOT_BY_ID, metadata.entityName(),
                    observationOwner, () -> findHotById(id));
        if (metadata.tenantPurgeEnabled()) return protectedPointReads().findHotById(activeTenant(), id, requiredVersion(id));
        activeTenant();
        if (pointReads != null) pointReads.ensureOpen();
        long startedAt = System.nanoTime();
        try {
            boolean local = nearCacheActive();
            String localId = local ? String.valueOf(id) : null;
            T cached = nearValue(id);
            if (cached != null) { applyFetchPlan(List.of(cached)); return HotLookup.hit(cached); }
            var stamp = local ? nearCache.capture(localId) : null;
            String entityKey = keyStrategy.entityKey(metadata.redisNamespace(), id);
            String tombstoneKey = keyStrategy.tombstoneKey(metadata.redisNamespace(), id);
            long minimumVersion = requiredVersion(id);
            RedisPointRead read = null;
            List<String> values = null;
            observationTier(Tier.REDIS);
            if (pointReads != null && pointReads.coalesce(minimumVersion)) {
                read = pointReads.runtime().readSync(entityKey, tombstoneKey, local);
            } else {
                values = pointValues(id, minimumVersion > 0);
            }
            if (minimumVersion > 0) verifyCausalVersion(id, values.get(2));
            if ((read == null ? values.get(1) : read.tombstone()) != null) {
                observationOutcome(Outcome.TOMBSTONE);
                return HotLookup.tombstoned();
            }
            String encoded = read == null ? values.get(0) : read.payload();
            if (encoded == null) {
                return HotLookup.notCached();
            }
            T entity = codec.fromRedisValue(encoded);
            verifyTenant(entity);
            if (!pageCacheManager.shouldServeCachedEntity(entity)) {
                if (effectiveCachePolicy().hotPolicy().evictWhenRejected()) {
                    pageCacheManager.removeEntity(id);
                }
                return HotLookup.outsidePolicy();
            }
            pageCacheManager.recordEntityAccess(id);
            if (local && nearCacheActive()) nearCache.put(localId, encoded, shareableNearEntity(entity, encoded),
                    pointReadTtl(read, entityKey), stamp, values != null && values.size() > 2 && values.get(2) != null ? Long.parseLong(values.get(2)) : 0);
            applyFetchPlan(List.of(entity));
            return HotLookup.hit(entity);
        } finally {
            recordRedisRead(startedAt);
        }
    }

    @Override
    public <K> Map<K, List<T>> queryPartitions(PartitionedQuerySpec<K> partitionedQuery) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return CacheObservations.call(observationRecorder, CacheOperation.QUERY_PARTITIONS, metadata.entityName(),
                    observationOwner, () -> queryPartitions(partitionedQuery));
        try {
            return queryPartitionsScoped(partitionedQuery);
        } catch (com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException denied) {
            throw new IllegalStateException("Tenant partition access denied", denied);
        }
    }

    private <K> Map<K, List<T>> queryPartitionsScoped(PartitionedQuerySpec<K> partitionedQuery) {
        requireUnversionedReadAllowed();
        String tenant = activeTenant();
        if (tenant != null) {
            if (partitionedQuery == null || partitionedQuery.partitionValues().isEmpty()) return Map.of();
            if (partitionedQuery.requestedRowCount() > 10_000) throw new IllegalArgumentException("Partitioned entity query supports at most 10000 rows");
            validateEntityRouteContract("Tenant partitioned query", partitionedQuery.requestedRowCount());
            QuerySpec scoped = tenantQuery(QuerySpec.where(QueryFilter.in(partitionedQuery.partitionColumn(), partitionedQuery.partitionValues())));
            ReadShapeGuardrails.validateEntityQuery(metadata.entityName(), scoped.limitTo(partitionedQuery.limitPerPartition()),
                    effectiveCachePolicy(), readShapeGuardrailConfig);
            observationTier(Tier.REDIS);
            var ids = queryIndexManager.resolveCandidateIds(scoped);
            if (ids.size() > 10_000) throw new IllegalStateException("Tenant partition candidate limit exceeded; use a bounded tenant projection");
            var result = new LinkedHashMap<K, List<T>>();
            for (K partition : partitionedQuery.partitionValues()) result.put(partition, new ArrayList<>());
            long bytes = 0;
            for (int start = 0; start < ids.size(); start += 128) {
                var batch = ids.subList(start, Math.min(ids.size(), start + 128));
                var entityKeys = batch.stream().map(id -> keyStrategy.entityKey(metadata.redisNamespace(), id)).toList();
                var tombstones = batch.stream().map(id -> keyStrategy.tombstoneKey(metadata.redisNamespace(), id)).toList();
                var requestedKeys = new ArrayList<>(entityKeys); requestedKeys.addAll(tombstones);
                var values = jedis.boundedMget(requestedKeys, 16L * 1024 * 1024);
                for (int i = 0; i < batch.size(); i++) {
                    String payload = values.get(i);
                    if (payload == null || values.get(batch.size() + i) != null) continue;
                    bytes += 2L * payload.length();
                    if (bytes > 16L * 1024 * 1024) throw new IllegalStateException("Tenant partition byte budget exceeded");
                    T row = codec.fromRedisValue(payload);
                    if (!queryEvaluator.matches(row, codec, scoped.rootGroup()) || !pageCacheManager.shouldServeCachedEntity(row)) continue;
                    verifyTenant(row);
                    var partitionRows = result.get(codec.entityColumnValue(row, partitionedQuery.partitionColumn()));
                    if (partitionRows != null) partitionRows.add(row);
                }
            }
            for (var entry : result.entrySet()) {
                var rows = entry.getValue();
                rows.sort((left, right) -> {
                    for (var sort : partitionedQuery.sorts()) {
                        int order = queryEvaluator.compareColumns(left, right, codec, sort);
                        if (order != 0) return order;
                    }
                    return String.valueOf(metadata.idAccessor().apply(left)).compareTo(String.valueOf(metadata.idAccessor().apply(right)));
                });
                var window = List.copyOf(rows.subList(0, Math.min(rows.size(), partitionedQuery.limitPerPartition())));
                applyFetchPlan(window); entry.setValue(window);
            }
            return Collections.unmodifiableMap(result);
        }
        long startedAt = System.nanoTime();
        try {
            if (partitionedQuery == null || partitionedQuery.partitionValues().isEmpty()) {
                return Map.of();
            }
            if (partitionedQuery.requestedRowCount() > 10_000) {
                throw new IllegalArgumentException(
                        "Partitioned entity query supports at most 10000 rows per call");
            }
            validateEntityRouteContract(
                    "Partitioned entity query", partitionedQuery.requestedRowCount());
            ReadShapeGuardrails.validateEntityQuery(
                    metadata.entityName(),
                    QuerySpec.where(
                                    QueryFilter.eq(
                                            partitionedQuery.partitionColumn(),
                                            partitionedQuery.partitionValues().get(0)))
                            .limitTo(partitionedQuery.limitPerPartition()),
                    effectiveCachePolicy(),
                    readShapeGuardrailConfig);
            observationTier(Tier.REDIS);
            Map<K, List<String>> idsByPartition =
                    queryIndexManager.resolvePartitionedIds(partitionedQuery);
            List<String> payloadIds =
                    idsByPartition.values().stream()
                            .flatMap(Collection::stream)
                            .distinct()
                            .toList();
            if (payloadIds.isEmpty()) {
                LinkedHashMap<K, List<T>> empty = new LinkedHashMap<>();
                partitionedQuery.partitionValues().forEach(value -> empty.put(value, List.of()));
                return Collections.unmodifiableMap(empty);
            }

            List<String> entityKeys =
                    payloadIds.stream()
                            .map(id -> keyStrategy.entityKey(metadata.redisNamespace(), id))
                            .toList();
            List<String> tombstoneKeys =
                    payloadIds.stream()
                            .map(id -> keyStrategy.tombstoneKey(metadata.redisNamespace(), id))
                            .toList();
            List<String> values = mgetEntityAndTombstones(entityKeys, tombstoneKeys);
            LinkedHashMap<String, T> entitiesById = new LinkedHashMap<>(payloadIds.size());
            ArrayList<String> staleIds = new ArrayList<>();
            for (int index = 0; index < payloadIds.size(); index++) {
                String payload = values.get(index);
                String tombstone = values.get(payloadIds.size() + index);
                if (payload == null || tombstone != null) {
                    staleIds.add(payloadIds.get(index));
                    continue;
                }
                T entity = codec.fromRedisValue(payload);
                if (pageCacheManager.shouldServeCachedEntity(entity)) {
                    entitiesById.put(payloadIds.get(index), entity);
                }
            }
            staleIds.forEach(queryIndexManager::removeById);

            LinkedHashMap<K, List<T>> result =
                    new LinkedHashMap<>(partitionedQuery.partitionValues().size());
            for (K partitionValue : partitionedQuery.partitionValues()) {
                ArrayList<T> partition = new ArrayList<>();
                for (String id : idsByPartition.getOrDefault(partitionValue, List.of())) {
                    T entity = entitiesById.get(id);
                    if (entity != null) {
                        partition.add(entity);
                    }
                }
                if (codec.directColumnAccess()) {
                    partition.sort((left, right) -> compareEntitiesBySorts(left, right, partitionedQuery.sorts()));
                } else {
                Map<T, Map<String, Object>> sortColumns = new IdentityHashMap<>(partition.size());
                for (T entity : partition) {
                    sortColumns.put(entity, codec.toColumns(entity));
                }
                partition.sort(
                        (left, right) ->
                                compareBySorts(
                                        sortColumns.get(left),
                                        sortColumns.get(right),
                                        partitionedQuery.sorts()));
                }
                if (partition.size() > partitionedQuery.limitPerPartition()) {
                    partition
                            .subList(partitionedQuery.limitPerPartition(), partition.size())
                            .clear();
                }
                applyFetchPlan(partition);
                result.put(partitionValue, List.copyOf(partition));
            }
            return Collections.unmodifiableMap(result);
        } finally {
            recordRedisRead(startedAt);
        }
    }

    private List<String> windowResolvedCandidateIds(
            QuerySpec querySpec,
            List<String> candidateIds,
            boolean sortedIndexScan,
            boolean completeSortOrderResolved,
            boolean requiresResidualEvaluation) {
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        if (sortedIndexScan || !completeSortOrderResolved || requiresResidualEvaluation) {
            return candidateIds;
        }
        int fromIndex = Math.min(querySpec.offset(), candidateIds.size());
        int toIndex = Math.min(fromIndex + querySpec.limit(), candidateIds.size());
        return candidateIds.subList(fromIndex, toIndex);
    }

    private void validateEntityRouteContract(String shape, int requestedRows) {
        RouteCacheContract contract = RouteCacheContext.currentContract();
        ReadShapeGuardrails.validateRouteContract(contract, "entity:" + metadata.entityName());
        ReadShapeGuardrails.validateRouteReadSize(contract, shape, requestedRows);
    }

    @Override
    public T save(T entity) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return observeWrite(CacheOperation.SAVE, () -> save(entity));
        if (com.reactor.cachedb.core.api.CacheUnitOfWork.active()) {
            var work = com.reactor.cachedb.core.api.CacheUnitOfWork.current();
            work.requireOwner(entityRegistry); work.put(entity); return entity;
        }
        return saveInternal(entity, -1L, null).entity();
    }

    @Override
    public WriteReceipt<T, ID> saveWithReceipt(T entity) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return observeWrite(CacheOperation.SAVE, () -> saveWithReceipt(entity));
        return saveInternal(entity, -1L, null);
    }

    @Override
    public WriteReceipt<T, ID> save(T entity, long expectedVersion) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return observeWrite(CacheOperation.SAVE, () -> save(entity, expectedVersion));
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        return saveInternal(entity, expectedVersion, null);
    }

    @Override
    public WriteReceipt<T, ID> saveAfter(T entity, WriteDependency dependency) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return observeWrite(CacheOperation.SAVE, () -> saveAfter(entity, dependency));
        if (dependency == null) {
            throw new IllegalArgumentException("dependency must not be null");
        }
        return saveInternal(entity, -1L, dependency);
    }

    @Override
    public List<WriteReceipt<T, ID>> saveAll(Collection<T> entities) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return observeWrite(CacheOperation.SAVE_ALL, () -> saveAll(entities));
        com.reactor.cachedb.core.api.CacheUnitOfWork.rejectImmediateReceipt();
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        if (entities.size() > 10_000) {
            throw new IllegalArgumentException(
                    "Bulk save supports at most 10000 entities per call");
        }
        if (metadata.tenantPurgeEnabled() && !ownedProtectedMutation(activeTenant()))
            return protectedMutation(activeTenant(), () -> saveAll(entities));
        List<T> input = List.copyOf(entities);
        java.util.HashSet<ID> uniqueIds = new java.util.HashSet<>(input.size());
        for (T entity : input) {
            verifyTenant(entity);
            ID id = entity == null ? null : metadata.idAccessor().apply(entity);
            if (id == null || !uniqueIds.add(id)) {
                throw new IllegalArgumentException(
                        "Bulk save requires non-null entities with unique ids");
            }
        }
        prepareTenantOwners(input);
        if (watching() || functionExecutor == null || !functionExecutor.enabled()) {
            return input.stream().map(this::saveWithReceipt).toList();
        }

        ArrayList<WriteReceipt<T, ID>> receipts = new ArrayList<>(input.size());
        for (int start = 0; start < input.size(); start += 128) {
            int end = Math.min(input.size(), start + 128);
            receipts.addAll(saveBatch(input.subList(start, end)));
        }
        return List.copyOf(receipts);
    }

    @Override
    public T patch(ID id, Map<String, Object> updates) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return observeWrite(CacheOperation.PATCH, () -> patch(id, updates));
        return patchInternal(id, updates, true).entity();
    }

    @Override
    public WriteReceipt<T, ID> patchWithReceipt(ID id, Map<String, Object> updates) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return observeWrite(CacheOperation.PATCH, () -> patchWithReceipt(id, updates));
        return patchInternal(id, updates, false);
    }

    private WriteReceipt<T, ID> patchInternal(ID id, Map<String, Object> updates, boolean hotRequired) {
        if (metadata.tenantPurgeEnabled() && !ownedProtectedMutation(activeTenant()))
            return protectedMutation(activeTenant(), () -> {
                try { return patchInternal(id, updates, hotRequired); }
                finally { invalidateNear(id); }
            });
        observationTier(Tier.REDIS);
        if (!queryIndexManager.hasCompositeSortIndexes()) return patchInternalUnfenced(id, updates, hotRequired);
        return queryIndexManager.withCompositeMutation(() -> patchInternalUnfenced(id, updates, hotRequired));
    }

    private WriteReceipt<T, ID> patchInternalUnfenced(ID id, Map<String, Object> updates, boolean hotRequired) {
        com.reactor.cachedb.core.api.CacheUnitOfWork.rejectImmediateReceipt();
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.requireBound(metadata, codec);
        java.util.Objects.requireNonNull(id, "id");
        String patchTenant = activeTenant();
        String cachedOwner = patchTenant == null ? null : verifiedOwners.get(id);
        if (cachedOwner != null && !patchTenant.equals(cachedOwner))
            throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException(metadata.entityName());
        boolean tenantOwnerKnown = patchTenant == null || cachedOwner != null;
        invalidateNear(id);
        if (updates == null || updates.isEmpty() || updates.size() > metadata.columns().size())
            throw new IllegalArgumentException("PATCH requires a bounded nonempty column set");
        for (String column : updates.keySet())
            if (!metadata.columns().contains(column) || column.equals(metadata.idColumn())
                    || column.equals(metadata.versionColumn()) || column.equals(metadata.deletedColumn()))
                throw new IllegalArgumentException("Invalid PATCH column");
        if (metadata.tenantFieldName() != null && updates.containsKey(metadata.tenantFieldName()))
            throw new IllegalArgumentException("PATCH cannot change tenant ownership");
        var assignments = new LinkedHashMap<String, Object>();
        var deltas = new LinkedHashMap<String, com.reactor.cachedb.core.model.PatchDeltaSpec>();
        updates.forEach((column, value) -> {
            if (value instanceof com.reactor.cachedb.core.model.PatchDeltaSpec delta) {
                com.reactor.cachedb.core.crypto.EncryptedStorageChecks.rejectProtectedRole(metadata, column);
                if (!column.equals(delta.columnName())) throw new IllegalArgumentException("Delta column mismatch");
                deltas.put(column, delta);
            } else assignments.put(column, value);
        });
        Map<String, Object> normalizedAssignments = codec.normalizePatchColumns(assignments);
        if (!normalizedAssignments.keySet().equals(assignments.keySet())) throw new IllegalArgumentException("Codec changed PATCH column membership");
        if (!functionExecutor.enabled()) throw new IllegalStateException("PATCH requires atomic Redis Functions");
        var settings = mutationConfig;
        producerGuard.applyBackpressure();
        String entityKey = keyStrategy.entityKey(metadata.redisNamespace(), id);
        String versionKey = keyStrategy.versionKey(metadata.redisNamespace(), id);
        String tombstoneKey = keyStrategy.tombstoneKey(metadata.redisNamespace(), id);
        com.reactor.cachedb.core.model.OptimisticWriteConflictException conflict = null;
        for (int attempt = 0; attempt < settings.maxPatchAttempts(); attempt++) {
            var values = tenantOwnerKnown ? jedis.mget(entityKey, versionKey, tombstoneKey)
                    : jedis.mget(entityKey, versionKey, tombstoneKey, versionKey + ":tenant");
            if (!tenantOwnerKnown && (metadata.tenantPurgeEnabled()
                    || com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata))) {
                if (!patchTenant.equals(values.get(3))) throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException(metadata.entityName());
                verifiedOwners.put(id, patchTenant); tenantOwnerKnown = true;
            }
            if (!tenantOwnerKnown) tenantOwnerKnown = verifyObservedOwner(id, patchTenant, false, false, values.get(3));
            if (values.get(2) != null) throw new IllegalStateException("PATCH cannot recreate a deleted entity");
            boolean hot = values.get(0) != null;
            if (!hot && watching()) throw new com.reactor.cachedb.core.repository.HotUpdateUnavailableException(
                    com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata) ? null : id);
            if (!hot && !deltas.isEmpty()) throw new com.reactor.cachedb.core.repository.HotUpdateUnavailableException(
                    com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata) ? null : id);
            if (!hot && (hotRequired || !settings.coldPatchEnabled()))
                throw new IllegalStateException("PATCH entity return requires a complete hot entity");
            boolean hasProjections = entityRegistry != null && !entityRegistry.projections(metadata.entityName()).isEmpty();
            if (values.get(1) == null) {
                if (metadata.tenantPurgeEnabled()) throw new com.reactor.cachedb.core.protection.ProtectionException(
                        com.reactor.cachedb.core.protection.ProtectionException.Code.MIGRATION_REQUIRED);
                if (com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata))
                    throw new IllegalStateException("Encrypted PATCH requires an established Redis version");
                var source = versionedSourceLoader(currentByIdLoader());
                if (source == null) throw new IllegalStateException("Cold PATCH requires a versioned source loader");
                var sourceVersion = source.loadVersionById(id);
                if (sourceVersion.isEmpty()) throw new IllegalArgumentException("PATCH target does not exist: " + id);
                long version = sourceVersion.getAsLong();
                jedis.eval("""
                        local current=tonumber(redis.call('GET',KEYS[1]) or '0')
                        if redis.call('EXISTS',KEYS[2]) == 0 and current < tonumber(ARGV[1]) then
                          redis.call('SET',KEYS[1],ARGV[1])
                        end
                        return 1
                        """, List.of(versionKey, tombstoneKey), List.of(Long.toString(version)));
                durabilityTracker.advance(metadata.redisNamespace(), id, version);
                values = jedis.mget(entityKey, versionKey, tombstoneKey);
                if (values.get(2) != null) throw new IllegalStateException("PATCH cannot recreate a deleted entity");
                if (values.get(1) == null) throw new IllegalStateException("PATCH version initialization was lost");
                hot = values.get(0) != null;
                if (!hot && (hotRequired || !settings.coldPatchEnabled()))
                    throw new IllegalStateException("PATCH entity return requires a complete hot entity");
            }
            if (!hot && hasProjections && (projectionRefreshQueue == null || currentByIdLoader() instanceof NoOpEntityByIdLoader))
                throw new IllegalStateException("Cold PATCH with projections requires the refresh queue and a source loader");
            long previous = Long.parseLong(values.get(1));
            T merged = null;
            Map<String, Object> fullColumns = null;
            String payload = "";
            boolean admitted = false;
            CachePolicy policy = effectiveCachePolicy();
            var delta = new LinkedHashMap<>(normalizedAssignments);
            if (hot) {
                T previousEntity = codec.fromRedisValue(values.get(0));
                verifyTenant(previousEntity);
                fullColumns = new LinkedHashMap<>(codec.toColumns(previousEntity));
                for (var entry : deltas.entrySet()) delta.put(entry.getKey(), applyNumericDelta(id, entry.getValue(), fullColumns.get(entry.getKey())));
                delta = new LinkedHashMap<>(codec.normalizePatchColumns(delta));
                fullColumns.putAll(delta);
                queryIndexManager.validateCompositeValues(fullColumns);
                merged = codec.fromLogicalColumns(fullColumns);
                payload = codec.toRedisValue(merged);
                admitted = shouldCacheEntity(id, fullColumns, CacheAdmissionSource.WRITE, policy, estimatePayloadBytes(payload));
            }
            Map<String, Object> columns = com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata)
                    ? (hot ? codec.toQueuedColumns(merged, delta)
                            : codec.toQueuedPatchColumns(String.valueOf(id), patchTenant, delta))
                    : new LinkedHashMap<>(delta);
            columns.put(metadata.idColumn(), id);
            if (com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata))
                com.reactor.cachedb.core.crypto.EncryptedStorageChecks.preparedBytes(0, payload, columns);
            if (!tenantOwnerKnown) { claimVerifiedTenantOwner(id); tenantOwnerKnown = true; }
            Instant created = Instant.now();
            var dependency = previous > 0 ? new WriteDependency(metadata.redisNamespace(), String.valueOf(id), previous) : null;
            var operation = new WriteOperation<>(OperationType.PATCH, metadata, id, columns, payload, 0, created, dependency);
            try {
                long version = functionExecutor.patch(List.of(entityKey, versionKey, tombstoneKey, streamKey,
                        keyStrategy.compactionPayloadKey(metadata.redisNamespace(), id),
                        keyStrategy.compactionPendingKey(metadata.redisNamespace(), id),
                        keyStrategy.compactionStreamKey(compactionStreamKey, metadata.redisNamespace(), id, compactionShardCount),
                        keyStrategy.compactionStatsKey(metadata.redisNamespace()),
                        keyStrategy.indexDegradedKey(metadata.redisNamespace())), operation, policy, admitted, previous);
                if (admitted) {
                    queryIndexManager.reindexBatchWithColumns(Map.of(id, merged), Map.of(id, fullColumns));
                    pageCacheManager.recordEntityAccess(id, fullColumns, estimatePayloadBytes(payload));
                } else pageCacheManager.removeEntity(id);
                if (merged != null) syncProjectionPayloads(merged, false);
                else {
                    deleteProjectionPayloads(id, true);
                    if (hasProjections) for (var binding : entityRegistry.projections(metadata.entityName()))
                        projectionRefreshQueue.enqueueSourceUpsert(metadata.entityName(), binding.projection().name(), id, version);
                }
                notifyWatchers(id, values.get(0), merged == null ? null : payload);
                return new WriteReceipt<>(merged, id, metadata.redisNamespace(), OperationType.PATCH, version, created);
            } catch (com.reactor.cachedb.core.model.OptimisticWriteConflictException retry) { conflict = retry; }
        }
        if (conflict != null) throw conflict;
        throw new IllegalStateException("PATCH version initialization exceeded maxPatchAttempts");
    }

    private Object applyNumericDelta(ID id, com.reactor.cachedb.core.model.PatchDeltaSpec spec, Object current) {
        try {
            return com.reactor.cachedb.core.model.NumericDeltas.apply(metadata.entityName(), id,
                    metadata.columnTypes().get(spec.columnName()), spec, current);
        } catch (RuntimeException failure) {
            if (com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata))
                throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue();
            throw failure;
        }
    }

    private List<WriteReceipt<T, ID>> saveBatch(List<T> entities) {
        observationTier(Tier.REDIS);
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.requireBound(metadata, codec);
        if (!queryIndexManager.hasCompositeSortIndexes()) return saveBatchUnfenced(entities);
        return queryIndexManager.withCompositeMutation(() -> saveBatchUnfenced(entities));
    }

    private List<WriteReceipt<T, ID>> saveBatchUnfenced(List<T> entities) {
        long startedAt = System.nanoTime();
        try {
            producerGuard.applyBackpressure();
            CachePolicy effectivePolicy = effectiveCachePolicy();
            ArrayList<RedisFunctionExecutor.UpsertRequest> requests =
                    new ArrayList<>(entities.size());
            ArrayList<WriteOperation<T, ID>> operations = new ArrayList<>(entities.size());
            ArrayList<ID> ids = new ArrayList<>(entities.size());
            ArrayList<Map<String, Object>> columnsByEntity = new ArrayList<>(entities.size());
            ArrayList<Long> payloadBytes = new ArrayList<>(entities.size());
            ArrayList<Boolean> admitted = new ArrayList<>(entities.size());
            long protectedPreparedBytes = 0;
            boolean protectedMapping = com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata);
            for (T entity : entities) {
                ID id = metadata.idAccessor().apply(entity);
                verifyTenant(entity);
                invalidateNear(id);
                String encoded = codec.toRedisValue(entity);
                Map<String, Object> columns = codec.toColumns(entity);
                queryIndexManager.validateCompositeValues(columns);
                long estimatedBytes = estimatePayloadBytes(encoded);
                boolean cacheEntity =
                        shouldCacheEntity(
                                id,
                                columns,
                                CacheAdmissionSource.WRITE,
                                effectivePolicy,
                                estimatedBytes);
                Map<String, Object> queuedColumns = protectedMapping ? codec.toQueuedColumns(entity) : columns;
                if (protectedMapping)
                    protectedPreparedBytes = com.reactor.cachedb.core.crypto.EncryptedStorageChecks.preparedBytes(protectedPreparedBytes, encoded, queuedColumns);
                WriteOperation<T, ID> operation =
                        new WriteOperation<>(
                                OperationType.UPSERT,
                                metadata,
                                id,
                                queuedColumns,
                                encoded,
                                0L,
                                Instant.now());
                String targetCompactionStreamKey =
                        keyStrategy.compactionStreamKey(
                                compactionStreamKey,
                                metadata.redisNamespace(),
                                id,
                                compactionShardCount);
                requests.add(
                        new RedisFunctionExecutor.UpsertRequest(
                                List.of(
                                        keyStrategy.entityKey(metadata.redisNamespace(), id),
                                        keyStrategy.versionKey(metadata.redisNamespace(), id),
                                        keyStrategy.tombstoneKey(metadata.redisNamespace(), id),
                                        streamKey,
                                        keyStrategy.compactionPayloadKey(
                                                metadata.redisNamespace(), id),
                                        keyStrategy.compactionPendingKey(
                                                metadata.redisNamespace(), id),
                                        targetCompactionStreamKey,
                                        keyStrategy.compactionStatsKey(metadata.redisNamespace())),
                                operation,
                                effectivePolicy,
                                cacheEntity));
                operations.add(operation);
                ids.add(id);
                columnsByEntity.add(columns);
                payloadBytes.add(estimatedBytes);
                admitted.add(cacheEntity);
            }

            List<Long> versions = functionExecutor.upsertBatch(requests);
            Map<ID, T> indexedEntities = new LinkedHashMap<>();
            Map<ID, Map<String, Object>> indexedColumns = new LinkedHashMap<>();
            Map<ID, Long> indexedBytes = new LinkedHashMap<>();
            ArrayList<WriteReceipt<T, ID>> receipts = new ArrayList<>(entities.size());
            for (int index = 0; index < entities.size(); index++) {
                ID id = ids.get(index);
                if (admitted.get(index)) {
                    indexedEntities.put(id, entities.get(index));
                    indexedColumns.put(id, columnsByEntity.get(index));
                    indexedBytes.put(id, payloadBytes.get(index));
                } else if (effectivePolicy.hotPolicy().evictWhenRejected()) {
                    pageCacheManager.removeEntity(id);
                }
                receipts.add(
                        new WriteReceipt<>(
                                entities.get(index),
                                id,
                                metadata.redisNamespace(),
                                OperationType.UPSERT,
                                versions.get(index),
                                operations.get(index).createdAt()));
            }
            queryIndexManager.reindexBatchWithColumns(indexedEntities, indexedColumns);
            pageCacheManager.recordEntityAccessBatch(indexedColumns, indexedBytes);
            syncProjectionPayloadsBatch(entities, false);
            return List.copyOf(receipts);
        } finally {
            if (metadata.tenantPurgeEnabled())
                for (T entity : entities) invalidateNear(metadata.idAccessor().apply(entity));
            recordRedisWrite(startedAt);
        }
    }

    private WriteReceipt<T, ID> saveInternal(
            T entity, long expectedVersion, WriteDependency dependency) {
        if (metadata.tenantPurgeEnabled() && !ownedProtectedMutation(metadata.extractTenantId(entity))) {
            com.reactor.cachedb.core.tenant.TenantAccess.verify(metadata, entity);
            return protectedMutation(metadata.extractTenantId(entity), () -> saveInternal(entity, expectedVersion, dependency));
        }
        observationTier(Tier.REDIS);
        if (!queryIndexManager.hasCompositeSortIndexes()) return saveInternalUnfenced(entity, expectedVersion, dependency);
        return queryIndexManager.withCompositeMutation(() -> saveInternalUnfenced(entity, expectedVersion, dependency));
    }

    private WriteReceipt<T, ID> saveInternalUnfenced(
            T entity, long expectedVersion, WriteDependency dependency) {
        com.reactor.cachedb.core.api.CacheUnitOfWork.rejectImmediateReceipt();
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.requireBound(metadata, codec);
        verifyTenant(entity); prepareEntityTenantOwner(entity);
        WatchBefore watched = watchBefore(metadata.idAccessor().apply(entity));
        if (watched != null && expectedVersion < 0) expectedVersion = watched.version();
        invalidateNear(metadata.idAccessor().apply(entity));
        long startedAt = System.nanoTime();
        try {
            producerGuard.applyBackpressure();
            ID id = metadata.idAccessor().apply(entity);
            String redisKey = keyStrategy.entityKey(metadata.redisNamespace(), id);
            String versionKey = keyStrategy.versionKey(metadata.redisNamespace(), id);
            String tombstoneKey = keyStrategy.tombstoneKey(metadata.redisNamespace(), id);
            String compactionPayloadKey =
                    keyStrategy.compactionPayloadKey(metadata.redisNamespace(), id);
            String compactionPendingKey =
                    keyStrategy.compactionPendingKey(metadata.redisNamespace(), id);
            String compactionStatsKey = keyStrategy.compactionStatsKey(metadata.redisNamespace());
            CachePolicy effectiveCachePolicy = effectiveCachePolicy();
            String targetCompactionStreamKey =
                    keyStrategy.compactionStreamKey(
                            compactionStreamKey,
                            metadata.redisNamespace(),
                            id,
                            compactionShardCount);
            String encoded = codec.toRedisValue(entity);
            Map<String, Object> columns = codec.toColumns(entity);
            queryIndexManager.validateCompositeValues(columns);
            long estimatedPayloadBytes = estimatePayloadBytes(encoded);
            boolean cacheEntity =
                    shouldCacheEntity(
                            id,
                            columns,
                            CacheAdmissionSource.WRITE,
                            effectiveCachePolicy,
                            estimatedPayloadBytes);
            boolean protectedMapping = com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata);
            Map<String, Object> queuedColumns = protectedMapping ? codec.toQueuedColumns(entity) : columns;
            if (protectedMapping)
                com.reactor.cachedb.core.crypto.EncryptedStorageChecks.preparedBytes(0, encoded, queuedColumns);
            WriteOperation<T, ID> operation =
                    new WriteOperation<>(
                            OperationType.UPSERT,
                            metadata,
                            id,
                            queuedColumns,
                            encoded,
                            0L,
                            Instant.now(),
                            dependency);

            long version;

            if (functionExecutor != null && functionExecutor.enabled()) {
                version =
                        functionExecutor.upsert(
                                redisKey,
                                versionKey,
                                tombstoneKey,
                                streamKey,
                                compactionPayloadKey,
                                compactionPendingKey,
                                targetCompactionStreamKey,
                                compactionStatsKey,
                                operation,
                                effectiveCachePolicy,
                                cacheEntity,
                                expectedVersion);
                if (cacheEntity) {
                    queryIndexManager.reindex(entity);
                    pageCacheManager.recordEntityAccess(id, columns, estimatedPayloadBytes);
                } else if (effectiveCachePolicy.hotPolicy().evictWhenRejected()) {
                    pageCacheManager.removeEntity(id);
                }
            } else {
                if (expectedVersion >= 0) {
                    throw new UnsupportedOperationException(
                            "Atomic optimistic writes require Redis Functions for "
                                    + metadata.entityName()
                                    + ". Enable the CacheDB Redis function executor instead of"
                                    + " using the legacy fallback.");
                }
                version = jedis.commands().incr(versionKey);
                jedis.commands().del(tombstoneKey);
                Map<String, Object> persistedColumns =
                        enrichColumnsForPersistence(operation.columns(), version, false);
                if (cacheEntity) {
                    pageCacheManager.cacheEntity(entity, encoded, CacheAdmissionSource.WRITE);
                } else if (effectiveCachePolicy.hotPolicy().evictWhenRejected()) {
                    pageCacheManager.removeEntity(id);
                }
                writeBehindQueue.enqueue(
                        new WriteOperation<>(
                                operation.type(),
                                metadata,
                                id,
                                persistedColumns,
                                encoded,
                                version,
                                operation.createdAt(),
                                dependency));
            }
            syncProjectionPayloads(entity, false);
            if (watched != null) notifyWatchers(id, watched.payload(), encoded);
            return new WriteReceipt<>(
                    entity,
                    id,
                    metadata.redisNamespace(),
                    OperationType.UPSERT,
                    version,
                    operation.createdAt());
        } finally {
            if (metadata.tenantPurgeEnabled()) invalidateNear(metadata.idAccessor().apply(entity));
            recordRedisWrite(startedAt);
        }
    }

    public T hydrate(T entity, long version) {
        return hydrateInternal(entity, version, true, true, true, CacheAdmissionSource.WRITE);
    }

    public T hydrateWarm(T entity, long version) {
        return hydrateInternal(entity, version, false, false, false, CacheAdmissionSource.WARM);
    }

    @Override
    public T hydrateExternalUpsert(T entity, long version) {
        if (entity == null) {
            throw new IllegalArgumentException("entity must not be null");
        }
        requirePositiveHydrationVersion(version);
        producerGuard.applyBackpressure();
        return hydrateInternal(entity, version, true, true, true, CacheAdmissionSource.READ);
    }

    public void hydrateWarmBatch(List<T> entities, List<Long> versions) {
        hydrateWarmBatch(entities, versions, false, false);
    }

    public void hydrateWarmBatch(
            List<T> entities, List<Long> versions, boolean forceImmediateProjectionRefresh) {
        hydrateWarmBatch(entities, versions, forceImmediateProjectionRefresh, false);
    }

    public void hydrateWarmBatch(
            List<T> entities,
            List<Long> versions,
            boolean forceImmediateProjectionRefresh,
            boolean reindexQueryIndexes) {
        hydrateWarmBatchInternal(
                entities, versions, forceImmediateProjectionRefresh, reindexQueryIndexes);
    }

    /** Returns only rows accepted by the entity hot policy and Redis version fence. */
    public List<T> hydrateWarmBatchAccepted(
            List<T> entities,
            List<Long> versions,
            boolean forceImmediateProjectionRefresh,
            boolean reindexQueryIndexes) {
        return hydrateWarmBatchInternal(
                entities, versions, forceImmediateProjectionRefresh, reindexQueryIndexes);
    }

    /**
     * Incrementally removes cached rows that no longer satisfy the current hot policy. This is
     * cache-only maintenance: it never creates a tombstone or write-behind command.
     */
    public RedisHotSetReconciliationResult reconcileHotSet(
            String cursor, int maxRows, int scanCount) {
        requireTenantMaintenanceScope();
        if (maxRows <= 0) {
            throw new IllegalArgumentException("maxRows must be greater than zero");
        }
        if (scanCount <= 0) {
            throw new IllegalArgumentException("scanCount must be greater than zero");
        }

        String nextCursor = normalizeScanCursor(cursor);
        int inspectedRows = 0;
        int evictedRows = 0;
        int missingRows = 0;
        int invalidRows = 0;
        CachePolicy effectivePolicy = effectiveCachePolicy();
        String hotSetKey = keyStrategy.hotSetKey(metadata.redisNamespace());

        do {
            int remaining = Math.max(1, maxRows - inspectedRows);
            ScanParams params = new ScanParams().count(Math.min(scanCount, remaining));
            ScanResult<Tuple> batch =
                    backgroundJedis.commands().zscan(hotSetKey, nextCursor, params);
            nextCursor = normalizeScanCursor(batch.getCursor());
            for (Tuple tuple : batch.getResult()) {
                String rawId = tuple.getElement();
                inspectedRows++;
                String encoded =
                        backgroundJedis
                                .commands()
                                .get(keyStrategy.entityKey(metadata.redisNamespace(), rawId));
                if (encoded == null) {
                    evictCacheOnly(rawId);
                    evictedRows++;
                    missingRows++;
                    continue;
                }

                T entity;
                try {
                    entity = codec.fromRedisValue(encoded);
                } catch (RuntimeException invalidPayload) {
                    evictCacheOnly(rawId);
                    evictedRows++;
                    invalidRows++;
                    continue;
                }
                if (effectivePolicy
                        .hotPolicy()
                        .shouldAdmit(codec.toColumns(entity), CacheAdmissionSource.SERVE)) {
                    continue;
                }
                evictCacheOnly(metadata.idAccessor().apply(entity));
                evictedRows++;
            }
        } while (!"0".equals(nextCursor) && inspectedRows < maxRows);

        if (performanceCollector != null && evictedRows > 0) {
            performanceCollector.recordCacheEviction(
                    "policy-reconciliation:" + metadata.entityName(), evictedRows);
        }
        return new RedisHotSetReconciliationResult(
                nextCursor,
                inspectedRows,
                evictedRows,
                missingRows,
                invalidRows,
                "0".equals(nextCursor));
    }

    private List<T> hydrateWarmBatchInternal(
            List<T> entities,
            List<Long> versions,
            boolean forceImmediateProjectionRefresh,
            boolean reindexQueryIndexes) {
        if (!queryIndexManager.hasCompositeSortIndexes()) return hydrateWarmBatchInternalUnfenced(entities, versions, forceImmediateProjectionRefresh, reindexQueryIndexes);
        return queryIndexManager.withCompositeMutation(() -> hydrateWarmBatchInternalUnfenced(entities, versions, forceImmediateProjectionRefresh, reindexQueryIndexes));
    }

    private List<T> hydrateWarmBatchInternalUnfenced(
            List<T> entities,
            List<Long> versions,
            boolean forceImmediateProjectionRefresh,
            boolean reindexQueryIndexes) {
        if (queryIndexManager.hasCompositeSortIndexes()) reindexQueryIndexes = true;
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        if (versions == null || versions.size() != entities.size()) {
            throw new IllegalArgumentException(
                    "versions must be present and aligned with entities for hydrateWarmBatch");
        }
        entities.forEach(this::verifyTenant);
        long startedAt = System.nanoTime();
        try {
            CachePolicy effectiveCachePolicy = effectiveCachePolicy();
            ArrayList<T> admittedEntities = new ArrayList<>(entities.size());
            ArrayList<ID> admittedIds = new ArrayList<>(entities.size());
            ArrayList<Long> admittedVersions = new ArrayList<>(entities.size());
            ArrayList<Map<String, Object>> admittedColumns = new ArrayList<>(entities.size());
            ArrayList<String> admittedPayloads = new ArrayList<>(entities.size());
            ArrayList<Long> admittedPayloadBytes = new ArrayList<>(entities.size());
            ArrayList<ID> rejectedIds = new ArrayList<>();
            long protectedPreparedBytes = 0;
            for (int index = 0; index < entities.size(); index++) {
                Long requestedVersion = versions.get(index);
                if (requestedVersion == null) {
                    throw new IllegalArgumentException(
                            "Hydration version must not be null at index " + index);
                }
                requirePositiveHydrationVersion(requestedVersion);
                T entity = entities.get(index);
                ID id = metadata.idAccessor().apply(entity);
                Map<String, Object> columns = codec.toColumns(entity);
                queryIndexManager.validateCompositeValues(columns);
                String encoded = codec.toRedisValue(entity);
                long estimatedPayloadBytes = estimatePayloadBytes(encoded);
                if (shouldCacheEntity(
                        id,
                        columns,
                        CacheAdmissionSource.WARM,
                        effectiveCachePolicy,
                        estimatedPayloadBytes)) {
                    if (com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata))
                        protectedPreparedBytes = com.reactor.cachedb.core.crypto.EncryptedStorageChecks.preparedBytes(protectedPreparedBytes, encoded, Map.of());
                    admittedEntities.add(entity);
                    admittedIds.add(id);
                    admittedVersions.add(versions.get(index));
                    admittedColumns.add(columns);
                    admittedPayloads.add(encoded);
                    admittedPayloadBytes.add(estimatedPayloadBytes);
                } else {
                    rejectedIds.add(id);
                }
            }

            ArrayList<RedisVersionedHydrator.Upsert> hydrationOperations =
                    new ArrayList<>(admittedEntities.size());
            for (int index = 0; index < admittedEntities.size(); index++) {
                ID id = admittedIds.get(index);
                hydrationOperations.add(
                        new RedisVersionedHydrator.Upsert(
                                keyStrategy.entityKey(metadata.redisNamespace(), id),
                                keyStrategy.versionKey(metadata.redisNamespace(), id),
                                keyStrategy.tombstoneKey(metadata.redisNamespace(), id),
                                admittedPayloads.get(index),
                                admittedVersions.get(index),
                                effectiveCachePolicy.entityTtlSeconds()));
            }
            List<Boolean> hydrationResults = versionedHydrator.upsertBatch(hydrationOperations);
            ArrayList<T> hydratedEntities = new ArrayList<>(admittedEntities.size());
            ArrayList<ID> hydratedIds = new ArrayList<>(admittedIds.size());
            ArrayList<Long> hydratedVersions = new ArrayList<>(admittedVersions.size());
            ArrayList<Map<String, Object>> hydratedColumns =
                    new ArrayList<>(admittedColumns.size());
            ArrayList<Long> hydratedPayloadBytes = new ArrayList<>(admittedPayloadBytes.size());
            for (int index = 0; index < hydrationResults.size(); index++) {
                if (hydrationResults.get(index)) {
                    hydratedEntities.add(admittedEntities.get(index));
                    hydratedIds.add(admittedIds.get(index));
                    hydratedVersions.add(admittedVersions.get(index));
                    hydratedColumns.add(admittedColumns.get(index));
                    hydratedPayloadBytes.add(admittedPayloadBytes.get(index));
                }
            }
            durabilityTracker.advanceBatch(
                    metadata.redisNamespace(), hydratedIds, hydratedVersions);
            if (effectiveCachePolicy.hotPolicy().evictWhenRejected()) {
                for (ID rejectedId : rejectedIds) {
                    pageCacheManager.removeEntity(rejectedId);
                }
            }
            if (reindexQueryIndexes) {
                queryIndexManager.reindexBatch(hydratedEntities);
            }
            for (int index = 0; index < hydratedIds.size(); index++) {
                pageCacheManager.recordEntityAccess(
                        hydratedIds.get(index),
                        hydratedColumns.get(index),
                        hydratedPayloadBytes.get(index));
            }
            if (forceImmediateProjectionRefresh) {
                syncProjectionPayloadsBatch(hydratedEntities, true);
            } else {
                enqueueProjectionRefreshBatch(hydratedEntities, hydratedIds);
            }
            return List.copyOf(hydratedEntities);
        } finally {
            recordRedisWrite(startedAt);
        }
    }

    @Override
    public Optional<VersionedEntity<T>> findVersionedById(ID id) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return CacheObservations.call(observationRecorder, CacheOperation.FIND_VERSIONED_BY_ID, metadata.entityName(),
                    observationOwner, () -> findVersionedById(id));
        if (metadata.tenantPurgeEnabled()) return protectedPointReads().findVersionedById(activeTenant(), id, requiredVersion(id));
        activeTenant();
        long startedAt = System.nanoTime();
        try {
            String entityKey = keyStrategy.entityKey(metadata.redisNamespace(), id);
            String tombstoneKey = keyStrategy.tombstoneKey(metadata.redisNamespace(), id);
            String versionKey = keyStrategy.versionKey(metadata.redisNamespace(), id);
            observationTier(Tier.REDIS);
            List<String> values = jedis.mget(entityKey, tombstoneKey, versionKey);
            if (values.get(1) != null) observationOutcome(Outcome.TOMBSTONE);
            if (values.get(0) == null || values.get(1) != null) {
                return Optional.empty();
            }
            long version = parsePositiveVersion(values.get(2));
            if (version <= 0) {
                throw new IllegalStateException(
                        "Cached entity "
                                + metadata.entityName()
                                + " id="
                                + id
                                + " has no positive Redis version");
            }
            T entity = codec.fromRedisValue(values.get(0));
            verifyTenant(entity);
            if (!pageCacheManager.shouldServeCachedEntity(entity)) {
                if (effectiveCachePolicy().hotPolicy().evictWhenRejected()) {
                    pageCacheManager.removeEntity(id);
                }
                return Optional.empty();
            }
            pageCacheManager.recordEntityAccess(id);
            applyFetchPlan(List.of(entity));
            return Optional.of(new VersionedEntity<>(entity, version));
        } finally {
            recordRedisRead(startedAt);
        }
    }

    public void hydrateProjectionWarmBatch(List<T> entities) {
        requireTenantMaintenanceScope();
        if (entities == null || entities.isEmpty()) {
            return;
        }
        long startedAt = System.nanoTime();
        try {
            warmProjectionPayloadsBatch(entities);
        } finally {
            recordRedisWrite(startedAt);
        }
    }

    /**
     * Warms exactly one registered projection and returns the number of source rows that were
     * processed. A missing projection is a configuration error; silently warming a different
     * surface would make route-coverage evidence untrustworthy.
     */
    public int hydrateProjectionWarmBatchAccepted(List<T> entities, String projectionName) {
        requireTenantMaintenanceScope();
        if (entities == null || entities.isEmpty()) {
            return 0;
        }
        if (projectionName == null || projectionName.isBlank()) {
            throw new IllegalArgumentException("projectionName must not be blank");
        }
        if (entityRegistry == null) {
            throw new IllegalStateException("Projection warm requires an EntityRegistry");
        }
        EntityProjectionBinding<?, ?, ?> selected =
                entityRegistry.projections(metadata.entityName()).stream()
                        .filter(
                                binding ->
                                        binding.projection().name().equals(projectionName.trim()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No registered CacheDB projection named "
                                                        + projectionName
                                                        + " for entity "
                                                        + metadata.entityName()));
        long startedAt = System.nanoTime();
        try {
            warmProjectionPayloadBatch(selected, entities);
            return entities.size();
        } finally {
            recordRedisWrite(startedAt);
        }
    }

    private void warmProjectionPayloadsBatch(List<T> entities) {
        if (entities == null || entities.isEmpty() || entityRegistry == null) {
            return;
        }
        Collection<com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?>> bindings =
                entityRegistry.projections(metadata.entityName());
        if (bindings.isEmpty()) {
            return;
        }
        for (com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding :
                bindings) {
            warmProjectionPayloadBatch(rawBinding, entities);
        }
    }

    @SuppressWarnings("unchecked")
    private <P> void warmProjectionPayloadBatch(
            com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding,
            List<T> entities) {
        EntityProjectionBinding<T, P, ID> binding = (EntityProjectionBinding<T, P, ID>) rawBinding;
        ProjectionSupport<T, ID, P> support = projectionSupport(binding);
        ArrayList<P> projections = new ArrayList<>(entities.size());
        ArrayList<ID> deleteIds = new ArrayList<>();
        for (T entity : entities) {
            ID id = metadata.idAccessor().apply(entity);
            P projection = binding.projection().projector().apply(entity);
            if (projection == null) {
                deleteIds.add(id);
            } else {
                projections.add(projection);
            }
        }
        if (!projections.isEmpty()) {
            safeProjectionWarmUpsertBatch(support.refreshRuntime(), projections);
        }
        for (ID deleteId : deleteIds) {
            applyProjectionDelete(support, deleteId, true);
        }
    }

    private T hydrateInternal(
            T entity,
            long version,
            boolean forceImmediateProjectionRefresh,
            boolean reindexQueryIndexes,
            boolean recordPageAccess,
            CacheAdmissionSource source) {
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.requireBound(metadata, codec);
        if (!queryIndexManager.hasCompositeSortIndexes()) return hydrateInternalUnfenced(entity, version, forceImmediateProjectionRefresh, reindexQueryIndexes, recordPageAccess, source);
        return queryIndexManager.withCompositeMutation(() -> hydrateInternalUnfenced(entity, version, forceImmediateProjectionRefresh, reindexQueryIndexes, recordPageAccess, source));
    }

    private T hydrateInternalUnfenced(
            T entity,
            long version,
            boolean forceImmediateProjectionRefresh,
            boolean reindexQueryIndexes,
            boolean recordPageAccess,
            CacheAdmissionSource source) {
        if (queryIndexManager.hasCompositeSortIndexes()) reindexQueryIndexes = true;
        verifyTenant(entity);
        requirePositiveHydrationVersion(version);
        long startedAt = System.nanoTime();
        try {
            ID id = metadata.idAccessor().apply(entity);
            String redisKey = keyStrategy.entityKey(metadata.redisNamespace(), id);
            String versionKey = keyStrategy.versionKey(metadata.redisNamespace(), id);
            String tombstoneKey = keyStrategy.tombstoneKey(metadata.redisNamespace(), id);
            String encoded = codec.toRedisValue(entity);
            if (com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata))
                com.reactor.cachedb.core.crypto.EncryptedStorageChecks.preparedBytes(0, encoded, Map.of());
            CachePolicy effectiveCachePolicy = effectiveCachePolicy();
            Map<String, Object> columns = codec.toColumns(entity);
            queryIndexManager.validateCompositeValues(columns);
            long estimatedPayloadBytes = estimatePayloadBytes(encoded);
            boolean cacheEntity =
                    shouldCacheEntity(
                            id, columns, source, effectiveCachePolicy, estimatedPayloadBytes);
            if (!cacheEntity) {
                if (effectiveCachePolicy.hotPolicy().evictWhenRejected()) {
                    pageCacheManager.removeEntity(id);
                }
                syncProjectionPayloads(entity, forceImmediateProjectionRefresh);
                return entity;
            }
            boolean hydrated =
                    versionedHydrator.upsert(
                            redisKey,
                            versionKey,
                            tombstoneKey,
                            encoded,
                            version,
                            effectiveCachePolicy.entityTtlSeconds());
            if (!hydrated) {
                return entity;
            }
            durabilityTracker.advance(metadata.redisNamespace(), id, version);
            if (reindexQueryIndexes) {
                queryIndexManager.reindex(entity);
            }
            if (recordPageAccess || source == CacheAdmissionSource.WARM) {
                pageCacheManager.recordEntityAccess(id, columns, estimatedPayloadBytes);
            }
            syncProjectionPayloads(entity, forceImmediateProjectionRefresh);
            return entity;
        } finally {
            recordRedisWrite(startedAt);
        }
    }

    @Override
    public void deleteById(ID id) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner)) {
            observeWrite(CacheOperation.DELETE, () -> { deleteById(id); return null; });
            return;
        }
        if (com.reactor.cachedb.core.api.CacheUnitOfWork.active()) {
            var work = com.reactor.cachedb.core.api.CacheUnitOfWork.current();
            work.requireOwner(entityRegistry); work.delete(metadata.entityType(), id); return;
        }
        deleteWithReceipt(id);
    }

    @Override
    public List<WriteReceipt<T, ID>> bulkDelete(Collection<ID> requested) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return observeWrite(CacheOperation.DELETE, () -> bulkDelete(requested));
        com.reactor.cachedb.core.api.CacheUnitOfWork.rejectImmediateReceipt();
        if (metadata.tenantPurgeEnabled() && !ownedProtectedMutation(activeTenant()))
            return protectedMutation(activeTenant(), () -> bulkDelete(requested));
        RedisEncryptedBulkDeleteChecks.requireScope(metadata);
        if (pointReads == null || functionExecutor == null || !functionExecutor.supportsBulkDelete())
            throw new com.reactor.cachedb.core.api.RepositoryCapabilityUnavailableException(
                    com.reactor.cachedb.core.api.RepositoryCapability.BULK_DELETE, getClass());
        if (!pointReads.acquireBulkDelete())
            throw new java.util.concurrent.RejectedExecutionException("Bulk delete runtime admission is full");
        long startedAt = System.nanoTime();
        try {
            observationTier(Tier.REDIS);
            var ids = RedisBulkDeleteExecutor.copyIds(requested);
            if (ids.isEmpty()) return List.of();
            String tenant = activeTenant();
            com.reactor.cachedb.core.crypto.EncryptedStorageChecks.requireBound(metadata, codec);
            long deadline = pointReads.bulkDeleteDeadline();
            RedisEncryptedBulkDeleteChecks.requireMapping(metadata, codec, entityRegistry);
            RedisEncryptedBulkDeleteChecks.probeIds(metadata, codec, ids, tenant, deadline);
            // No source bootstrap: tenant owner markers are mandatory, including protected/cold IDs.
            RedisBulkDeleteExecutor.requireOwners(jedis, keyStrategy, metadata, ids, tenant, deadline);
            for (ID id : ids) jedis.requireSameSlot(bulkDeleteKeys(id));
            if (entityRegistry != null) {
                var bindings = entityRegistry.projections(metadata.entityName());
                if (bindings.size() > 32) throw new IllegalArgumentException("Bulk delete supports at most 32 projection bindings");

            }
            return queryIndexManager.withCompositeMutation(() -> bulkDeletePrepared(ids, tenant, deadline));
        } finally {
            pointReads.releaseBulkDelete();
            recordRedisWrite(startedAt);
        }
    }

    private List<String> bulkDeleteKeys(ID id) {
        return List.of(keyStrategy.entityKey(metadata.redisNamespace(), id),
                keyStrategy.versionKey(metadata.redisNamespace(), id),
                keyStrategy.tombstoneKey(metadata.redisNamespace(), id), streamKey,
                keyStrategy.compactionPayloadKey(metadata.redisNamespace(), id),
                keyStrategy.compactionPendingKey(metadata.redisNamespace(), id),
                keyStrategy.compactionStreamKey(compactionStreamKey, metadata.redisNamespace(), id, compactionShardCount),
                keyStrategy.compactionStatsKey(metadata.redisNamespace()));
    }

    private List<WriteReceipt<T, ID>> bulkDeletePrepared(List<ID> ids, String tenant, long deadline) {
        var accepted = new ArrayList<WriteReceipt<T, ID>>();
        for (int start = 0; start < ids.size(); start += RedisBulkDeleteExecutor.CHUNK) {
            var batch = ids.subList(start, Math.min(ids.size(), start + RedisBulkDeleteExecutor.CHUNK));
            final List<RedisBulkDeleteExecutor.State> snapshots;
            final List<RedisFunctionExecutor.DeleteRequest> requests;
            final RedisFunctionExecutor.PreparedDeleteBatch prepared;
            try {
                RedisBulkDeleteExecutor.checkDeadline(deadline);
                boolean watched = watching();
                snapshots = RedisBulkDeleteExecutor.states(jedis, keyStrategy, metadata, batch, watched, deadline);
                requests = new ArrayList<>(batch.size());
                long commandBytes = 0; int commandCount = 0;
                for (int i = 0; i < batch.size(); i++) {
                    ID id = batch.get(i); var snapshot = snapshots.get(i);
                    if (metadata.tenantFieldName() != null && !java.util.Objects.equals(tenant, snapshot.owner()))
                        throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException(metadata.entityName());
                    RedisEncryptedBulkDeleteChecks.validateSnapshot(metadata, codec, keyStrategy, id, tenant, snapshot, watched);
                    var indexCommands = queryIndexManager.bulkDeleteCommands(id, snapshot.meta());
                    RedisEncryptedBulkDeleteChecks.validateIndexCommands(metadata, keyStrategy, indexCommands);
                    var maintenance = RedisBulkDeleteExecutor.maintenance(keyStrategy, metadata, id, snapshot, indexCommands);
                    for (var command : maintenance) {
                        if (++commandCount > RedisDeleteCommandBuffer.MAX_COMMANDS)
                            throw new IllegalArgumentException("Delete maintenance exceeds 8192 commands");
                        for (String argument : command) commandBytes += com.reactor.cachedb.core.codec.Utf8Length.of(argument);
                        if (commandBytes > RedisBulkDeleteExecutor.BYTES)
                            throw new IllegalArgumentException("Delete maintenance exceeds 16 MiB");
                    }
                    var operation = new WriteOperation<T, ID>(OperationType.DELETE, metadata, id,
                            Map.of(metadata.idColumn(), id), "", 0L, Instant.now());
                    requests.add(new RedisFunctionExecutor.DeleteRequest(bulkDeleteKeys(id), operation,
                            effectiveCachePolicy(), snapshot.version(),
                            keyStrategy.tenantHotOwnerKey(metadata.redisNamespace(), id), snapshot.hotOwner(), maintenance));
                }
                prepared = functionExecutor.prepareDeleteBatch(requests);
                producerGuard.applyBackpressure();
                RedisBulkDeleteExecutor.checkDeadline(deadline);
            } catch (RuntimeException failure) {
                if (accepted.isEmpty()) throw failure;
                throw bulkDeleteFailure(com.reactor.cachedb.core.api.BulkDeleteFailureException.Phase.PREFLIGHT,
                        accepted, List.of(), List.of(), List.of(), ids.size() - start);
            }
            for (ID id : batch) invalidateNear(id);
            final List<List<String>> replies;
            try { replies = functionExecutor.deleteBatch(prepared); }
            catch (RuntimeException uncertain) {
                // No blind retry: any subset may already be durable in Redis.
                throw bulkDeleteFailure(com.reactor.cachedb.core.api.BulkDeleteFailureException.Phase.MUTATION,
                        accepted, List.of(), batch, List.of(), ids.size() - start - batch.size());
            }
            var changed = new ArrayList<ID>(); var acceptedVersions = new LinkedHashMap<ID,Long>(); var rejected = new ArrayList<ID>();
            var uncertain = new ArrayList<ID>(); var cleanup = new ArrayList<ID>(); int notAttempted = ids.size() - start - batch.size();
            for (int i = 0; i < replies.size(); i++) {
                var reply = replies.get(i); ID id = batch.get(i);
                if ("A".equals(reply.get(0))) {
                    long version;
                    try { version = Long.parseLong(reply.get(1)); }
                    catch (NumberFormatException malformed) { uncertain.add(id); continue; }
                    if (version <= 0) { uncertain.add(id); continue; }
                    accepted.add(new WriteReceipt<>(null, id, metadata.redisNamespace(), OperationType.DELETE,
                            version, requests.get(i).operation().createdAt()));
                    changed.add(id); acceptedVersions.put(id, version);
                    if (!"C".equals(reply.get(2))) cleanup.add(id);
                } else if ("R".equals(reply.get(0))) rejected.add(id);
                else if ("N".equals(reply.get(0))) notAttempted++;
                else uncertain.add(id);
            }
            try {
                pageCacheManager.bulkDeleteObserved(changed);
                queryIndexManager.bulkDeleteObserved();
                deleteProjectionPayloadsBatch(acceptedVersions, deadline);
            } catch (RuntimeException postAcceptance) { for (ID id : changed) if (!cleanup.contains(id)) cleanup.add(id); }
            for (int i = 0; i < batch.size(); i++) if (changed.contains(batch.get(i))) {
                invalidateNear(batch.get(i));
                if (watching()) notifyWatchers(batch.get(i), snapshots.get(i).payload(), null);
            }
            if (!rejected.isEmpty() || !uncertain.isEmpty() || !cleanup.isEmpty() || notAttempted > ids.size() - start - batch.size())
                throw bulkDeleteFailure(cleanup.isEmpty()
                                ? com.reactor.cachedb.core.api.BulkDeleteFailureException.Phase.MUTATION
                                : com.reactor.cachedb.core.api.BulkDeleteFailureException.Phase.CLEANUP,
                        accepted, rejected, uncertain, cleanup, notAttempted);
        }
        return List.copyOf(accepted);
    }

    private com.reactor.cachedb.core.api.BulkDeleteFailureException bulkDeleteFailure(
            com.reactor.cachedb.core.api.BulkDeleteFailureException.Phase phase,
            List<WriteReceipt<T, ID>> accepted, Collection<ID> rejected, Collection<ID> uncertain,
            Collection<ID> cleanup, int notAttempted) {
        return new com.reactor.cachedb.core.api.BulkDeleteFailureException(
                phase, accepted, rejected, uncertain, cleanup, notAttempted);
    }

    @SuppressWarnings("unchecked")
    private void deleteProjectionPayloadsBatch(Map<ID,Long> versions, long deadline) {
        if (versions.isEmpty() || entityRegistry == null) return;
        for (var raw : entityRegistry.projections(metadata.entityName())) {
            var binding = (EntityProjectionBinding<T, Object, ID>) raw;
            // Bulk delete deliberately completes bounded cleanup inline for every refresh mode.
            projectionSupport(binding).readRuntime().deleteBatch(versions, metadata.redisNamespace(), deadline);
        }
    }

    @Override
    public WriteReceipt<T, ID> deleteWithReceipt(ID id) {
        if (observationEnabled && !CacheObservationContext.isOwnedBy(observationOwner))
            return observeWrite(CacheOperation.DELETE, () -> deleteWithReceipt(id));
        if (metadata.tenantPurgeEnabled() && !ownedProtectedMutation(activeTenant()))
            return protectedMutation(activeTenant(), () -> deleteWithReceipt(id));
        observationTier(Tier.REDIS);
        if (!queryIndexManager.hasCompositeSortIndexes()) return deleteWithReceiptUnfenced(id);
        return queryIndexManager.withCompositeMutation(() -> deleteWithReceiptUnfenced(id));
    }

    private WriteReceipt<T, ID> deleteWithReceiptUnfenced(ID id) {
        com.reactor.cachedb.core.api.CacheUnitOfWork.rejectImmediateReceipt();
        verifyProtectedWriteOwner(id, activeTenant());
        ensureTenantOwner(id, false);
        WatchBefore watched = watchBefore(id);
        invalidateNear(id);
        long startedAt = System.nanoTime();
        try {
            producerGuard.applyBackpressure();
            String redisKey = keyStrategy.entityKey(metadata.redisNamespace(), id);
            String versionKey = keyStrategy.versionKey(metadata.redisNamespace(), id);
            String tombstoneKey = keyStrategy.tombstoneKey(metadata.redisNamespace(), id);
            String compactionPayloadKey =
                    keyStrategy.compactionPayloadKey(metadata.redisNamespace(), id);
            String compactionPendingKey =
                    keyStrategy.compactionPendingKey(metadata.redisNamespace(), id);
            String compactionStatsKey = keyStrategy.compactionStatsKey(metadata.redisNamespace());
            String targetCompactionStreamKey =
                    keyStrategy.compactionStreamKey(
                            compactionStreamKey,
                            metadata.redisNamespace(),
                            id,
                            compactionShardCount);
            WriteOperation<T, ID> operation =
                    new WriteOperation<>(
                            OperationType.DELETE,
                            metadata,
                            id,
                            Map.of(metadata.idColumn(), id),
                            "",
                            0L,
                            Instant.now());

            long version;
            if (functionExecutor != null && functionExecutor.enabled()) {
                version =
                        functionExecutor.delete(
                                redisKey,
                                versionKey,
                                tombstoneKey,
                                streamKey,
                                compactionPayloadKey,
                                compactionPendingKey,
                                targetCompactionStreamKey,
                                compactionStatsKey,
                                operation, effectiveCachePolicy(), watched == null ? -1 : watched.version());
                pageCacheManager.removeEntity(id);
            } else {
                version = jedis.commands().incr(versionKey);
                writeTombstoneKey(tombstoneKey, version);
                pageCacheManager.removeEntity(id);
                writeBehindQueue.enqueue(
                        new WriteOperation<>(
                                operation.type(),
                                metadata,
                                id,
                                enrichColumnsForPersistence(operation.columns(), version, true),
                                "",
                                version,
                                operation.createdAt()));
            }
            deleteProjectionPayloads(id, false);
            if (watched != null) notifyWatchers(id, watched.payload(), null);
            return new WriteReceipt<>(
                    null,
                    id,
                    metadata.redisNamespace(),
                    OperationType.DELETE,
                    version,
                    operation.createdAt());
        } finally {
            if (metadata.tenantPurgeEnabled()) invalidateNear(id);
            recordRedisWrite(startedAt);
        }
    }

    @Override
    public EntityRepository<T, ID> withFetchPlan(FetchPlan nextFetchPlan) {
        return new RedisEntityRepository<>(
                jedis,
                metadata,
                codec,
                writeBehindQueue,
                keyStrategy,
                nextFetchPlan,
                cachePolicy,
                functionExecutor,
                producerGuard,
                guardrailConfig,
                streamKey,
                compactionStreamKey,
                compactionShardCount,
                entityRegistry,
                relationBatchLoader,
                relationConfig,
                queryIndexConfig,
                pageLoader,
                byIdLoader,
                queryLoader,
                pageCacheConfig,
                readThroughConfig,
                readShapeGuardrailConfig,
                pageCacheManager,
                queryEvaluator,
                queryIndexManager,
                performanceCollector,
                backgroundJedis,
                projectionRefreshDispatcher,
                projectionRefreshQueue).withMutationConfig(mutationConfig).withNearCache(nearCacheCoordinator).withReadRuntime(pointReads).withAsyncExecutor(asyncGate).withReadFlights(readFlights).withWatchState(watchState).withVerifiedOwners(verifiedOwners).withObservationState(this).withTenantReadBridgeState(this);
    }

    @Override
    public <P> ProjectionRepository<P, ID> projected(EntityProjection<T, P, ID> projection) {
        if (metadata.tenantPurgeEnabled()) return protectedPointReads().projected(activeTenant(), projection);
        activeTenant();
        if (projection == null) throw new IllegalStateException("An explicit projection contract is required");
        if (entityRegistry == null) {
            throw new IllegalStateException("Projection repositories require an EntityRegistry");
        }
        EntityProjectionBinding<T, P, ID> binding =
                entityRegistry.registerProjection(metadata, projection);
        ProjectionSupport<T, ID, P> support = projectionSupport(binding);
        return new RedisProjectionRepository<T, ID, P>(
                support.readRuntime(),
                support.refreshRuntime(),
                queryEvaluator,
                rawId -> loadProjectionFromBaseEntityPayload(rawId, projection),
                rawIds -> loadProjectionMapFromBaseEntityPayload(rawIds, projection),
                querySpec -> projectQueryFromBaseEntities(querySpec, projection),
                performanceCollector,
                readShapeGuardrailConfig).withTenantScope(metadata, this::projectionOwners)
                .withObservationRecorder(observationRecorder, observationEnabled, observationOwner);
    }

    private Map<String, String> projectionOwners(Collection<String> ids) {
        var result = new LinkedHashMap<String, String>();
        var names = new ArrayList<>(ids);
        for (int start = 0; start < names.size(); start += 128) {
            var batch = names.subList(start, Math.min(names.size(), start + 128));
            var ownerKeys = batch.stream().map(id -> keyStrategy.versionKey(metadata.redisNamespace(), id) + ":tenant").toArray(String[]::new);
            var owners = jedis.mget(ownerKeys);
            for (int i = 0; i < batch.size(); i++) if (owners.get(i) != null) result.put(batch.get(i), owners.get(i));
        }
        return result;
    }

    public FetchPlan fetchPlan() {
        return fetchPlan;
    }

    public CachePolicy cachePolicy() {
        return effectiveCachePolicy();
    }

    private void requirePositiveHydrationVersion(long version) {
        if (version <= 0L) {
            throw new IllegalArgumentException(
                    "Hydration for "
                            + metadata.entityName()
                            + " requires a source version greater than zero");
        }
    }

    private long parsePositiveVersion(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void writeTombstoneKey(String tombstoneKey, long version) {
        if (guardrailConfig.tombstoneTtlSeconds() > 0) {
            jedis.commands()
                    .setex(
                            tombstoneKey,
                            guardrailConfig.tombstoneTtlSeconds(),
                            String.valueOf(version));
            return;
        }
        jedis.commands().set(tombstoneKey, String.valueOf(version));
    }

    private CachePolicy effectiveCachePolicy() {
        return producerGuard == null
                ? cachePolicy
                : producerGuard.effectiveCachePolicy(cachePolicy);
    }

    private void evictCacheOnly(Object id) {
        @SuppressWarnings("unchecked")
        ID typedId = (ID) id;
        pageCacheManager.removeEntity(typedId);
        deleteProjectionPayloads(id, true);
    }

    private String normalizeScanCursor(String cursor) {
        return cursor == null || cursor.isBlank() ? "0" : cursor.trim();
    }

    private boolean shouldCacheEntity(
            ID id,
            Map<String, Object> columns,
            CacheAdmissionSource source,
            CachePolicy effectiveCachePolicy,
            long estimatedPayloadBytes) {
        boolean admitted =
                effectiveCachePolicy.shouldAdmit(columns, source)
                        && pageCacheManager.allowTenantQuota(id, columns, estimatedPayloadBytes);
        recordCacheAdmission(source, admitted);
        return admitted;
    }

    private long estimatePayloadBytes(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return 1L;
        }
        return com.reactor.cachedb.core.codec.Utf8Length.of(encoded) + 128L;
    }

    private void recordCacheAdmission(CacheAdmissionSource source, boolean admitted) {
        if (performanceCollector == null) {
            return;
        }
        String routeTag = PerformanceObservationContext.currentTag();
        String tag =
                routeTag.isBlank()
                        ? "entity:"
                                + metadata.entityName()
                                + ":"
                                + (source == null ? CacheAdmissionSource.READ : source).name()
                        : "route:"
                                + routeTag
                                + ":"
                                + (source == null ? CacheAdmissionSource.READ : source).name();
        performanceCollector.recordCacheAdmission(tag, admitted);
    }

    private void recordReadThroughEvent(String event, boolean success) {
        if (performanceCollector == null) {
            return;
        }
        String routeTag = PerformanceObservationContext.currentTag();
        String tag =
                routeTag.isBlank()
                        ? "read-through:entity:" + metadata.entityName() + ":" + event
                        : "read-through:route:" + routeTag + ":" + event;
        performanceCollector.recordCacheAdmission(tag, success);
    }

    private Map<String, Object> enrichColumnsForPersistence(
            Map<String, Object> source, long version, boolean deleting) {
        LinkedHashMap<String, Object> enriched = new LinkedHashMap<>(source);
        enriched.put(metadata.versionColumn(), version);
        if (metadata.deletedColumn() != null) {
            enriched.put(
                    metadata.deletedColumn(),
                    deleting ? metadata.deletedMarkerValue() : metadata.activeMarkerValue());
        }
        return enriched;
    }

    private void applyFetchPlan(List<T> entities) {
        applyFetchPlan(entities, fetchPlan);
    }

    private void applyFetchPlan(List<T> entities, FetchPlan effectiveFetchPlan) {
        if (entities.isEmpty() || effectiveFetchPlan.includes().isEmpty()) {
            return;
        }
        if (effectiveFetchPlan.exceedsDepth(relationConfig.maxFetchDepth())) {
            throw new IllegalArgumentException(
                    "FetchPlan depth "
                            + effectiveFetchPlan.maxDepth()
                            + " exceeds configured maxFetchDepth="
                            + relationConfig.maxFetchDepth()
                            + " for "
                            + metadata.entityName());
        }
        RelationBatchLoader<T> effectiveRelationBatchLoader = currentRelationBatchLoader();
        if (relationConfig.failOnMissingPreloader()
                && effectiveRelationBatchLoader instanceof NoOpRelationBatchLoader) {
            throw new IllegalStateException(
                    "FetchPlan requested relations but no RelationBatchLoader is registered for "
                            + metadata.entityName());
        }
        effectiveRelationBatchLoader.preload(
                entities, new RelationBatchContext(effectiveFetchPlan, relationConfig));
    }

    @SuppressWarnings("unchecked")
    private RelationBatchLoader<T> currentRelationBatchLoader() {
        if (entityRegistry == null) {
            return relationBatchLoader;
        }
        EntityBinding<?, ?> binding = entityRegistry.find(metadata.entityName()).orElse(null);
        if (binding == null || binding.relationBatchLoader() == null) {
            return relationBatchLoader;
        }
        return (RelationBatchLoader<T>) binding.relationBatchLoader();
    }

    @SuppressWarnings("unchecked")
    private EntityPageLoader<T> currentPageLoader() {
        if (entityRegistry == null) {
            return pageLoader;
        }
        EntityBinding<?, ?> binding = entityRegistry.find(metadata.entityName()).orElse(null);
        if (binding == null || binding.pageLoader() == null) {
            return pageLoader;
        }
        return (EntityPageLoader<T>) binding.pageLoader();
    }

    @SuppressWarnings("unchecked")
    private EntityByIdLoader<T, ID> currentByIdLoader() {
        if (entityRegistry == null) {
            return byIdLoader;
        }
        EntityBinding<?, ?> binding = entityRegistry.find(metadata.entityName()).orElse(null);
        if (binding == null || binding.byIdLoader() == null) {
            return byIdLoader;
        }
        return (EntityByIdLoader<T, ID>) binding.byIdLoader();
    }

    @SuppressWarnings("unchecked")
    private EntityQueryLoader<T> currentQueryLoader() {
        if (entityRegistry == null) {
            return queryLoader;
        }
        EntityBinding<?, ?> binding = entityRegistry.find(metadata.entityName()).orElse(null);
        if (binding == null || binding.queryLoader() == null) {
            return queryLoader;
        }
        return (EntityQueryLoader<T>) binding.queryLoader();
    }

    private Optional<T> loadByIdFromSource(ID id) {
        if (!readThroughConfig.mode().byIdEnabled() || !readThroughConfig.singleFlight().enabled())
            return loadByIdUncoalesced(id);
        String encoded = (String) readFlights.load(
                new ReadFlightKey("id", id, RouteCacheContext.currentContract()), () -> {
                    var values = jedis.mget(keyStrategy.entityKey(metadata.redisNamespace(), id),
                            keyStrategy.tombstoneKey(metadata.redisNamespace(), id));
                    if (values.get(1) != null) return null;
                    if (values.get(0) != null) return values.get(0);
                    return loadByIdUncoalesced(id).map(codec::toRedisValue).orElse(null);
                });
        return encoded == null ? Optional.empty() : Optional.of(codec.fromRedisValue(encoded));
    }

    private Optional<T> loadByIdUncoalesced(ID id) {
        if (!readThroughConfig.mode().byIdEnabled()) {
            recordReadThroughEvent("by-id-disabled", false);
            return Optional.empty();
        }
        EntityByIdLoader<T, ID> effectiveByIdLoader = currentByIdLoader();
        if (effectiveByIdLoader instanceof NoOpEntityByIdLoader) {
            if (readThroughConfig.failOnMissingLoader()) {
                throw new IllegalStateException(
                        "Redis miss for "
                                + metadata.entityName()
                                + " id="
                                + id
                                + " but no EntityByIdLoader is registered");
            }
            recordReadThroughEvent("by-id-loader-missing", false);
            return Optional.empty();
        }
        observationTier(Tier.SOURCE);
        VersionedEntitySourceLoader<T, ID> versionedLoader =
                versionedSourceLoader(effectiveByIdLoader);
        Optional<VersionedEntity<T>> versioned =
                versionedLoader == null
                        ? Optional.empty()
                        : Optional.ofNullable(versionedLoader.loadVersionedById(id))
                                .orElse(Optional.empty());
        Optional<T> loaded =
                versionedLoader == null
                        ? Optional.ofNullable(effectiveByIdLoader.load(id)).orElse(Optional.empty())
                        : versioned.map(VersionedEntity::entity);
        if (loaded.isEmpty()) {
            recordReadThroughEvent("by-id-source-empty", false);
            return Optional.empty();
        }
        if (shouldHydrateReadThrough()) {
            if (versioned.isPresent()) {
                hydrateInternal(
                        loaded.get(),
                        versioned.get().version(),
                        true,
                        true,
                        true,
                        CacheAdmissionSource.READ);
            } else {
                recordReadThroughEvent("by-id-version-unavailable", false);
            }
        }
        recordReadThroughEvent("by-id-loaded", true);
        return loaded;
    }

    private List<T> loadQueryFromSource(QuerySpec querySpec) {
        if (!readThroughConfig.mode().queryEnabled() || !readThroughConfig.singleFlight().enabled())
            return loadQueryUncoalesced(querySpec);
        var selection = new QueryFlightSelection(querySpec.rootGroup(), querySpec.sorts(), querySpec.offset(),
                querySpec.limit(), querySpec.queryTimeoutSeconds(), querySpec.fetchPlan().includes(),
                querySpec.fetchPlan().relationLimits());
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) readFlights.load(
                new ReadFlightKey("query", selection, RouteCacheContext.currentContract()),
                () -> encodeFlightRows(loadQueryUncoalesced(querySpec)));
        return decodeFlightRows(values);
    }

    private List<String> encodeFlightRows(List<T> rows) {
        ArrayList<String> values = new ArrayList<>(rows.size());
        long characters = 0;
        for (T entity : rows) {
            String value = codec.toRedisValue(entity);
            characters += value.length();
            if (characters > readThroughConfig.singleFlight().maxResultCharacters())
                throw new IllegalArgumentException("Read-through flight exceeds maxResultCharacters="
                        + readThroughConfig.singleFlight().maxResultCharacters());
            values.add(value);
        }
        return List.copyOf(values);
    }

    private List<T> decodeFlightRows(List<String> values) {
        ArrayList<T> rows = new ArrayList<>(values.size());
        for (String value : values) rows.add(codec.fromRedisValue(value));
        return rows;
    }

    private record ReadFlightKey(String kind, Object selection, RouteCacheContract contract) {}
    private record QueryFlightSelection(com.reactor.cachedb.core.query.QueryGroup root,
            List<QuerySort> sorts, int offset, int limit, int timeout, java.util.Set<String> includes,
            Map<String, Integer> relationLimits) {}

    private List<T> loadQueryUncoalesced(QuerySpec querySpec) {
        RouteCacheContract contract = RouteCacheContext.currentContract();
        if (contract != null && !contract.sourceFallbackAllowed()) {
            recordReadThroughEvent("query-route-hot-only", false);
            return List.of();
        }
        if (!readThroughConfig.mode().queryEnabled()) {
            recordReadThroughEvent("query-disabled", false);
            return List.of();
        }
        if (querySpec.limit() > readThroughConfig.maxQueryLoadRows()) {
            throw new IllegalArgumentException(
                    "Read-through query for "
                            + metadata.entityName()
                            + " requested "
                            + querySpec.limit()
                            + " rows but maxQueryLoadRows="
                            + readThroughConfig.maxQueryLoadRows());
        }
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.validateSourceQuery(metadata, querySpec);
        EntityQueryLoader<T> effectiveQueryLoader = currentQueryLoader();
        if (effectiveQueryLoader instanceof NoOpEntityQueryLoader) {
            if (readThroughConfig.failOnMissingLoader()) {
                throw new IllegalStateException(
                        "Redis query miss for "
                                + metadata.entityName()
                                + " but no EntityQueryLoader is registered");
            }
            recordReadThroughEvent("query-loader-missing", false);
            return List.of();
        }
        observationTier(Tier.SOURCE);
        VersionedEntitySourceLoader<T, ID> versionedLoader =
                versionedSourceLoader(effectiveQueryLoader);
        List<VersionedEntity<T>> versioned =
                versionedLoader == null ? List.of() : versionedLoader.loadVersionedQuery(querySpec);
        List<T> loaded =
                versionedLoader == null
                        ? effectiveQueryLoader.load(querySpec)
                        : entities(versioned);
        if (loaded == null || loaded.isEmpty()) {
            recordReadThroughEvent("query-source-empty", false);
            return List.of();
        }
        if (loaded.size() > readThroughConfig.maxQueryLoadRows()) {
            throw new IllegalArgumentException(
                    "Read-through query loader for "
                            + metadata.entityName()
                            + " returned "
                            + loaded.size()
                            + " rows but maxQueryLoadRows="
                            + readThroughConfig.maxQueryLoadRows());
        }
        if (shouldHydrateReadThrough()) {
            if (versionedLoader == null) {
                recordReadThroughEvent("query-version-unavailable", false);
            } else {
                hydrateWarmBatchInternal(loaded, versions(versioned), true, true);
            }
        }
        recordReadThroughEvent("query-loaded", true);
        return List.copyOf(loaded);
    }

    private boolean shouldFailOnMissingPageLoader() {
        return pageCacheConfig.failOnMissingPageLoader()
                || (readThroughConfig.mode().pageEnabled()
                        && readThroughConfig.failOnMissingLoader());
    }

    private boolean shouldHydrateReadThrough() {
        return readThroughConfig.hydrateLoadedEntities()
                && (producerGuard == null
                        || !producerGuard.shouldShedReadThroughCache(metadata.redisNamespace()));
    }

    @SuppressWarnings("unchecked")
    private VersionedEntitySourceLoader<T, ID> versionedSourceLoader(Object loader) {
        return loader instanceof VersionedEntitySourceLoader<?, ?> versioned
                ? (VersionedEntitySourceLoader<T, ID>) versioned
                : null;
    }

    private List<T> entities(List<VersionedEntity<T>> versioned) {
        return versioned.stream().map(VersionedEntity::entity).toList();
    }

    private List<Long> versions(List<VersionedEntity<T>> versioned) {
        return versioned.stream().map(VersionedEntity::version).toList();
    }

    private void rejectProjectionOnlyEntityRoute(String shape) {
        if (!readThroughConfig.mode().projectionOnly()) {
            return;
        }
        throw new IllegalStateException(
                shape
                        + " for "
                        + metadata.entityName()
                        + " is disabled because readThrough.mode=PROJECTION_ONLY; use"
                        + " ProjectionRepository or an explicit SQL route");
    }

    private List<String> mgetEntityAndTombstones(
            List<String> entityKeys, List<String> tombstoneKeys) {
        int entityCount = entityKeys.size();
        String[] lookupKeys = new String[entityCount + tombstoneKeys.size()];
        for (int index = 0; index < entityCount; index++) {
            lookupKeys[index] = entityKeys.get(index);
            lookupKeys[entityCount + index] = tombstoneKeys.get(index);
        }
        return jedis.mget(lookupKeys);
    }

    private void syncProjectionPayloads(T entity, boolean forceImmediateRefresh) {
        if (entityRegistry == null) {
            return;
        }
        Collection<com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?>> bindings =
                entityRegistry.projections(metadata.entityName());
        if (bindings.isEmpty()) {
            return;
        }
        for (com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding :
                bindings) {
            syncProjectionPayload(rawBinding, entity, forceImmediateRefresh);
        }
    }

    @Override
    public void hydrateExternalDelete(ID id, long version) {
        verifyProtectedWriteOwner(id, activeTenant());
        if (!queryIndexManager.hasCompositeSortIndexes()) { hydrateExternalDeleteUnfenced(id, version); return; }
        queryIndexManager.withCompositeMutation(() -> { hydrateExternalDeleteUnfenced(id, version); return null; });
    }

    private void hydrateExternalDeleteUnfenced(ID id, long version) {
        ensureTenantOwner(id, false);
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        long startedAt = System.nanoTime();
        try {
            requirePositiveHydrationVersion(version);
            producerGuard.applyBackpressure();
            String entityKey = keyStrategy.entityKey(metadata.redisNamespace(), id);
            String versionKey = keyStrategy.versionKey(metadata.redisNamespace(), id);
            String tombstoneKey = keyStrategy.tombstoneKey(metadata.redisNamespace(), id);
            boolean hydrated =
                    versionedHydrator.delete(
                            entityKey,
                            versionKey,
                            tombstoneKey,
                            version,
                            guardrailConfig.tombstoneTtlSeconds());
            if (!hydrated) {
                return;
            }
            pageCacheManager.removeEntity(id);
            deleteProjectionPayloads(id, false);
        } finally {
            recordRedisWrite(startedAt);
        }
    }

    private void syncProjectionPayloadsBatch(List<T> entities, boolean forceImmediateRefresh) {
        if (entities == null || entities.isEmpty() || entityRegistry == null) {
            return;
        }
        Collection<com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?>> bindings =
                entityRegistry.projections(metadata.entityName());
        if (bindings.isEmpty()) {
            return;
        }
        for (com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding :
                bindings) {
            syncProjectionPayloadBatch(rawBinding, entities, forceImmediateRefresh);
        }
    }

    private void enqueueProjectionRefreshBatch(List<T> entities, Collection<ID> ids) {
        if (ids == null
                || ids.isEmpty()
                || entities == null
                || entities.isEmpty()
                || entityRegistry == null) {
            return;
        }
        Collection<com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?>> bindings =
                entityRegistry.projections(metadata.entityName());
        if (bindings.isEmpty()) {
            return;
        }
        ArrayList<String> asyncProjectionNames = new ArrayList<>();
        ArrayList<com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?>>
                synchronousBindings = new ArrayList<>();
        for (com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding :
                bindings) {
            if (rawBinding.projection().refreshMode().isAsync()) {
                asyncProjectionNames.add(rawBinding.projection().name());
            } else {
                synchronousBindings.add(rawBinding);
            }
        }
        if (!asyncProjectionNames.isEmpty() && projectionRefreshQueue != null) {
            projectionRefreshQueue.enqueueUpsertBatch(
                    metadata.entityName(), asyncProjectionNames, ids);
        }
        if (synchronousBindings.isEmpty()) {
            return;
        }
        for (T entity : entities) {
            for (com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding :
                    synchronousBindings) {
                syncProjectionPayload(rawBinding, entity, false);
            }
        }
    }

    private void deleteProjectionPayloads(Object id, boolean forceImmediateRefresh) {
        if (entityRegistry == null) {
            return;
        }
        Collection<com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?>> bindings =
                entityRegistry.projections(metadata.entityName());
        if (bindings.isEmpty()) {
            return;
        }
        for (com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding :
                bindings) {
            deleteProjectionPayload(rawBinding, id, forceImmediateRefresh);
        }
    }

    @SuppressWarnings("unchecked")
    private <P> void syncProjectionPayload(
            com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding,
            T entity,
            boolean forceImmediateRefresh) {
        EntityProjectionBinding<T, P, ID> binding = (EntityProjectionBinding<T, P, ID>) rawBinding;
        if (!forceImmediateRefresh
                && binding.projection().refreshMode().isAsync()
                && projectionRefreshQueue != null) {
            projectionRefreshQueue.enqueueUpsert(
                    metadata.entityName(),
                    binding.projection().name(),
                    metadata.idAccessor().apply(entity));
            return;
        }
        ProjectionSupport<T, ID, P> support = projectionSupport(binding);
        P projection = binding.projection().projector().apply(entity);
        if (projection == null) {
            applyProjectionDelete(
                    support, metadata.idAccessor().apply(entity), forceImmediateRefresh);
            return;
        }
        applyProjectionUpsert(support, projection, forceImmediateRefresh);
    }

    @SuppressWarnings("unchecked")
    private <P> void syncProjectionPayloadBatch(
            com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding,
            List<T> entities,
            boolean forceImmediateRefresh) {
        EntityProjectionBinding<T, P, ID> binding = (EntityProjectionBinding<T, P, ID>) rawBinding;
        if (!forceImmediateRefresh
                && binding.projection().refreshMode().isAsync()
                && projectionRefreshQueue != null) {
            ArrayList<ID> ids = new ArrayList<>(entities.size());
            for (T entity : entities) {
                ids.add(metadata.idAccessor().apply(entity));
            }
            projectionRefreshQueue.enqueueUpsertBatch(
                    metadata.entityName(), List.of(binding.projection().name()), ids);
            return;
        }
        ProjectionSupport<T, ID, P> support = projectionSupport(binding);
        ArrayList<P> projections = new ArrayList<>(entities.size());
        ArrayList<ID> deleteIds = new ArrayList<>();
        for (T entity : entities) {
            ID id = metadata.idAccessor().apply(entity);
            P projection = binding.projection().projector().apply(entity);
            if (projection == null) {
                deleteIds.add(id);
            } else {
                projections.add(projection);
            }
        }
        applyProjectionUpsertBatch(support, projections, forceImmediateRefresh);
        for (ID deleteId : deleteIds) {
            applyProjectionDelete(support, deleteId, forceImmediateRefresh);
        }
    }

    @SuppressWarnings("unchecked")
    private <P> void deleteProjectionPayload(
            com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding,
            Object rawId,
            boolean forceImmediateRefresh) {
        EntityProjectionBinding<T, P, ID> binding = (EntityProjectionBinding<T, P, ID>) rawBinding;
        if (!forceImmediateRefresh
                && binding.projection().refreshMode().isAsync()
                && projectionRefreshQueue != null) {
            projectionRefreshQueue.enqueueDelete(
                    metadata.entityName(), binding.projection().name(), rawId);
            return;
        }
        applyProjectionDelete(projectionSupport(binding), (ID) rawId, forceImmediateRefresh);
    }

    private <P> void applyProjectionUpsert(
            ProjectionSupport<T, ID, P> support, P projection, boolean forceImmediateRefresh) {
        if (projection == null) {
            return;
        }
        if (!forceImmediateRefresh && support.binding().projection().refreshMode().isAsync()) {
            throw new IllegalStateException(
                    "Async projection "
                            + support.binding().projection().name()
                            + " requires the durable projection refresh queue");
        }
        safeProjectionUpsert(
                forceImmediateRefresh ? support.refreshRuntime() : support.readRuntime(),
                projection);
    }

    private <P> void applyProjectionUpsertBatch(
            ProjectionSupport<T, ID, P> support,
            Collection<P> projections,
            boolean forceImmediateRefresh) {
        if (projections == null || projections.isEmpty()) {
            return;
        }
        if (!forceImmediateRefresh && support.binding().projection().refreshMode().isAsync()) {
            throw new IllegalStateException(
                    "Async projection "
                            + support.binding().projection().name()
                            + " requires the durable projection refresh queue");
        }
        safeProjectionUpsertBatch(
                forceImmediateRefresh ? support.refreshRuntime() : support.readRuntime(),
                projections);
    }

    private <P> void applyProjectionDelete(
            ProjectionSupport<T, ID, P> support, ID id, boolean forceImmediateRefresh) {
        if (id == null) {
            return;
        }
        if (!forceImmediateRefresh && support.binding().projection().refreshMode().isAsync()) {
            throw new IllegalStateException(
                    "Async projection "
                            + support.binding().projection().name()
                            + " requires the durable projection refresh queue");
        }
        safeProjectionDelete(
                forceImmediateRefresh ? support.refreshRuntime() : support.readRuntime(), id);
    }

    private <P> void safeProjectionUpsert(RedisProjectionRuntime<P, ID> runtime, P projection) {
        runtime.upsert(projection);
    }

    private <P> void safeProjectionUpsertBatch(
            RedisProjectionRuntime<P, ID> runtime, Collection<P> projections) {
        runtime.upsertBatch(projections);
    }

    private <P> void safeProjectionWarmUpsertBatch(
            RedisProjectionRuntime<P, ID> runtime, Collection<P> projections) {
        runtime.upsertWarmBatch(projections);
    }

    private <P> void safeProjectionDelete(RedisProjectionRuntime<P, ID> runtime, ID id) {
        runtime.delete(id);
    }

    @SuppressWarnings("unchecked")
    private <P> ProjectionSupport<T, ID, P> projectionSupport(
            EntityProjectionBinding<T, P, ID> binding) {
        return (ProjectionSupport<T, ID, P>)
                projectionSupportCache.computeIfAbsent(
                        binding.projection().name(), ignored -> createProjectionSupport(binding));
    }

    private <P> ProjectionSupport<T, ID, P> createProjectionSupport(
            EntityProjectionBinding<T, P, ID> binding) {
        ProjectionEntityMetadata<P, ID> projectionMetadata =
                new ProjectionEntityMetadata<>(metadata, binding.projection());
        ProjectionEntityCodec<P, ID> projectionCodec =
                new ProjectionEntityCodec<>(binding.projection());
        QueryIndexConfig projectionQueryIndexConfig = projectionQueryIndexConfig();
        RedisQueryIndexManager<P, ID> foregroundProjectionQueryIndexManager =
                new RedisQueryIndexManager<>(
                        jedis,
                        projectionMetadata,
                        projectionCodec,
                        keyStrategy,
                        entityRegistry,
                        projectionQueryIndexConfig,
                        relationConfig,
                        queryEvaluator,
                        producerGuard);
        RedisProjectionRuntime<P, ID> readRuntime =
                new RedisProjectionRuntime<>(
                        jedis,
                        projectionMetadata,
                        projectionCodec,
                        keyStrategy,
                        cachePolicy,
                        foregroundProjectionQueryIndexManager,
                        binding);
        RedisProjectionRuntime<P, ID> refreshRuntime = readRuntime;
        if (backgroundJedis != null && backgroundJedis != jedis) {
            RedisQueryIndexManager<P, ID> backgroundProjectionQueryIndexManager =
                    new RedisQueryIndexManager<>(
                            backgroundJedis,
                            projectionMetadata,
                            projectionCodec,
                            keyStrategy,
                            entityRegistry,
                            projectionQueryIndexConfig,
                            relationConfig,
                            queryEvaluator,
                            producerGuard);
            refreshRuntime =
                    new RedisProjectionRuntime<>(
                            backgroundJedis,
                            projectionMetadata,
                            projectionCodec,
                            keyStrategy,
                            cachePolicy,
                            backgroundProjectionQueryIndexManager,
                            binding);
        }
        readRuntime.withAsyncQueue(projectionRefreshQueue);
        refreshRuntime.withAsyncQueue(projectionRefreshQueue);
        return new ProjectionSupport<>(binding, readRuntime, refreshRuntime);
    }

    private QueryIndexConfig projectionQueryIndexConfig() {
        return QueryIndexConfig.builder()
                .exactIndexEnabled(queryIndexConfig.exactIndexEnabled())
                .rangeIndexEnabled(queryIndexConfig.rangeIndexEnabled())
                .prefixIndexEnabled(queryIndexConfig.prefixIndexEnabled())
                .textIndexEnabled(queryIndexConfig.textIndexEnabled())
                .plannerStatisticsEnabled(false)
                .plannerStatisticsPersisted(false)
                .plannerStatisticsTtlMillis(queryIndexConfig.plannerStatisticsTtlMillis())
                .plannerStatisticsSampleSize(queryIndexConfig.plannerStatisticsSampleSize())
                .learnedStatisticsEnabled(false)
                .learnedStatisticsWeight(queryIndexConfig.learnedStatisticsWeight())
                .cacheWarmingEnabled(false)
                .rangeHistogramBuckets(queryIndexConfig.rangeHistogramBuckets())
                .prefixMaxLength(queryIndexConfig.prefixMaxLength())
                .textTokenMinLength(queryIndexConfig.textTokenMinLength())
                .textTokenMaxLength(queryIndexConfig.textTokenMaxLength())
                .textMaxTokensPerValue(queryIndexConfig.textMaxTokensPerValue())
                .maxMaterializedCandidateIds(queryIndexConfig.maxMaterializedCandidateIds())
                .build();
    }

    private <P> Optional<P> loadProjectionFromBaseEntityPayload(
            String rawId, EntityProjection<T, P, ID> projection) {
        List<String> values =
                jedis.mget(
                        keyStrategy.entityKey(metadata.redisNamespace(), rawId),
                        keyStrategy.tombstoneKey(metadata.redisNamespace(), rawId));
        if (values.get(1) != null || values.get(0) == null) {
            return Optional.empty();
        }
        T entity = codec.fromRedisValue(values.get(0));
        return Optional.ofNullable(projection.projector().apply(entity));
    }

    private <P> Map<String, P> loadProjectionMapFromBaseEntityPayload(
            Collection<String> rawIds, EntityProjection<T, P, ID> projection) {
        if (rawIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = new ArrayList<>(rawIds);
        List<String> entityKeys =
                ids.stream()
                        .map(id -> keyStrategy.entityKey(metadata.redisNamespace(), id))
                        .toList();
        List<String> tombstoneKeys =
                ids.stream()
                        .map(id -> keyStrategy.tombstoneKey(metadata.redisNamespace(), id))
                        .toList();
        List<String> values = mgetEntityAndTombstones(entityKeys, tombstoneKeys);
        LinkedHashMap<String, P> projections = new LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            if (values.get(ids.size() + index) != null || values.get(index) == null) {
                continue;
            }
            T entity = codec.fromRedisValue(values.get(index));
            P projected = projection.projector().apply(entity);
            if (projected != null) {
                projections.put(ids.get(index), projected);
            }
        }
        return projections;
    }

    private <P> List<P> projectQueryFromBaseEntities(
            QuerySpec querySpec, EntityProjection<T, P, ID> projection) {
        List<T> entities = query(querySpec);
        ArrayList<P> projections = new ArrayList<>(entities.size());
        for (T entity : entities) {
            P projected = projection.projector().apply(entity);
            if (projected != null) {
                projections.add(projected);
            }
        }
        return List.copyOf(projections);
    }

    private int compareBySorts(
            Map<String, Object> leftColumns,
            Map<String, Object> rightColumns,
            List<QuerySort> sorts) {
        for (QuerySort sort : sorts) {
            int result = queryEvaluator.comparator(sort).compare(leftColumns, rightColumns);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private int compareEntitiesBySorts(T left, T right, List<QuerySort> sorts) {
        for (QuerySort sort : sorts) {
            int result = queryEvaluator.compareColumns(left, right, codec, sort);
            if (result != 0) return result;
        }
        return 0;
    }

    private void recordRedisRead(long startedAtNanos) {
        if (performanceCollector == null) {
            return;
        }
        performanceCollector.recordRedisRead((System.nanoTime() - startedAtNanos) / 1_000L);
    }

    private void recordRedisWrite(long startedAtNanos) {
        if (performanceCollector == null) {
            return;
        }
        performanceCollector.recordRedisWrite((System.nanoTime() - startedAtNanos) / 1_000L);
    }

    private record ProjectionSupport<T, ID, P>(
            EntityProjectionBinding<T, P, ID> binding,
            RedisProjectionRuntime<P, ID> readRuntime,
            RedisProjectionRuntime<P, ID> refreshRuntime) {}
}

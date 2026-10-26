package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.api.ProjectionRepository;
import com.reactor.cachedb.core.api.RepositoryCapabilities;
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
        implements EntityRepository<T, ID>, ExternalChangeHydrationRepository<T, ID> {
    private static final RepositoryCapabilities CAPABILITIES = RepositoryCapabilities.all();

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
    private static final class WatchState<T, ID> {
        volatile RedisWatchRegistry<T, ID> registry;
        int limit = 64;
    }
    private WatchState<T, ID> watchState = new WatchState<>();
    private RedisEntityRepository<T, ID> withWatchState(WatchState<T, ID> state) { watchState = state; return this; }

    public RedisEntityRepository<T, ID> withWatcherLimit(int limit) {
        if (limit < 1 || limit > 64 || watchState.registry != null) throw new IllegalArgumentException("Invalid watcher limit or late configuration");
        watchState.limit = limit; return this;
    }
    @Override public synchronized com.reactor.cachedb.core.watch.CacheWatcherRegistration registerWatcher(QuerySpec spec,
            com.reactor.cachedb.core.watch.CacheWatcherListener<T, ID> listener) {
        if (metadata.tenantFieldName() != null && activeTenant() == null)
            throw new IllegalStateException("Tenant watcher requires an explicit tenant context");
        synchronized (watchState) {
            if (watchState.registry == null) watchState.registry = new RedisWatchRegistry<>(metadata, codec, queryEvaluator, watchState.limit);
            return watchState.registry.register(tenantQuery(spec), listener);
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
            throw new com.reactor.cachedb.core.repository.HotUpdateUnavailableException(id);
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

    private boolean nearCacheActive() {
        var route = RouteCacheContext.currentContract();
        return nearCache != null && nearCacheCoordinator.connected()
                && (route == null || !route.tenantQuota().bounded());
    }

    private void invalidateNear(ID id) { if (nearCache != null) nearCache.invalidate(String.valueOf(id)); }
    @SuppressWarnings("unchecked")
    private T decodeNear(String id, String payload) {
        if (metadata.deeplyImmutable() && fetchPlan.includes().isEmpty()) {
            Object cached = nearCache.getDecoded(id, payload);
            if (cached != null) return (T) cached;
        }
        return codec.fromRedisValue(payload);
    }

    private Object shareableNearEntity(T entity, String payload) {
        return metadata.deeplyImmutable() && metadata.schemaVersion() == 1 && fetchPlan.includes().isEmpty()
                && !payload.startsWith("~Z:") && !payload.startsWith("~v") ? entity : null;
    }

    private String activeTenant() {
        return com.reactor.cachedb.core.tenant.TenantAccess.active(metadata);
    }

    private void requireTenantMaintenanceScope() {
        if (metadata.tenantFieldName() != null
                && !com.reactor.cachedb.core.tenant.CacheTenantContext.isAdministrative())
            throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException(metadata.entityName());
    }

    private void verifyTenant(T entity) {
        com.reactor.cachedb.core.tenant.TenantAccess.verify(metadata, entity);
    }

    private QuerySpec tenantQuery(QuerySpec query) {
        return com.reactor.cachedb.core.tenant.TenantAccess.scoped(metadata, query);
    }

    private void ensureTenantOwner(ID id, boolean create) {
        verifyTenantOwner(id, create, true);
    }

    private boolean verifyTenantOwner(ID id, boolean create, boolean initialize) {
        String tenant = activeTenant();
        if (tenant == null) return true;
        if (!functionExecutor.enabled()) throw new IllegalStateException("Tenant writes require atomic Redis Functions");
        String ownerKey = keyStrategy.versionKey(metadata.redisNamespace(), id) + ":tenant";
        String owner = jedis.commands().get(ownerKey);
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

    private void prepareEntityTenantOwner(T entity) {
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
    }

    private void prepareTenantOwners(List<T> entities) {
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
        return CAPABILITIES;
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
        activeTenant();
        long startedAt = System.nanoTime();
        try {
            boolean local = nearCacheActive();
            String localId = local ? String.valueOf(id) : null;
            long epoch = local ? nearCache.epoch() : 0;
            if (local) {
                String cached = nearCache.get(localId);
                if (cached != null && nearCacheActive()) {
                    T entity = decodeNear(localId, cached);
                    verifyTenant(entity);
                    if (pageCacheManager.shouldServeCachedEntity(entity)) {
                        applyFetchPlan(List.of(entity)); return Optional.of(entity);
                    }
                    nearCache.invalidate(localId);
                }
            }
            String entityKey = keyStrategy.entityKey(metadata.redisNamespace(), id);
            String tombstoneKey = keyStrategy.tombstoneKey(metadata.redisNamespace(), id);
            List<String> values = jedis.mget(entityKey, tombstoneKey);
            if (values.get(1) != null) {
                return Optional.empty();
            }
            String encoded = values.get(0);
            if (encoded == null) {
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
                    jedis.commands().pttl(entityKey), epoch);
            applyFetchPlan(List.of(entity));
            return Optional.of(entity);
        } finally {
            recordRedisRead(startedAt);
        }
    }

    @Override
    public List<T> findAll(Collection<ID> ids) {
        activeTenant();
        long startedAt = System.nanoTime();
        try {
            if (ids.isEmpty()) {
                return List.of();
            }
            validateEntityRouteContract("Entity bulk read", ids.size());

            List<ID> idsList = new ArrayList<>(ids);
            List<String> keys = new ArrayList<>(idsList.size());
            List<String> tombstoneKeys = new ArrayList<>(idsList.size());
            for (ID id : idsList) {
                keys.add(keyStrategy.entityKey(metadata.redisNamespace(), id));
                tombstoneKeys.add(keyStrategy.tombstoneKey(metadata.redisNamespace(), id));
            }

            List<String> values = mgetEntityAndTombstones(keys, tombstoneKeys);
            List<T> entities = new ArrayList<>(keys.size());
            List<ID> accessed = new ArrayList<>(keys.size());
            for (int index = 0; index < keys.size(); index++) {
                String encoded = values.get(index);
                String tombstone = values.get(keys.size() + index);
                if (encoded != null && tombstone == null) {
                    T entity = codec.fromRedisValue(encoded);
                    verifyTenant(entity);
                    ID id = idsList.get(index);
                    if (pageCacheManager.shouldServeCachedEntity(entity)) {
                        entities.add(entity);
                        accessed.add(id);
                    } else if (effectiveCachePolicy().hotPolicy().evictWhenRejected()) {
                        pageCacheManager.removeEntity(id);
                    }
                }
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
        return queryIndexManager.explain(tenantQuery(querySpec));
    }

    @Override
    public Object aggregateFieldValue(T entity, String column) { return codec.entityColumnValue(entity, column); }

    @Override
    public com.reactor.cachedb.core.query.AggregateResult aggregate(com.reactor.cachedb.core.query.AggregateSpec spec) {
        java.util.Objects.requireNonNull(spec, "spec");
        rejectProjectionOnlyEntityRoute("Entity aggregate");
        boolean count = spec.function() == com.reactor.cachedb.core.query.AggregateFunction.COUNT;
        if (!count && !metadata.columns().contains(spec.fieldName())) throw new IllegalArgumentException("Unknown aggregate column");
        var query = tenantQuery(QuerySpec.where(spec.filter()));
        var indexed = queryIndexManager.indexOnlyCandidates(query);
        var accumulator = new com.reactor.cachedb.core.query.AggregateResult.Accumulator();
        boolean indexValues = indexed.isPresent() && (count || queryIndexManager.hasIndexedColumn(spec.fieldName()));
        boolean countOnlyPolicy = effectiveCachePolicy().hotPolicy().mode()
                == com.reactor.cachedb.core.cache.EntityHotPolicyMode.COUNT_WINDOW;
        List<String> ids = indexed.orElseGet(() -> queryIndexManager.resolveCandidateIds(query));
        for (int start = 0; start < ids.size(); start += 128) {
            var batch = ids.subList(start, Math.min(ids.size(), start + 128));
            var live = new ArrayList<redis.clients.jedis.Response<Boolean>>(batch.size());
            var deleted = new ArrayList<redis.clients.jedis.Response<Boolean>>(batch.size());
            var fields = new ArrayList<redis.clients.jedis.Response<String>>(batch.size());
            try (var pipeline = jedis.pipeline()) {
                for (String id : batch) {
                    live.add(pipeline.exists(keyStrategy.entityKey(metadata.redisNamespace(), id)));
                    deleted.add(pipeline.exists(keyStrategy.tombstoneKey(metadata.redisNamespace(), id)));
                    fields.add(count || !indexValues ? null : pipeline.hget(keyStrategy.indexMetaKey(metadata.redisNamespace(), id), spec.fieldName()));
                }
                pipeline.sync();
                List<String> payloads = !countOnlyPolicy || !indexValues
                        ? jedis.mget(batch.stream().map(id -> keyStrategy.entityKey(metadata.redisNamespace(), id)).toArray(String[]::new)) : null;
                for (int i = 0; i < batch.size(); i++) {
                    if (!Boolean.TRUE.equals(live.get(i).get()) || Boolean.TRUE.equals(deleted.get(i).get())) continue;
                    T entity = payloads == null || payloads.get(i) == null ? null : codec.fromRedisValue(payloads.get(i));
                    if (payloads != null && (entity == null || !pageCacheManager.shouldServeCachedEntity(entity))) continue;
                    if (entity != null && !queryEvaluator.matches(entity, codec, query.rootGroup())) continue;
                    Object value = null;
                    if (!count) {
                        if (indexValues) {
                            String raw = fields.get(i).get();
                            if (raw == null) throw new IllegalStateException("Aggregate index metadata is incomplete; rebuild indexes");
                            if (!raw.equals("__NULL__")) value = new java.math.BigDecimal(raw);
                        } else value = codec.entityColumnValue(entity, spec.fieldName());
                    }
                    accumulator.add(value);
                }
            }
        }
        return accumulator.result();
    }

    @Override
    public List<T> query(QuerySpec requestedQuery) {
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
            if (effectiveCachePolicy().hotPolicy().mode() == com.reactor.cachedb.core.cache.EntityHotPolicyMode.COUNT_WINDOW
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
            queryIndexManager.warm(querySpec);
            boolean sortedIndexScan = queryIndexManager.supportsSortedIndexScan(querySpec);
            boolean primarySortedScan =
                    !sortedIndexScan && queryIndexManager.shouldUsePrimarySortedScan(querySpec);
            List<String> candidateIds =
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
                    !queryIndexManager.fullyIndexed(querySpec.rootGroup());
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
            List<String> values = mgetEntityAndTombstones(keys, tombstoneKeys);
            List<T> entities = new ArrayList<>();
            List<String> staleIndexIds = new ArrayList<>();
            Map<T, Map<String, Object>> resolvedColumns =
                    new IdentityHashMap<>(Math.max(16, keys.size()));
            for (int index = 0; index < keys.size(); index++) {
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
            applyFetchPlan(sliced, querySpec.fetchPlan());
            return sliced;
        } finally {
            recordRedisRead(startedAt);
        }
    }

    @Override
    public HotLookup<T> findHotById(ID id) {
        activeTenant();
        long startedAt = System.nanoTime();
        try {
            boolean local = nearCacheActive();
            String localId = local ? String.valueOf(id) : null;
            long epoch = local ? nearCache.epoch() : 0;
            if (local) {
                String cached = nearCache.get(localId);
                if (cached != null && nearCacheActive()) {
                    T entity = decodeNear(localId, cached);
                    verifyTenant(entity);
                    if (pageCacheManager.shouldServeCachedEntity(entity)) {
                        applyFetchPlan(List.of(entity)); return HotLookup.hit(entity);
                    }
                    nearCache.invalidate(localId);
                }
            }
            String entityKey = keyStrategy.entityKey(metadata.redisNamespace(), id);
            String tombstoneKey = keyStrategy.tombstoneKey(metadata.redisNamespace(), id);
            List<String> values = jedis.mget(entityKey, tombstoneKey);
            if (values.get(1) != null) {
                return HotLookup.tombstoned();
            }
            String encoded = values.get(0);
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
                    jedis.commands().pttl(entityKey), epoch);
            applyFetchPlan(List.of(entity));
            return HotLookup.hit(entity);
        } finally {
            recordRedisRead(startedAt);
        }
    }

    @Override
    public <K> Map<K, List<T>> queryPartitions(PartitionedQuerySpec<K> partitionedQuery) {
        if (activeTenant() != null) throw new IllegalStateException("Use an explicitly tenant-scoped query instead of unscoped partition windows");
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
        if (com.reactor.cachedb.core.api.CacheUnitOfWork.active()) {
            var work = com.reactor.cachedb.core.api.CacheUnitOfWork.current();
            work.requireOwner(entityRegistry); work.put(entity); return entity;
        }
        return saveInternal(entity, -1L, null).entity();
    }

    @Override
    public WriteReceipt<T, ID> saveWithReceipt(T entity) {
        return saveInternal(entity, -1L, null);
    }

    @Override
    public WriteReceipt<T, ID> save(T entity, long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        return saveInternal(entity, expectedVersion, null);
    }

    @Override
    public WriteReceipt<T, ID> saveAfter(T entity, WriteDependency dependency) {
        if (dependency == null) {
            throw new IllegalArgumentException("dependency must not be null");
        }
        return saveInternal(entity, -1L, dependency);
    }

    @Override
    public List<WriteReceipt<T, ID>> saveAll(Collection<T> entities) {
        com.reactor.cachedb.core.api.CacheUnitOfWork.rejectImmediateReceipt();
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        if (entities.size() > 10_000) {
            throw new IllegalArgumentException(
                    "Bulk save supports at most 10000 entities per call");
        }
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
        return patchInternal(id, updates, true).entity();
    }

    @Override
    public WriteReceipt<T, ID> patchWithReceipt(ID id, Map<String, Object> updates) {
        return patchInternal(id, updates, false);
    }

    private WriteReceipt<T, ID> patchInternal(ID id, Map<String, Object> updates, boolean hotRequired) {
        com.reactor.cachedb.core.api.CacheUnitOfWork.rejectImmediateReceipt();
        java.util.Objects.requireNonNull(id, "id");
        boolean tenantOwnerKnown = verifyTenantOwner(id, false, false);
        invalidateNear(id);
        if (updates == null || updates.isEmpty() || updates.size() > metadata.columns().size())
            throw new IllegalArgumentException("PATCH requires a bounded nonempty column set");
        for (String column : updates.keySet())
            if (!metadata.columns().contains(column) || column.equals(metadata.idColumn())
                    || column.equals(metadata.versionColumn()) || column.equals(metadata.deletedColumn()))
                throw new IllegalArgumentException("Invalid PATCH column: " + column);
        if (metadata.tenantFieldName() != null && updates.containsKey(metadata.tenantFieldName()))
            throw new IllegalArgumentException("PATCH cannot change tenant ownership");
        var assignments = new LinkedHashMap<String, Object>();
        var deltas = new LinkedHashMap<String, com.reactor.cachedb.core.model.PatchDeltaSpec>();
        updates.forEach((column, value) -> {
            if (value instanceof com.reactor.cachedb.core.model.PatchDeltaSpec delta) {
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
            var values = jedis.mget(entityKey, versionKey, tombstoneKey);
            if (values.get(2) != null) throw new IllegalStateException("PATCH cannot recreate a deleted entity");
            boolean hot = values.get(0) != null;
            if (!hot && watching()) throw new com.reactor.cachedb.core.repository.HotUpdateUnavailableException(id);
            if (!hot && !deltas.isEmpty()) throw new com.reactor.cachedb.core.repository.HotUpdateUnavailableException(id);
            if (!hot && (hotRequired || !settings.coldPatchEnabled()))
                throw new IllegalStateException("PATCH entity return requires a complete hot entity");
            boolean hasProjections = entityRegistry != null && !entityRegistry.projections(metadata.entityName()).isEmpty();
            if (values.get(1) == null) {
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
                merged = codec.fromColumns(fullColumns);
                payload = codec.toRedisValue(merged);
                admitted = shouldCacheEntity(id, fullColumns, CacheAdmissionSource.WRITE, policy, estimatePayloadBytes(payload));
            }
            var columns = new LinkedHashMap<>(delta);
            columns.put(metadata.idColumn(), id);
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
        return com.reactor.cachedb.core.model.NumericDeltas.apply(metadata.entityName(), id,
                metadata.columnTypes().get(spec.columnName()), spec, current);
    }

    private List<WriteReceipt<T, ID>> saveBatch(List<T> entities) {
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
            for (T entity : entities) {
                ID id = metadata.idAccessor().apply(entity);
                verifyTenant(entity);
                invalidateNear(id);
                String encoded = codec.toRedisValue(entity);
                Map<String, Object> columns = codec.toColumns(entity);
                long estimatedBytes = estimatePayloadBytes(encoded);
                boolean cacheEntity =
                        shouldCacheEntity(
                                id,
                                columns,
                                CacheAdmissionSource.WRITE,
                                effectivePolicy,
                                estimatedBytes);
                WriteOperation<T, ID> operation =
                        new WriteOperation<>(
                                OperationType.UPSERT,
                                metadata,
                                id,
                                columns,
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
            recordRedisWrite(startedAt);
        }
    }

    private WriteReceipt<T, ID> saveInternal(
            T entity, long expectedVersion, WriteDependency dependency) {
        com.reactor.cachedb.core.api.CacheUnitOfWork.rejectImmediateReceipt();
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
            long estimatedPayloadBytes = estimatePayloadBytes(encoded);
            boolean cacheEntity =
                    shouldCacheEntity(
                            id,
                            columns,
                            CacheAdmissionSource.WRITE,
                            effectiveCachePolicy,
                            estimatedPayloadBytes);
            WriteOperation<T, ID> operation =
                    new WriteOperation<>(
                            OperationType.UPSERT,
                            metadata,
                            id,
                            columns,
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
                String encoded = codec.toRedisValue(entity);
                long estimatedPayloadBytes = estimatePayloadBytes(encoded);
                if (shouldCacheEntity(
                        id,
                        columns,
                        CacheAdmissionSource.WARM,
                        effectiveCachePolicy,
                        estimatedPayloadBytes)) {
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
        activeTenant();
        long startedAt = System.nanoTime();
        try {
            String entityKey = keyStrategy.entityKey(metadata.redisNamespace(), id);
            String tombstoneKey = keyStrategy.tombstoneKey(metadata.redisNamespace(), id);
            String versionKey = keyStrategy.versionKey(metadata.redisNamespace(), id);
            List<String> values = jedis.mget(entityKey, tombstoneKey, versionKey);
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
        verifyTenant(entity);
        requirePositiveHydrationVersion(version);
        long startedAt = System.nanoTime();
        try {
            ID id = metadata.idAccessor().apply(entity);
            String redisKey = keyStrategy.entityKey(metadata.redisNamespace(), id);
            String versionKey = keyStrategy.versionKey(metadata.redisNamespace(), id);
            String tombstoneKey = keyStrategy.tombstoneKey(metadata.redisNamespace(), id);
            String encoded = codec.toRedisValue(entity);
            CachePolicy effectiveCachePolicy = effectiveCachePolicy();
            Map<String, Object> columns = codec.toColumns(entity);
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
        if (com.reactor.cachedb.core.api.CacheUnitOfWork.active()) {
            var work = com.reactor.cachedb.core.api.CacheUnitOfWork.current();
            work.requireOwner(entityRegistry); work.delete(metadata.entityType(), id); return;
        }
        deleteWithReceipt(id);
    }

    @Override
    public WriteReceipt<T, ID> deleteWithReceipt(ID id) {
        com.reactor.cachedb.core.api.CacheUnitOfWork.rejectImmediateReceipt();
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
                projectionRefreshQueue).withMutationConfig(mutationConfig).withNearCache(nearCacheCoordinator).withReadFlights(readFlights).withWatchState(watchState);
    }

    @Override
    public <P> ProjectionRepository<P, ID> projected(EntityProjection<T, P, ID> projection) {
        if (metadata.tenantFieldName() != null) throw new IllegalStateException("Register a tenant-scoped projection repository contract explicitly");
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
                readShapeGuardrailConfig);
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

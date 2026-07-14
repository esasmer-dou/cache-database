package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.api.ProjectionRepository;
import com.reactor.cachedb.core.cache.CacheAdmissionSource;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.change.ExternalChangeHydrationRepository;
import com.reactor.cachedb.core.config.QueryIndexConfig;
import com.reactor.cachedb.core.config.ReadThroughConfig;
import com.reactor.cachedb.core.config.ReadShapeGuardrailConfig;
import com.reactor.cachedb.core.config.RedisGuardrailConfig;
import com.reactor.cachedb.core.config.RelationConfig;
import com.reactor.cachedb.core.config.PageCacheConfig;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.guardrail.ReadShapeGuardrails;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.model.WriteOperation;
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
import com.reactor.cachedb.core.query.QueryEvaluator;
import com.reactor.cachedb.core.query.QueryExplainPlan;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.queue.PerformanceObservationContext;
import com.reactor.cachedb.core.queue.StoragePerformanceCollector;
import com.reactor.cachedb.core.queue.WriteBehindQueue;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;
import com.reactor.cachedb.core.relation.RelationBatchContext;
import com.reactor.cachedb.core.relation.RelationBatchLoader;
import com.reactor.cachedb.core.relation.NoOpRelationBatchLoader;
import com.reactor.cachedb.core.route.RouteCacheContext;
import com.reactor.cachedb.core.route.RouteCacheContract;
import redis.clients.jedis.JedisPooled;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RedisEntityRepository<T, ID> implements EntityRepository<T, ID>, ExternalChangeHydrationRepository<T, ID> {

    private final JedisPooled jedis;
    private final JedisPooled backgroundJedis;
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
    private final ReadShapeGuardrailConfig readShapeGuardrailConfig;
    private final RedisPageCacheManager<T, ID> pageCacheManager;
    private final QueryEvaluator queryEvaluator;
    private final RedisQueryIndexManager<T, ID> queryIndexManager;
    private final StoragePerformanceCollector performanceCollector;
    private final ProjectionRefreshDispatcher projectionRefreshDispatcher;
    private final RedisProjectionRefreshQueue projectionRefreshQueue;
    private final RedisVersionedHydrator versionedHydrator;
    private final Map<String, ProjectionSupport<T, ID, ?>> projectionSupportCache = new ConcurrentHashMap<>();

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
            RedisProjectionRefreshQueue projectionRefreshQueue
    ) {
        this(
                jedis, metadata, codec, writeBehindQueue, keyStrategy, fetchPlan, cachePolicy,
                functionExecutor, producerGuard, guardrailConfig, streamKey, compactionStreamKey,
                compactionShardCount, entityRegistry, relationBatchLoader, relationConfig,
                queryIndexConfig, pageLoader, new NoOpEntityByIdLoader<>(), new NoOpEntityQueryLoader<>(),
                pageCacheConfig, ReadThroughConfig.defaults(), readShapeGuardrailConfig, pageCacheManager,
                queryEvaluator, queryIndexManager, performanceCollector, backgroundJedis,
                projectionRefreshDispatcher, projectionRefreshQueue
        );
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
            RedisProjectionRefreshQueue projectionRefreshQueue
    ) {
        this.jedis = jedis;
        this.backgroundJedis = backgroundJedis == null ? jedis : backgroundJedis;
        this.metadata = metadata;
        this.codec = codec;
        this.writeBehindQueue = writeBehindQueue;
        this.keyStrategy = keyStrategy;
        this.fetchPlan = fetchPlan;
        this.cachePolicy = cachePolicy;
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
        this.pageCacheConfig = pageCacheConfig == null ? PageCacheConfig.defaults() : pageCacheConfig;
        this.readThroughConfig = readThroughConfig == null ? ReadThroughConfig.defaults() : readThroughConfig;
        this.readShapeGuardrailConfig = readShapeGuardrailConfig;
        this.pageCacheManager = pageCacheManager;
        this.queryEvaluator = queryEvaluator;
        this.queryIndexManager = queryIndexManager;
        this.performanceCollector = performanceCollector;
        this.projectionRefreshDispatcher = projectionRefreshDispatcher;
        this.projectionRefreshQueue = projectionRefreshQueue;
        this.versionedHydrator = new RedisVersionedHydrator(jedis);
    }

    @Override
    public Optional<T> findById(ID id) {
        long startedAt = System.nanoTime();
        try {
            String entityKey = keyStrategy.entityKey(metadata.redisNamespace(), id);
            String tombstoneKey = keyStrategy.tombstoneKey(metadata.redisNamespace(), id);
            List<String> values = jedis.mget(entityKey, tombstoneKey);
            if (values.get(1) != null) {
                return Optional.empty();
            }
            String encoded = values.get(0);
            if (encoded == null) {
                Optional<T> loaded = loadByIdFromSource(id);
                loaded.ifPresent(entity -> applyFetchPlan(List.of(entity)));
                return loaded;
            }
            T entity = codec.fromRedisValue(encoded);
            if (!pageCacheManager.shouldServeCachedEntity(entity)) {
                if (effectiveCachePolicy().hotPolicy().evictWhenRejected()) {
                    pageCacheManager.removeEntity(id);
                }
                return Optional.empty();
            }
            pageCacheManager.recordEntityAccess(id);
            applyFetchPlan(List.of(entity));
            return Optional.of(entity);
        } finally {
            recordRedisRead(startedAt);
        }
    }

    @Override
    public List<T> findAll(Collection<ID> ids) {
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
            for (int index = 0; index < keys.size(); index++) {
                String encoded = values.get(index);
                String tombstone = values.get(keys.size() + index);
                if (encoded != null && tombstone == null) {
                    T entity = codec.fromRedisValue(encoded);
                    ID id = idsList.get(index);
                    if (pageCacheManager.shouldServeCachedEntity(entity)) {
                        entities.add(entity);
                        pageCacheManager.recordEntityAccess(id);
                    } else if (effectiveCachePolicy().hotPolicy().evictWhenRejected()) {
                        pageCacheManager.removeEntity(id);
                    }
                }
            }
            applyFetchPlan(entities);
            return entities;
        } finally {
            recordRedisRead(startedAt);
        }
    }

    @Override
    public List<T> findPage(PageWindow pageWindow) {
        long startedAt = System.nanoTime();
        try {
            rejectProjectionOnlyEntityRoute("Entity page read");
            if (pageWindow != null) {
                validateEntityRouteContract("Entity page read", pageWindow.pageSize());
            }
            ReadShapeGuardrails.validatePageRequest(metadata.entityName(), pageWindow, effectiveCachePolicy(), readShapeGuardrailConfig);
            Optional<List<T>> cachedPage = pageCacheManager.getCachedPage(pageWindow);
            if (cachedPage.isPresent()) {
                List<T> entities = cachedPage.get();
                applyFetchPlan(entities);
                return entities;
            }

            EntityPageLoader<T> effectivePageLoader = currentPageLoader();
            if (shouldFailOnMissingPageLoader() && effectivePageLoader instanceof NoOpEntityPageLoader) {
                throw new IllegalStateException("Page cache miss for " + metadata.entityName() + " but no EntityPageLoader is registered");
            }
            if (!pageCacheConfig.readThroughEnabled()) {
                return List.of();
            }
            VersionedEntitySourceLoader<T, ID> versionedLoader = versionedSourceLoader(effectivePageLoader);
            if (producerGuard != null && producerGuard.shouldShedReadThroughCache(metadata.redisNamespace())) {
                List<T> loaded = versionedLoader == null
                        ? effectivePageLoader.load(pageWindow)
                        : entities(versionedLoader.loadVersionedPage(pageWindow));
                ReadShapeGuardrails.validateLoadedPage(metadata.entityName(), loaded.size(), effectiveCachePolicy(), readShapeGuardrailConfig);
                applyFetchPlan(loaded);
                return loaded;
            }

            List<VersionedEntity<T>> versioned = versionedLoader == null
                    ? List.of()
                    : versionedLoader.loadVersionedPage(pageWindow);
            List<T> loaded = versionedLoader == null ? effectivePageLoader.load(pageWindow) : entities(versioned);
            ReadShapeGuardrails.validateLoadedPage(metadata.entityName(), loaded.size(), effectiveCachePolicy(), readShapeGuardrailConfig);
            if (versionedLoader == null) {
                recordReadThroughEvent("page-version-unavailable", false);
            } else if (ReadShapeGuardrails.shouldCacheLoadedPage(loaded.size(), effectiveCachePolicy(), readShapeGuardrailConfig)) {
                List<T> hydrated = hydrateWarmBatchInternal(
                        loaded,
                        versions(versioned),
                        true,
                        true
                );
                if (hydrated.size() == loaded.size()) {
                    pageCacheManager.cachePageMembership(pageWindow, loaded);
                }
            }
            applyFetchPlan(loaded);
            return loaded;
        } finally {
            recordRedisRead(startedAt);
        }
    }

    @Override
    public QueryExplainPlan explain(QuerySpec querySpec) {
        return queryIndexManager.explain(querySpec);
    }

    @Override
    public List<T> query(QuerySpec querySpec) {
        long startedAt = System.nanoTime();
        try {
            rejectProjectionOnlyEntityRoute("Entity query");
            if (querySpec != null) {
                validateEntityRouteContract("Entity query", querySpec.limit());
            }
            ReadShapeGuardrails.validateEntityQuery(metadata.entityName(), querySpec, effectiveCachePolicy(), readShapeGuardrailConfig);
            queryIndexManager.warm(querySpec);
            boolean sortedIndexScan = queryIndexManager.supportsSortedIndexScan(querySpec);
            boolean primarySortedScan = !sortedIndexScan && queryIndexManager.shouldUsePrimarySortedScan(querySpec);
            List<String> candidateIds = sortedIndexScan
                    ? queryIndexManager.resolveSortedIds(querySpec)
                    : (primarySortedScan
                    ? queryIndexManager.resolvePrimarySortedIds(querySpec)
                    : queryIndexManager.resolveCandidateIds(querySpec));
            boolean completeSortOrderResolved = primarySortedScan
                    && queryIndexManager.resolvesCompleteSortOrder(querySpec, candidateIds.size());
            boolean requiresResidualEvaluation = !queryIndexManager.fullyIndexed(querySpec.rootGroup());
            boolean requiresInMemorySort = !querySpec.sorts().isEmpty() && !sortedIndexScan && !completeSortOrderResolved;
            if (candidateIds.isEmpty()) {
                List<T> loaded = loadQueryFromSource(querySpec);
                queryIndexManager.observe(querySpec, loaded.size());
                if (!loaded.isEmpty()) {
                    applyFetchPlan(loaded, querySpec.fetchPlan());
                }
                return loaded;
            }

            List<String> payloadIds = windowResolvedCandidateIds(
                    querySpec,
                    candidateIds,
                    sortedIndexScan,
                    completeSortOrderResolved,
                    requiresResidualEvaluation
            );

            List<String> keys = payloadIds.stream()
                    .map(id -> keyStrategy.entityKey(metadata.redisNamespace(), id))
                    .toList();
            List<String> tombstoneKeys = payloadIds.stream()
                    .map(id -> keyStrategy.tombstoneKey(metadata.redisNamespace(), id))
                    .toList();
            List<String> values = mgetEntityAndTombstones(keys, tombstoneKeys);
            List<T> entities = new ArrayList<>();
            List<String> staleIndexIds = new ArrayList<>();
            Map<T, Map<String, Object>> resolvedColumns = new IdentityHashMap<>(Math.max(16, keys.size()));
            for (int index = 0; index < keys.size(); index++) {
                String payload = values.get(index);
                if (payload == null || values.get(keys.size() + index) != null) {
                    staleIndexIds.add(payloadIds.get(index));
                    continue;
                }
                T entity = codec.fromRedisValue(payload);
                if (!pageCacheManager.shouldServeCachedEntity(entity)) {
                    if (effectiveCachePolicy().hotPolicy().evictWhenRejected()) {
                        pageCacheManager.removeEntity((ID) payloadIds.get(index));
                    }
                    continue;
                }
                boolean matches = true;
                if (requiresResidualEvaluation) {
                    Map<String, Object> columns = codec.toColumns(entity);
                    resolvedColumns.put(entity, columns);
                    matches = queryEvaluator.matches(columns, querySpec.rootGroup());
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
                        "Redis query index/payload divergence detected for " + metadata.entityName()
                                + "; removed " + staleIndexIds.size()
                                + " stale index entries, but query read-through is disabled"
                );
            }

            queryIndexManager.observe(querySpec, entities.size());

            if (requiresInMemorySort) {
                Map<T, Map<String, Object>> sortColumns = new IdentityHashMap<>(Math.max(entities.size(), resolvedColumns.size()));
                sortColumns.putAll(resolvedColumns);
                for (T entity : entities) {
                    sortColumns.computeIfAbsent(entity, codec::toColumns);
                }
                entities.sort((left, right) -> compareBySorts(sortColumns.get(left), sortColumns.get(right), querySpec.sorts()));
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

    private List<String> windowResolvedCandidateIds(
            QuerySpec querySpec,
            List<String> candidateIds,
            boolean sortedIndexScan,
            boolean completeSortOrderResolved,
            boolean requiresResidualEvaluation
    ) {
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
        long startedAt = System.nanoTime();
        try {
            producerGuard.applyBackpressure();
            ID id = metadata.idAccessor().apply(entity);
            String redisKey = keyStrategy.entityKey(metadata.redisNamespace(), id);
            String versionKey = keyStrategy.versionKey(metadata.redisNamespace(), id);
            String tombstoneKey = keyStrategy.tombstoneKey(metadata.redisNamespace(), id);
            String compactionPayloadKey = keyStrategy.compactionPayloadKey(metadata.redisNamespace(), id);
            String compactionPendingKey = keyStrategy.compactionPendingKey(metadata.redisNamespace(), id);
            String compactionStatsKey = keyStrategy.compactionStatsKey();
            CachePolicy effectiveCachePolicy = effectiveCachePolicy();
            String targetCompactionStreamKey = keyStrategy.compactionStreamKey(
                    compactionStreamKey,
                    metadata.redisNamespace(),
                    id,
                    compactionShardCount
            );
            String encoded = codec.toRedisValue(entity);
            Map<String, Object> columns = codec.toColumns(entity);
            long estimatedPayloadBytes = estimatePayloadBytes(encoded);
            boolean cacheEntity = shouldCacheEntity(id, columns, CacheAdmissionSource.WRITE, effectiveCachePolicy, estimatedPayloadBytes);
            WriteOperation<T, ID> operation = new WriteOperation<>(
                    OperationType.UPSERT,
                    metadata,
                    id,
                    columns,
                    encoded,
                    0L,
                    Instant.now()
            );

            if (functionExecutor != null && functionExecutor.enabled()) {
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
                        cacheEntity
                );
                if (cacheEntity) {
                    queryIndexManager.reindex(entity);
                    pageCacheManager.recordEntityAccess(id, columns, estimatedPayloadBytes);
                } else if (effectiveCachePolicy.hotPolicy().evictWhenRejected()) {
                    pageCacheManager.removeEntity(id);
                }
            } else {
                long version = jedis.incr(versionKey);
                jedis.del(tombstoneKey);
                Map<String, Object> persistedColumns = enrichColumnsForPersistence(operation.columns(), version, false);
                if (cacheEntity) {
                    pageCacheManager.cacheEntity(entity, encoded, CacheAdmissionSource.WRITE);
                } else if (effectiveCachePolicy.hotPolicy().evictWhenRejected()) {
                    pageCacheManager.removeEntity(id);
                }
                writeBehindQueue.enqueue(new WriteOperation<>(
                        operation.type(),
                        metadata,
                        id,
                        persistedColumns,
                        encoded,
                        version,
                        operation.createdAt()
                ));
            }
            syncProjectionPayloads(entity, false);
            return entity;
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

    public void hydrateWarmBatch(List<T> entities, List<Long> versions, boolean forceImmediateProjectionRefresh) {
        hydrateWarmBatch(entities, versions, forceImmediateProjectionRefresh, false);
    }

    public void hydrateWarmBatch(
            List<T> entities,
            List<Long> versions,
            boolean forceImmediateProjectionRefresh,
            boolean reindexQueryIndexes
    ) {
        hydrateWarmBatchInternal(entities, versions, forceImmediateProjectionRefresh, reindexQueryIndexes);
    }

    private List<T> hydrateWarmBatchInternal(
            List<T> entities,
            List<Long> versions,
            boolean forceImmediateProjectionRefresh,
            boolean reindexQueryIndexes
    ) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        if (versions == null || versions.size() != entities.size()) {
            throw new IllegalArgumentException("versions must be present and aligned with entities for hydrateWarmBatch");
        }
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
                    throw new IllegalArgumentException("Hydration version must not be null at index " + index);
                }
                requirePositiveHydrationVersion(requestedVersion);
                T entity = entities.get(index);
                ID id = metadata.idAccessor().apply(entity);
                Map<String, Object> columns = codec.toColumns(entity);
                String encoded = codec.toRedisValue(entity);
                long estimatedPayloadBytes = estimatePayloadBytes(encoded);
                if (shouldCacheEntity(id, columns, CacheAdmissionSource.WARM, effectiveCachePolicy, estimatedPayloadBytes)) {
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

            ArrayList<RedisVersionedHydrator.Upsert> hydrationOperations = new ArrayList<>(admittedEntities.size());
            for (int index = 0; index < admittedEntities.size(); index++) {
                ID id = admittedIds.get(index);
                hydrationOperations.add(new RedisVersionedHydrator.Upsert(
                        keyStrategy.entityKey(metadata.redisNamespace(), id),
                        keyStrategy.versionKey(metadata.redisNamespace(), id),
                        keyStrategy.tombstoneKey(metadata.redisNamespace(), id),
                        admittedPayloads.get(index),
                        admittedVersions.get(index),
                        effectiveCachePolicy.entityTtlSeconds()
                ));
            }
            List<Boolean> hydrationResults = versionedHydrator.upsertBatch(hydrationOperations);
            ArrayList<T> hydratedEntities = new ArrayList<>(admittedEntities.size());
            ArrayList<ID> hydratedIds = new ArrayList<>(admittedIds.size());
            ArrayList<Map<String, Object>> hydratedColumns = new ArrayList<>(admittedColumns.size());
            ArrayList<Long> hydratedPayloadBytes = new ArrayList<>(admittedPayloadBytes.size());
            for (int index = 0; index < hydrationResults.size(); index++) {
                if (hydrationResults.get(index)) {
                    hydratedEntities.add(admittedEntities.get(index));
                    hydratedIds.add(admittedIds.get(index));
                    hydratedColumns.add(admittedColumns.get(index));
                    hydratedPayloadBytes.add(admittedPayloadBytes.get(index));
                }
            }
            if (effectiveCachePolicy.hotPolicy().evictWhenRejected()) {
                for (ID rejectedId : rejectedIds) {
                    pageCacheManager.removeEntity(rejectedId);
                }
            }
            if (reindexQueryIndexes) {
                queryIndexManager.reindexBatch(hydratedEntities);
            }
            for (int index = 0; index < hydratedIds.size(); index++) {
                pageCacheManager.recordEntityAccess(hydratedIds.get(index), hydratedColumns.get(index), hydratedPayloadBytes.get(index));
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

    public void hydrateProjectionWarmBatch(List<T> entities) {
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

    private void warmProjectionPayloadsBatch(List<T> entities) {
        if (entities == null || entities.isEmpty() || entityRegistry == null) {
            return;
        }
        Collection<com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?>> bindings = entityRegistry.projections(metadata.entityName());
        if (bindings.isEmpty()) {
            return;
        }
        for (com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding : bindings) {
            warmProjectionPayloadBatch(rawBinding, entities);
        }
    }

    @SuppressWarnings("unchecked")
    private <P> void warmProjectionPayloadBatch(
            com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding,
            List<T> entities
    ) {
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
            CacheAdmissionSource source
    ) {
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
            boolean cacheEntity = shouldCacheEntity(id, columns, source, effectiveCachePolicy, estimatedPayloadBytes);
            if (!cacheEntity) {
                if (effectiveCachePolicy.hotPolicy().evictWhenRejected()) {
                    pageCacheManager.removeEntity(id);
                }
                syncProjectionPayloads(entity, forceImmediateProjectionRefresh);
                return entity;
            }
            boolean hydrated = versionedHydrator.upsert(
                    redisKey,
                    versionKey,
                    tombstoneKey,
                    encoded,
                    version,
                    effectiveCachePolicy.entityTtlSeconds()
            );
            if (!hydrated) {
                return entity;
            }
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
        long startedAt = System.nanoTime();
        try {
            producerGuard.applyBackpressure();
            String redisKey = keyStrategy.entityKey(metadata.redisNamespace(), id);
            String versionKey = keyStrategy.versionKey(metadata.redisNamespace(), id);
            String tombstoneKey = keyStrategy.tombstoneKey(metadata.redisNamespace(), id);
            String compactionPayloadKey = keyStrategy.compactionPayloadKey(metadata.redisNamespace(), id);
            String compactionPendingKey = keyStrategy.compactionPendingKey(metadata.redisNamespace(), id);
            String compactionStatsKey = keyStrategy.compactionStatsKey();
            String targetCompactionStreamKey = keyStrategy.compactionStreamKey(
                    compactionStreamKey,
                    metadata.redisNamespace(),
                    id,
                    compactionShardCount
            );
            WriteOperation<T, ID> operation = new WriteOperation<>(
                    OperationType.DELETE,
                    metadata,
                    id,
                    Map.of(metadata.idColumn(), id),
                    "",
                    0L,
                    Instant.now()
            );

            if (functionExecutor != null && functionExecutor.enabled()) {
                functionExecutor.delete(
                        redisKey,
                        versionKey,
                        tombstoneKey,
                        streamKey,
                        compactionPayloadKey,
                        compactionPendingKey,
                        targetCompactionStreamKey,
                        compactionStatsKey,
                        operation
                );
                pageCacheManager.removeEntity(id);
            } else {
                long version = jedis.incr(versionKey);
                writeTombstoneKey(tombstoneKey, version);
                pageCacheManager.removeEntity(id);
                writeBehindQueue.enqueue(new WriteOperation<>(
                        operation.type(),
                        metadata,
                        id,
                        enrichColumnsForPersistence(operation.columns(), version, true),
                        "",
                        version,
                        operation.createdAt()
                ));
            }
            deleteProjectionPayloads(id, false);
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
                projectionRefreshQueue
        );
    }

    @Override
    public <P> ProjectionRepository<P, ID> projected(EntityProjection<T, P, ID> projection) {
        if (entityRegistry == null) {
            throw new IllegalStateException("Projection repositories require an EntityRegistry");
        }
        EntityProjectionBinding<T, P, ID> binding = entityRegistry.registerProjection(metadata, projection);
        ProjectionSupport<T, ID, P> support = projectionSupport(binding);
        return new RedisProjectionRepository<T, ID, P>(
                support.readRuntime(),
                support.refreshRuntime(),
                queryEvaluator,
                rawId -> loadProjectionFromBaseEntityPayload(rawId, projection),
                rawIds -> loadProjectionMapFromBaseEntityPayload(rawIds, projection),
                querySpec -> projectQueryFromBaseEntities(querySpec, projection),
                performanceCollector,
                readShapeGuardrailConfig
        );
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
                    "Hydration for " + metadata.entityName() + " requires a source version greater than zero"
            );
        }
    }

    private void writeTombstoneKey(String tombstoneKey, long version) {
        if (guardrailConfig.tombstoneTtlSeconds() > 0) {
            jedis.setex(tombstoneKey, guardrailConfig.tombstoneTtlSeconds(), String.valueOf(version));
            return;
        }
        jedis.set(tombstoneKey, String.valueOf(version));
    }

    private CachePolicy effectiveCachePolicy() {
        return producerGuard == null ? cachePolicy : producerGuard.effectiveCachePolicy(cachePolicy);
    }

    private boolean shouldCacheEntity(
            ID id,
            Map<String, Object> columns,
            CacheAdmissionSource source,
            CachePolicy effectiveCachePolicy,
            long estimatedPayloadBytes
    ) {
        boolean admitted = effectiveCachePolicy.hotPolicy().shouldAdmit(columns, source)
                && pageCacheManager.allowTenantQuota(id, columns, estimatedPayloadBytes);
        recordCacheAdmission(source, admitted);
        return admitted;
    }

    private long estimatePayloadBytes(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return 1L;
        }
        return encoded.getBytes(StandardCharsets.UTF_8).length + 128L;
    }

    private void recordCacheAdmission(CacheAdmissionSource source, boolean admitted) {
        if (performanceCollector == null) {
            return;
        }
        String routeTag = PerformanceObservationContext.currentTag();
        String tag = routeTag.isBlank()
                ? "entity:" + metadata.entityName() + ":" + (source == null ? CacheAdmissionSource.READ : source).name()
                : "route:" + routeTag + ":" + (source == null ? CacheAdmissionSource.READ : source).name();
        performanceCollector.recordCacheAdmission(tag, admitted);
    }

    private void recordReadThroughEvent(String event, boolean success) {
        if (performanceCollector == null) {
            return;
        }
        String routeTag = PerformanceObservationContext.currentTag();
        String tag = routeTag.isBlank()
                ? "read-through:entity:" + metadata.entityName() + ":" + event
                : "read-through:route:" + routeTag + ":" + event;
        performanceCollector.recordCacheAdmission(tag, success);
    }

    private Map<String, Object> enrichColumnsForPersistence(Map<String, Object> source, long version, boolean deleting) {
        LinkedHashMap<String, Object> enriched = new LinkedHashMap<>(source);
        enriched.put(metadata.versionColumn(), version);
        if (metadata.deletedColumn() != null) {
            enriched.put(metadata.deletedColumn(), deleting ? metadata.deletedMarkerValue() : metadata.activeMarkerValue());
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
                    "FetchPlan depth " + effectiveFetchPlan.maxDepth()
                            + " exceeds configured maxFetchDepth=" + relationConfig.maxFetchDepth()
                            + " for " + metadata.entityName()
            );
        }
        RelationBatchLoader<T> effectiveRelationBatchLoader = currentRelationBatchLoader();
        if (relationConfig.failOnMissingPreloader() && effectiveRelationBatchLoader instanceof NoOpRelationBatchLoader) {
            throw new IllegalStateException("FetchPlan requested relations but no RelationBatchLoader is registered for " + metadata.entityName());
        }
        effectiveRelationBatchLoader.preload(entities, new RelationBatchContext(effectiveFetchPlan, relationConfig));
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
        if (!readThroughConfig.mode().byIdEnabled()) {
            recordReadThroughEvent("by-id-disabled", false);
            return Optional.empty();
        }
        EntityByIdLoader<T, ID> effectiveByIdLoader = currentByIdLoader();
        if (effectiveByIdLoader instanceof NoOpEntityByIdLoader) {
            if (readThroughConfig.failOnMissingLoader()) {
                throw new IllegalStateException("Redis miss for " + metadata.entityName() + " id=" + id
                        + " but no EntityByIdLoader is registered");
            }
            recordReadThroughEvent("by-id-loader-missing", false);
            return Optional.empty();
        }
        VersionedEntitySourceLoader<T, ID> versionedLoader = versionedSourceLoader(effectiveByIdLoader);
        Optional<VersionedEntity<T>> versioned = versionedLoader == null
                ? Optional.empty()
                : Optional.ofNullable(versionedLoader.loadVersionedById(id)).orElse(Optional.empty());
        Optional<T> loaded = versionedLoader == null
                ? Optional.ofNullable(effectiveByIdLoader.load(id)).orElse(Optional.empty())
                : versioned.map(VersionedEntity::entity);
        if (loaded.isEmpty()) {
            recordReadThroughEvent("by-id-source-empty", false);
            return Optional.empty();
        }
        if (shouldHydrateReadThrough()) {
            if (versioned.isPresent()) {
                hydrateInternal(loaded.get(), versioned.get().version(), true, true, true, CacheAdmissionSource.READ);
            } else {
                recordReadThroughEvent("by-id-version-unavailable", false);
            }
        }
        recordReadThroughEvent("by-id-loaded", true);
        return loaded;
    }

    private List<T> loadQueryFromSource(QuerySpec querySpec) {
        if (!readThroughConfig.mode().queryEnabled()) {
            recordReadThroughEvent("query-disabled", false);
            return List.of();
        }
        if (querySpec.limit() > readThroughConfig.maxQueryLoadRows()) {
            throw new IllegalArgumentException("Read-through query for " + metadata.entityName()
                    + " requested " + querySpec.limit()
                    + " rows but maxQueryLoadRows=" + readThroughConfig.maxQueryLoadRows());
        }
        EntityQueryLoader<T> effectiveQueryLoader = currentQueryLoader();
        if (effectiveQueryLoader instanceof NoOpEntityQueryLoader) {
            if (readThroughConfig.failOnMissingLoader()) {
                throw new IllegalStateException("Redis query miss for " + metadata.entityName()
                        + " but no EntityQueryLoader is registered");
            }
            recordReadThroughEvent("query-loader-missing", false);
            return List.of();
        }
        VersionedEntitySourceLoader<T, ID> versionedLoader = versionedSourceLoader(effectiveQueryLoader);
        List<VersionedEntity<T>> versioned = versionedLoader == null
                ? List.of()
                : versionedLoader.loadVersionedQuery(querySpec);
        List<T> loaded = versionedLoader == null ? effectiveQueryLoader.load(querySpec) : entities(versioned);
        if (loaded == null || loaded.isEmpty()) {
            recordReadThroughEvent("query-source-empty", false);
            return List.of();
        }
        if (loaded.size() > readThroughConfig.maxQueryLoadRows()) {
            throw new IllegalArgumentException("Read-through query loader for " + metadata.entityName()
                    + " returned " + loaded.size()
                    + " rows but maxQueryLoadRows=" + readThroughConfig.maxQueryLoadRows());
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
                || (readThroughConfig.mode().pageEnabled() && readThroughConfig.failOnMissingLoader());
    }

    private boolean shouldHydrateReadThrough() {
        return readThroughConfig.hydrateLoadedEntities()
                && (producerGuard == null || !producerGuard.shouldShedReadThroughCache(metadata.redisNamespace()));
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
        throw new IllegalStateException(shape + " for " + metadata.entityName()
                + " is disabled because readThrough.mode=PROJECTION_ONLY; use ProjectionRepository or an explicit SQL route");
    }

    private List<String> mgetEntityAndTombstones(List<String> entityKeys, List<String> tombstoneKeys) {
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
        Collection<com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?>> bindings = entityRegistry.projections(metadata.entityName());
        if (bindings.isEmpty()) {
            return;
        }
        for (com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding : bindings) {
            syncProjectionPayload(rawBinding, entity, forceImmediateRefresh);
        }
    }

    @Override
    public void hydrateExternalDelete(ID id, long version) {
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
            boolean hydrated = versionedHydrator.delete(
                    entityKey,
                    versionKey,
                    tombstoneKey,
                    version,
                    guardrailConfig.tombstoneTtlSeconds()
            );
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
        Collection<com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?>> bindings = entityRegistry.projections(metadata.entityName());
        if (bindings.isEmpty()) {
            return;
        }
        for (com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding : bindings) {
            syncProjectionPayloadBatch(rawBinding, entities, forceImmediateRefresh);
        }
    }

    private void enqueueProjectionRefreshBatch(List<T> entities, Collection<ID> ids) {
        if (ids == null || ids.isEmpty() || entities == null || entities.isEmpty() || entityRegistry == null) {
            return;
        }
        Collection<com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?>> bindings = entityRegistry.projections(metadata.entityName());
        if (bindings.isEmpty()) {
            return;
        }
        ArrayList<String> asyncProjectionNames = new ArrayList<>();
        ArrayList<com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?>> synchronousBindings = new ArrayList<>();
        for (com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding : bindings) {
            if (rawBinding.projection().refreshMode().isAsync()) {
                asyncProjectionNames.add(rawBinding.projection().name());
            } else {
                synchronousBindings.add(rawBinding);
            }
        }
        if (!asyncProjectionNames.isEmpty() && projectionRefreshQueue != null) {
            projectionRefreshQueue.enqueueUpsertBatch(metadata.entityName(), asyncProjectionNames, ids);
        }
        if (synchronousBindings.isEmpty()) {
            return;
        }
        for (T entity : entities) {
            for (com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding : synchronousBindings) {
                syncProjectionPayload(rawBinding, entity, false);
            }
        }
    }

    private void deleteProjectionPayloads(Object id, boolean forceImmediateRefresh) {
        if (entityRegistry == null) {
            return;
        }
        Collection<com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?>> bindings = entityRegistry.projections(metadata.entityName());
        if (bindings.isEmpty()) {
            return;
        }
        for (com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding : bindings) {
            deleteProjectionPayload(rawBinding, id, forceImmediateRefresh);
        }
    }

    @SuppressWarnings("unchecked")
    private <P> void syncProjectionPayload(
            com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding,
            T entity,
            boolean forceImmediateRefresh
    ) {
        EntityProjectionBinding<T, P, ID> binding = (EntityProjectionBinding<T, P, ID>) rawBinding;
        if (!forceImmediateRefresh && binding.projection().refreshMode().isAsync() && projectionRefreshQueue != null) {
            projectionRefreshQueue.enqueueUpsert(metadata.entityName(), binding.projection().name(), metadata.idAccessor().apply(entity));
            return;
        }
        ProjectionSupport<T, ID, P> support = projectionSupport(binding);
        P projection = binding.projection().projector().apply(entity);
        if (projection == null) {
            applyProjectionDelete(support, metadata.idAccessor().apply(entity), forceImmediateRefresh);
            return;
        }
        applyProjectionUpsert(support, projection, forceImmediateRefresh);
    }

    @SuppressWarnings("unchecked")
    private <P> void syncProjectionPayloadBatch(
            com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding,
            List<T> entities,
            boolean forceImmediateRefresh
    ) {
        EntityProjectionBinding<T, P, ID> binding = (EntityProjectionBinding<T, P, ID>) rawBinding;
        if (!forceImmediateRefresh && binding.projection().refreshMode().isAsync() && projectionRefreshQueue != null) {
            ArrayList<ID> ids = new ArrayList<>(entities.size());
            for (T entity : entities) {
                ids.add(metadata.idAccessor().apply(entity));
            }
            projectionRefreshQueue.enqueueUpsertBatch(metadata.entityName(), List.of(binding.projection().name()), ids);
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
            boolean forceImmediateRefresh
    ) {
        EntityProjectionBinding<T, P, ID> binding = (EntityProjectionBinding<T, P, ID>) rawBinding;
        if (!forceImmediateRefresh && binding.projection().refreshMode().isAsync() && projectionRefreshQueue != null) {
            projectionRefreshQueue.enqueueDelete(metadata.entityName(), binding.projection().name(), rawId);
            return;
        }
        applyProjectionDelete(projectionSupport(binding), (ID) rawId, forceImmediateRefresh);
    }

    private <P> void applyProjectionUpsert(ProjectionSupport<T, ID, P> support, P projection, boolean forceImmediateRefresh) {
        if (projection == null) {
            return;
        }
        if (!forceImmediateRefresh && support.binding().projection().refreshMode().isAsync()) {
            throw new IllegalStateException(
                    "Async projection " + support.binding().projection().name()
                            + " requires the durable projection refresh queue"
            );
        }
        safeProjectionUpsert(forceImmediateRefresh ? support.refreshRuntime() : support.readRuntime(), projection);
    }

    private <P> void applyProjectionUpsertBatch(ProjectionSupport<T, ID, P> support, Collection<P> projections, boolean forceImmediateRefresh) {
        if (projections == null || projections.isEmpty()) {
            return;
        }
        if (!forceImmediateRefresh && support.binding().projection().refreshMode().isAsync()) {
            throw new IllegalStateException(
                    "Async projection " + support.binding().projection().name()
                            + " requires the durable projection refresh queue"
            );
        }
        safeProjectionUpsertBatch(forceImmediateRefresh ? support.refreshRuntime() : support.readRuntime(), projections);
    }

    private <P> void applyProjectionDelete(ProjectionSupport<T, ID, P> support, ID id, boolean forceImmediateRefresh) {
        if (id == null) {
            return;
        }
        if (!forceImmediateRefresh && support.binding().projection().refreshMode().isAsync()) {
            throw new IllegalStateException(
                    "Async projection " + support.binding().projection().name()
                            + " requires the durable projection refresh queue"
            );
        }
        safeProjectionDelete(forceImmediateRefresh ? support.refreshRuntime() : support.readRuntime(), id);
    }

    private <P> void safeProjectionUpsert(RedisProjectionRuntime<P, ID> runtime, P projection) {
        runtime.upsert(projection);
    }

    private <P> void safeProjectionUpsertBatch(RedisProjectionRuntime<P, ID> runtime, Collection<P> projections) {
        runtime.upsertBatch(projections);
    }

    private <P> void safeProjectionWarmUpsertBatch(RedisProjectionRuntime<P, ID> runtime, Collection<P> projections) {
        runtime.upsertWarmBatch(projections);
    }

    private <P> void safeProjectionDelete(RedisProjectionRuntime<P, ID> runtime, ID id) {
        runtime.delete(id);
    }

    @SuppressWarnings("unchecked")
    private <P> ProjectionSupport<T, ID, P> projectionSupport(EntityProjectionBinding<T, P, ID> binding) {
        return (ProjectionSupport<T, ID, P>) projectionSupportCache.computeIfAbsent(
                binding.projection().name(),
                ignored -> createProjectionSupport(binding)
        );
    }

    private <P> ProjectionSupport<T, ID, P> createProjectionSupport(EntityProjectionBinding<T, P, ID> binding) {
        ProjectionEntityMetadata<P, ID> projectionMetadata = new ProjectionEntityMetadata<>(metadata, binding.projection());
        ProjectionEntityCodec<P, ID> projectionCodec = new ProjectionEntityCodec<>(binding.projection());
        QueryIndexConfig projectionQueryIndexConfig = projectionQueryIndexConfig();
        RedisQueryIndexManager<P, ID> foregroundProjectionQueryIndexManager = new RedisQueryIndexManager<>(
                jedis,
                projectionMetadata,
                projectionCodec,
                keyStrategy,
                entityRegistry,
                projectionQueryIndexConfig,
                relationConfig,
                queryEvaluator,
                producerGuard
        );
        RedisProjectionRuntime<P, ID> readRuntime = new RedisProjectionRuntime<>(
                jedis,
                projectionMetadata,
                projectionCodec,
                keyStrategy,
                cachePolicy,
                foregroundProjectionQueryIndexManager,
                binding
        );
        RedisProjectionRuntime<P, ID> refreshRuntime = readRuntime;
        if (backgroundJedis != null && backgroundJedis != jedis) {
            RedisQueryIndexManager<P, ID> backgroundProjectionQueryIndexManager = new RedisQueryIndexManager<>(
                    backgroundJedis,
                    projectionMetadata,
                    projectionCodec,
                    keyStrategy,
                    entityRegistry,
                    projectionQueryIndexConfig,
                    relationConfig,
                    queryEvaluator,
                    producerGuard
            );
            refreshRuntime = new RedisProjectionRuntime<>(
                    backgroundJedis,
                    projectionMetadata,
                    projectionCodec,
                    keyStrategy,
                    cachePolicy,
                    backgroundProjectionQueryIndexManager,
                    binding
            );
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

    private <P> Optional<P> loadProjectionFromBaseEntityPayload(String rawId, EntityProjection<T, P, ID> projection) {
        List<String> values = jedis.mget(
                keyStrategy.entityKey(metadata.redisNamespace(), rawId),
                keyStrategy.tombstoneKey(metadata.redisNamespace(), rawId)
        );
        if (values.get(1) != null || values.get(0) == null) {
            return Optional.empty();
        }
        T entity = codec.fromRedisValue(values.get(0));
        return Optional.ofNullable(projection.projector().apply(entity));
    }

    private <P> Map<String, P> loadProjectionMapFromBaseEntityPayload(Collection<String> rawIds, EntityProjection<T, P, ID> projection) {
        if (rawIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = new ArrayList<>(rawIds);
        List<String> entityKeys = ids.stream()
                .map(id -> keyStrategy.entityKey(metadata.redisNamespace(), id))
                .toList();
        List<String> tombstoneKeys = ids.stream()
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

    private <P> List<P> projectQueryFromBaseEntities(QuerySpec querySpec, EntityProjection<T, P, ID> projection) {
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

    private int compareBySorts(Map<String, Object> leftColumns, Map<String, Object> rightColumns, List<QuerySort> sorts) {
        for (QuerySort sort : sorts) {
            int result = queryEvaluator.comparator(sort).compare(leftColumns, rightColumns);
            if (result != 0) {
                return result;
            }
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
            RedisProjectionRuntime<P, ID> refreshRuntime
    ) {
    }
}

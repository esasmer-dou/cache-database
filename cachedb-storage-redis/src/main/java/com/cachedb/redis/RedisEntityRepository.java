package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.api.ProjectionRepository;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.QueryIndexConfig;
import com.reactor.cachedb.core.config.RedisGuardrailConfig;
import com.reactor.cachedb.core.config.RelationConfig;
import com.reactor.cachedb.core.config.PageCacheConfig;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.model.WriteOperation;
import com.reactor.cachedb.core.page.EntityPageLoader;
import com.reactor.cachedb.core.page.NoOpEntityPageLoader;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.projection.EntityProjectionBinding;
import com.reactor.cachedb.core.projection.ProjectionEntityCodec;
import com.reactor.cachedb.core.projection.ProjectionEntityMetadata;
import com.reactor.cachedb.core.query.QueryEvaluator;
import com.reactor.cachedb.core.query.QueryExplainPlan;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.queue.StoragePerformanceCollector;
import com.reactor.cachedb.core.queue.WriteBehindQueue;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;
import com.reactor.cachedb.core.relation.RelationBatchContext;
import com.reactor.cachedb.core.relation.RelationBatchLoader;
import com.reactor.cachedb.core.relation.NoOpRelationBatchLoader;
import redis.clients.jedis.JedisPooled;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RedisEntityRepository<T, ID> implements EntityRepository<T, ID> {

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
    private final PageCacheConfig pageCacheConfig;
    private final RedisPageCacheManager<T, ID> pageCacheManager;
    private final QueryEvaluator queryEvaluator;
    private final RedisQueryIndexManager<T, ID> queryIndexManager;
    private final StoragePerformanceCollector performanceCollector;
    private final ProjectionRefreshDispatcher projectionRefreshDispatcher;
    private final RedisProjectionRefreshQueue projectionRefreshQueue;
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
        this.pageLoader = pageLoader;
        this.pageCacheConfig = pageCacheConfig;
        this.pageCacheManager = pageCacheManager;
        this.queryEvaluator = queryEvaluator;
        this.queryIndexManager = queryIndexManager;
        this.performanceCollector = performanceCollector;
        this.projectionRefreshDispatcher = projectionRefreshDispatcher;
        this.projectionRefreshQueue = projectionRefreshQueue;
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
                return Optional.empty();
            }
            T entity = codec.fromRedisValue(encoded);
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
                    entities.add(codec.fromRedisValue(encoded));
                    pageCacheManager.recordEntityAccess(idsList.get(index));
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
            Optional<List<T>> cachedPage = pageCacheManager.getCachedPage(pageWindow);
            if (cachedPage.isPresent()) {
                List<T> entities = cachedPage.get();
                applyFetchPlan(entities);
                return entities;
            }

            EntityPageLoader<T> effectivePageLoader = currentPageLoader();
            if (pageCacheConfig.failOnMissingPageLoader() && effectivePageLoader instanceof NoOpEntityPageLoader) {
                throw new IllegalStateException("Page cache miss for " + metadata.entityName() + " but no EntityPageLoader is registered");
            }
            if (!pageCacheConfig.readThroughEnabled()) {
                return List.of();
            }
            if (producerGuard != null && producerGuard.shouldShedReadThroughCache(metadata.redisNamespace())) {
                List<T> loaded = effectivePageLoader.load(pageWindow);
                applyFetchPlan(loaded);
                return loaded;
            }

            List<T> loaded = effectivePageLoader.load(pageWindow);
            pageCacheManager.cachePage(pageWindow, loaded);
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
                queryIndexManager.observe(querySpec, 0L);
                return List.of();
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
            Map<T, Map<String, Object>> resolvedColumns = new IdentityHashMap<>(Math.max(16, keys.size()));
            for (int index = 0; index < keys.size(); index++) {
                String payload = values.get(index);
                if (payload == null || values.get(keys.size() + index) != null) {
                    continue;
                }
                T entity = codec.fromRedisValue(payload);
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
            WriteOperation<T, ID> operation = new WriteOperation<>(
                    OperationType.UPSERT,
                    metadata,
                    id,
                    codec.toColumns(entity),
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
                        effectiveCachePolicy
                );
                queryIndexManager.reindex(entity);
            } else {
                long version = jedis.incr(versionKey);
                expireVersionKey(versionKey);
                jedis.del(tombstoneKey);
                Map<String, Object> persistedColumns = enrichColumnsForPersistence(operation.columns(), version, false);
                pageCacheManager.cacheEntity(entity, encoded);
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
            pageCacheManager.recordEntityAccess(id);
            syncProjectionPayloads(entity);
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
                expireVersionKey(versionKey);
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
            deleteProjectionPayloads(id);
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
                pageCacheConfig,
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
                performanceCollector
        );
    }

    public FetchPlan fetchPlan() {
        return fetchPlan;
    }

    public CachePolicy cachePolicy() {
        return effectiveCachePolicy();
    }

    private void expireVersionKey(String versionKey) {
        if (guardrailConfig.versionKeyTtlSeconds() > 0) {
            jedis.expire(versionKey, guardrailConfig.versionKeyTtlSeconds());
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

    private List<String> mgetEntityAndTombstones(List<String> entityKeys, List<String> tombstoneKeys) {
        int entityCount = entityKeys.size();
        String[] lookupKeys = new String[entityCount + tombstoneKeys.size()];
        for (int index = 0; index < entityCount; index++) {
            lookupKeys[index] = entityKeys.get(index);
            lookupKeys[entityCount + index] = tombstoneKeys.get(index);
        }
        return jedis.mget(lookupKeys);
    }

    private void syncProjectionPayloads(T entity) {
        if (entityRegistry == null) {
            return;
        }
        Collection<com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?>> bindings = entityRegistry.projections(metadata.entityName());
        if (bindings.isEmpty()) {
            return;
        }
        for (com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding : bindings) {
            syncProjectionPayload(rawBinding, entity);
        }
    }

    private void deleteProjectionPayloads(Object id) {
        if (entityRegistry == null) {
            return;
        }
        Collection<com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?>> bindings = entityRegistry.projections(metadata.entityName());
        if (bindings.isEmpty()) {
            return;
        }
        for (com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding : bindings) {
            deleteProjectionPayload(rawBinding, id);
        }
    }

    @SuppressWarnings("unchecked")
    private <P> void syncProjectionPayload(
            com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding,
            T entity
    ) {
        EntityProjectionBinding<T, P, ID> binding = (EntityProjectionBinding<T, P, ID>) rawBinding;
        if (binding.projection().refreshMode().isAsync() && projectionRefreshQueue != null) {
            projectionRefreshQueue.enqueueUpsert(metadata.entityName(), binding.projection().name(), metadata.idAccessor().apply(entity));
            return;
        }
        ProjectionSupport<T, ID, P> support = projectionSupport(binding);
        P projection = binding.projection().projector().apply(entity);
        if (projection == null) {
            applyProjectionDelete(support, metadata.idAccessor().apply(entity));
            return;
        }
        applyProjectionUpsert(support, projection);
    }

    @SuppressWarnings("unchecked")
    private <P> void deleteProjectionPayload(
            com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?> rawBinding,
            Object rawId
    ) {
        EntityProjectionBinding<T, P, ID> binding = (EntityProjectionBinding<T, P, ID>) rawBinding;
        if (binding.projection().refreshMode().isAsync() && projectionRefreshQueue != null) {
            projectionRefreshQueue.enqueueDelete(metadata.entityName(), binding.projection().name(), rawId);
            return;
        }
        applyProjectionDelete(projectionSupport(binding), (ID) rawId);
    }

    private <P> void applyProjectionUpsert(ProjectionSupport<T, ID, P> support, P projection) {
        if (projection == null) {
            return;
        }
        if (support.binding().projection().refreshMode().isAsync() && projectionRefreshDispatcher != null) {
            try {
                projectionRefreshDispatcher.dispatch(() -> safeProjectionUpsert(support.refreshRuntime(), projection));
                return;
            } catch (RuntimeException ignored) {
            }
        }
        safeProjectionUpsert(support.readRuntime(), projection);
    }

    private <P> void applyProjectionDelete(ProjectionSupport<T, ID, P> support, ID id) {
        if (id == null) {
            return;
        }
        if (support.binding().projection().refreshMode().isAsync() && projectionRefreshDispatcher != null) {
            try {
                projectionRefreshDispatcher.dispatch(() -> safeProjectionDelete(support.refreshRuntime(), id));
                return;
            } catch (RuntimeException ignored) {
            }
        }
        safeProjectionDelete(support.readRuntime(), id);
    }

    private <P> void safeProjectionUpsert(RedisProjectionRuntime<P, ID> runtime, P projection) {
        try {
            runtime.upsert(projection);
        } catch (RuntimeException ignored) {
        }
    }

    private <P> void safeProjectionDelete(RedisProjectionRuntime<P, ID> runtime, ID id) {
        try {
            runtime.delete(id);
        } catch (RuntimeException ignored) {
        }
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
        RedisQueryIndexManager<P, ID> foregroundProjectionQueryIndexManager = new RedisQueryIndexManager<>(
                jedis,
                projectionMetadata,
                projectionCodec,
                keyStrategy,
                entityRegistry,
                queryIndexConfig,
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
                    queryIndexConfig,
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

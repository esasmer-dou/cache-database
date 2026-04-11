package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.api.CacheSession;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.page.EntityPageLoader;
import com.reactor.cachedb.core.page.NoOpEntityPageLoader;
import com.reactor.cachedb.core.query.QueryEvaluator;
import com.reactor.cachedb.core.queue.StoragePerformanceCollector;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;
import com.reactor.cachedb.core.relation.NoOpRelationBatchLoader;
import com.reactor.cachedb.core.relation.RelationBatchLoader;
import com.reactor.cachedb.redis.ProjectionRefreshDispatcher;
import com.reactor.cachedb.redis.RedisProjectionRefreshQueue;
import com.reactor.cachedb.redis.RedisEntityRepository;
import com.reactor.cachedb.redis.RedisFunctionExecutor;
import com.reactor.cachedb.redis.RedisHotSetManager;
import com.reactor.cachedb.redis.RedisKeyStrategy;
import com.reactor.cachedb.redis.RedisPageCacheManager;
import com.reactor.cachedb.redis.RedisProducerGuard;
import com.reactor.cachedb.redis.RedisQueryIndexManager;
import com.reactor.cachedb.core.queue.WriteBehindQueue;
import redis.clients.jedis.JedisPooled;

public final class DefaultCacheSession implements CacheSession {

    private final JedisPooled foregroundJedis;
    private final JedisPooled backgroundJedis;
    private final CacheDatabaseConfig config;
    private final EntityRegistry entityRegistry;
    private final WriteBehindQueue writeBehindQueue;
    private final RedisKeyStrategy redisKeyStrategy;
    private final RedisFunctionExecutor functionExecutor;
    private final RedisProducerGuard producerGuard;
    private final RedisHotSetManager hotSetManager;
    private final QueryEvaluator queryEvaluator;
    private final StoragePerformanceCollector performanceCollector;
    private final ProjectionRefreshDispatcher projectionRefreshDispatcher;
    private final RedisProjectionRefreshQueue projectionRefreshQueue;

    public DefaultCacheSession(
            JedisPooled foregroundJedis,
            JedisPooled backgroundJedis,
            CacheDatabaseConfig config,
            EntityRegistry entityRegistry,
            WriteBehindQueue writeBehindQueue,
            RedisKeyStrategy redisKeyStrategy,
            RedisFunctionExecutor functionExecutor,
            RedisProducerGuard producerGuard,
            RedisHotSetManager hotSetManager,
            QueryEvaluator queryEvaluator,
            StoragePerformanceCollector performanceCollector,
            ProjectionRefreshDispatcher projectionRefreshDispatcher,
            RedisProjectionRefreshQueue projectionRefreshQueue
    ) {
        this.foregroundJedis = foregroundJedis;
        this.backgroundJedis = backgroundJedis;
        this.config = config;
        this.entityRegistry = entityRegistry;
        this.writeBehindQueue = writeBehindQueue;
        this.redisKeyStrategy = redisKeyStrategy;
        this.functionExecutor = functionExecutor;
        this.producerGuard = producerGuard;
        this.hotSetManager = hotSetManager;
        this.queryEvaluator = queryEvaluator;
        this.performanceCollector = performanceCollector;
        this.projectionRefreshDispatcher = projectionRefreshDispatcher;
        this.projectionRefreshQueue = projectionRefreshQueue;
    }

    @Override
    public <T, ID> EntityRepository<T, ID> repository(
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec
    ) {
        EntityBinding<T, ID> binding = resolveBinding(metadata, codec, config.resourceLimits().defaultCachePolicy());
        CachePolicy resolvedCachePolicy = binding.cachePolicy() == null
                ? config.resourceLimits().defaultCachePolicy()
                : binding.cachePolicy();
        return repository(metadata, codec, resolvedCachePolicy);
    }

    @Override
    public <T, ID> EntityRepository<T, ID> repository(
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            CachePolicy cachePolicy
    ) {
        CachePolicy defaultPolicy = config.resourceLimits().defaultCachePolicy();
        EntityBinding<T, ID> binding = resolveBinding(metadata, codec, cachePolicy == null ? defaultPolicy : cachePolicy);
        RelationBatchLoader<T> relationBatchLoader = binding.relationBatchLoader() == null
                ? new NoOpRelationBatchLoader<>()
                : binding.relationBatchLoader();
        EntityPageLoader<T> pageLoader = binding.pageLoader() == null
                ? new NoOpEntityPageLoader<>()
                : binding.pageLoader();
        CachePolicy resolvedCachePolicy = cachePolicy != null
                ? cachePolicy
                : (binding.cachePolicy() == null ? defaultPolicy : binding.cachePolicy());
        RedisQueryIndexManager<T, ID> queryIndexManager = new RedisQueryIndexManager<>(
                foregroundJedis,
                metadata,
                codec,
                redisKeyStrategy,
                entityRegistry,
                config.queryIndex(),
                config.relations(),
                queryEvaluator,
                producerGuard
        );
        return new RedisEntityRepository<>(
                foregroundJedis,
                metadata,
                codec,
                writeBehindQueue,
                redisKeyStrategy,
                com.reactor.cachedb.core.plan.FetchPlan.empty(),
                resolvedCachePolicy,
                functionExecutor,
                producerGuard,
                config.redisGuardrail(),
                config.writeBehind().streamKey(),
                config.writeBehind().compactionStreamKey(),
                config.writeBehind().compactionShardCount(),
                entityRegistry,
                relationBatchLoader,
                config.relations(),
                config.queryIndex(),
                pageLoader,
                config.pageCache(),
                new RedisPageCacheManager<>(foregroundJedis, metadata, codec, resolvedCachePolicy, redisKeyStrategy, hotSetManager, queryIndexManager, producerGuard),
                queryEvaluator,
                queryIndexManager,
                performanceCollector,
                backgroundJedis,
                projectionRefreshDispatcher,
                projectionRefreshQueue
        );
    }

    @SuppressWarnings("unchecked")
    private <T, ID> EntityBinding<T, ID> resolveBinding(
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            CachePolicy cachePolicy
    ) {
        return (EntityBinding<T, ID>) entityRegistry.find(metadata.entityName())
                .orElseGet(() -> entityRegistry.register(
                        metadata,
                        codec,
                        cachePolicy,
                        new NoOpRelationBatchLoader<>(),
                        new NoOpEntityPageLoader<>()
                ));
    }
}

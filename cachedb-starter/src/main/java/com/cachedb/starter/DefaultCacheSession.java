package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.api.CacheSession;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.page.EntityByIdLoader;
import com.reactor.cachedb.core.page.EntityPageLoader;
import com.reactor.cachedb.core.page.EntityQueryLoader;
import com.reactor.cachedb.core.page.NoOpEntityByIdLoader;
import com.reactor.cachedb.core.page.NoOpEntityPageLoader;
import com.reactor.cachedb.core.page.NoOpEntityQueryLoader;
import com.reactor.cachedb.core.query.QueryEvaluator;
import com.reactor.cachedb.core.queue.StoragePerformanceCollector;
import com.reactor.cachedb.core.queue.WriteBehindQueue;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;
import com.reactor.cachedb.core.relation.NoOpRelationBatchLoader;
import com.reactor.cachedb.core.relation.RelationBatchLoader;
import com.reactor.cachedb.redis.JedisPooledOperations;
import com.reactor.cachedb.redis.ProjectionRefreshDispatcher;
import com.reactor.cachedb.redis.RedisClientOperations;
import com.reactor.cachedb.redis.RedisEntityRepository;
import com.reactor.cachedb.redis.RedisFunctionExecutor;
import com.reactor.cachedb.redis.RedisHotSetManager;
import com.reactor.cachedb.redis.RedisKeyStrategy;
import com.reactor.cachedb.redis.RedisPageCacheManager;
import com.reactor.cachedb.redis.RedisProducerGuard;
import com.reactor.cachedb.redis.RedisProjectionRefreshQueue;
import com.reactor.cachedb.redis.RedisQueryIndexManager;

import redis.clients.jedis.JedisPooled;

import java.util.concurrent.ConcurrentHashMap;

public final class DefaultCacheSession implements CacheSession {
    private com.reactor.cachedb.core.async.CacheAsyncExecutor asyncExecutor;
    DefaultCacheSession withAsyncExecutor(com.reactor.cachedb.core.async.CacheAsyncExecutor gate) {
        if (asyncExecutor != null && asyncExecutor != gate) throw new IllegalStateException("Async gate is already attached");
        asyncExecutor = gate;
        return this;
    }
    @Override public com.reactor.cachedb.core.async.CacheAsyncExecutor asyncExecutor() {
        if (asyncExecutor == null) throw new UnsupportedOperationException("No runtime-owned async gate; use async(executor)");
        return asyncExecutor;
    }
    private com.reactor.cachedb.redis.RedisNearCacheCoordinator nearCacheCoordinator;
    private com.reactor.cachedb.redis.RedisRepositoryReadState pointReads;
    DefaultCacheSession withReadRuntime(com.reactor.cachedb.redis.RedisRepositoryReadState state) {
        pointReads = state; return this;
    }
    DefaultCacheSession withNearCacheCoordinator(com.reactor.cachedb.redis.RedisNearCacheCoordinator coordinator) {
        nearCacheCoordinator = coordinator; return this;
    }
    @Override public boolean awaitDurable(com.reactor.cachedb.core.model.WriteReceipt<?, ?> receipt, java.time.Duration timeout) {
        return durabilityTracker.await(receipt, timeout, null);
    }
    @Override public boolean awaitDurable(com.reactor.cachedb.core.api.CacheWriteBatchReceipt receipt, java.time.Duration timeout) {
        return durabilityTracker.awaitBatch(receipt, timeout);
    }
    private final com.reactor.cachedb.redis.RedisDurabilityTracker durabilityTracker;
    @Override public boolean isDurable(com.reactor.cachedb.core.model.WriteReceipt<?, ?> receipt) {
        return durabilityTracker.isDurable(receipt);
    }
    @Override public boolean isDurable(com.reactor.cachedb.core.api.CacheWriteBatchReceipt receipt) {
        if (receipt.dependencies().isEmpty()) throw new IllegalArgumentException("Batch receipt has no durability dependencies");
        return durabilityTracker.areDurableDependencies(receipt.dependencies());
    }
    @Override public boolean areDurable(java.util.Collection<? extends com.reactor.cachedb.core.model.WriteReceipt<?, ?>> receipts) {
        return durabilityTracker.areDurable(receipts);
    }

    private final RedisClientOperations foregroundJedis;
    private final RedisClientOperations backgroundJedis;
    private final com.reactor.cachedb.core.config.CacheRuntimeOptions runtimeOptions;
    private final com.reactor.cachedb.core.trace.TraceContextPropagator observationWriteTrace;
    public com.reactor.cachedb.core.config.CacheRuntimeOptions runtimeOptions() { return runtimeOptions; }
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
    private final java.util.concurrent.locks.ReentrantLock protectionConfiguration = new java.util.concurrent.locks.ReentrantLock();
    private final java.util.Map<String, com.reactor.cachedb.redis.RedisTenantProducer> tenantProducers = new java.util.HashMap<>();
    private boolean protectionFrozen;
    private Object protectionRegistration;
    private final java.util.concurrent.locks.ReentrantLock[] repositoryInitialization = repositoryInitializationLocks();
    private final java.util.concurrent.atomic.AtomicReferenceArray<RepositoryInitializationFrame> repositoryInitializations =
            new java.util.concurrent.atomic.AtomicReferenceArray<>(64);

    private static java.util.concurrent.locks.ReentrantLock[] repositoryInitializationLocks() {
        var locks = new java.util.concurrent.locks.ReentrantLock[64];
        for (int i = 0; i < locks.length; i++) locks[i] = new java.util.concurrent.locks.ReentrantLock();
        return locks;
    }

    /** Configure before the first repository. Caller owns the returned producer for standalone sessions. */
    public com.reactor.cachedb.redis.RedisTenantProducer configureTenantProducer(EntityMetadata<?, ?> metadata, int maxInFlight) {
        return configureTenantProducer(metadata, maxInFlight, java.util.UUID.randomUUID(), null);
    }

    com.reactor.cachedb.redis.RedisTenantProducer configureTenantProducer(EntityMetadata<?, ?> metadata,
            int maxInFlight, java.util.UUID incarnation, Runnable committedNamespaceInitializer) {
        if (maxInFlight < 1 || maxInFlight > 4096) throw new com.reactor.cachedb.core.protection.ProtectionException(
                com.reactor.cachedb.core.protection.ProtectionException.Code.INVALID_REQUEST);
        if (!metadata.tenantPurgeEnabled() || metadata.tenantFieldName() == null
                || !metadata.indexedColumns().contains(metadata.tenantFieldName())
                || !config.redisFunctions().enabled()
                || !"/functions/cachedb-functions.lua".equals(config.redisFunctions().templateResourcePath())
                || config.redisFunctions().sourceOverride() != null && !config.redisFunctions().sourceOverride().isBlank())
            throw new IllegalArgumentException("Canonical functions and indexed tenant protection are required");
        Object registration = new Object();
        protectionConfiguration.lock();
        try {
            if (protectionRegistration != null || protectionFrozen || tenantProducers.size() >= 128 || tenantProducers.containsKey(metadata.redisNamespace()))
                throw new IllegalStateException("Protection configuration is frozen, duplicate or full");
            protectionRegistration = registration;
        } finally { protectionConfiguration.unlock(); }
        com.reactor.cachedb.redis.RedisTenantProducer producer = null;
        boolean published = false;
        try {
            if (committedNamespaceInitializer != null) committedNamespaceInitializer.run();
            producer = new com.reactor.cachedb.redis.RedisTenantProducer(
                    com.reactor.cachedb.redis.RedisGenerationBarrier.enrolled(foregroundJedis, redisKeyStrategy, metadata.redisNamespace(), incarnation),
                    metadata.redisNamespace(), maxInFlight);
            protectionConfiguration.lock();
            try {
                if (protectionFrozen || protectionRegistration != registration)
                    throw new com.reactor.cachedb.core.protection.ProtectionException(
                            com.reactor.cachedb.core.protection.ProtectionException.Code.RECOVERY_REQUIRED);
                tenantProducers.put(metadata.redisNamespace(), producer);
                published = true;
            } finally { protectionConfiguration.unlock(); }
            return producer;
        } finally {
            if (!published && producer != null) producer.close();
            protectionConfiguration.lock();
            try {
                if (protectionRegistration == registration) protectionRegistration = null;
                if (!published && committedNamespaceInitializer != null) protectionFrozen = true;
            } finally { protectionConfiguration.unlock(); }
        }
    }

    com.reactor.cachedb.redis.RedisTenantProducer configuredTenantProducer(EntityMetadata<?, ?> metadata) {
        protectionConfiguration.lock();
        try { return tenantProducers.get(metadata.redisNamespace()); }
        finally { protectionConfiguration.unlock(); }
    }

    private com.reactor.cachedb.redis.RedisTenantProducer freezeTenantProducer(EntityMetadata<?, ?> metadata) {
        protectionConfiguration.lock();
        try {
            if (protectionRegistration != null) throw new com.reactor.cachedb.core.protection.ProtectionException(
                    com.reactor.cachedb.core.protection.ProtectionException.Code.BUSY);
            protectionFrozen = true; return tenantProducers.get(metadata.redisNamespace());
        }
        finally { protectionConfiguration.unlock(); }
    }

    private final ConcurrentHashMap<RepositoryKey, EntityRepository<?, ?>> repositories =
            new ConcurrentHashMap<>();

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
            RedisProjectionRefreshQueue projectionRefreshQueue) {
        this(
                JedisPooledOperations.of(foregroundJedis),
                JedisPooledOperations.of(backgroundJedis),
                config,
                entityRegistry,
                writeBehindQueue,
                redisKeyStrategy,
                functionExecutor,
                producerGuard,
                hotSetManager,
                queryEvaluator,
                performanceCollector,
                projectionRefreshDispatcher,
                projectionRefreshQueue);
    }

    public DefaultCacheSession(
            RedisClientOperations foregroundJedis,
            RedisClientOperations backgroundJedis,
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
            RedisProjectionRefreshQueue projectionRefreshQueue) {
        this(foregroundJedis, backgroundJedis, config, entityRegistry, writeBehindQueue, redisKeyStrategy,
                functionExecutor, producerGuard, hotSetManager, queryEvaluator, performanceCollector,
                projectionRefreshDispatcher, projectionRefreshQueue,
                com.reactor.cachedb.core.config.CacheRuntimeOptions.defaults());
    }

    public DefaultCacheSession(
            RedisClientOperations foregroundJedis,
            RedisClientOperations backgroundJedis,
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
            RedisProjectionRefreshQueue projectionRefreshQueue,
            com.reactor.cachedb.core.config.CacheRuntimeOptions runtimeOptions) {
        this.runtimeOptions = java.util.Objects.requireNonNull(runtimeOptions, "runtimeOptions");
        this.observationWriteTrace = runtimeOptions.observationRecorder().enabled()
                && runtimeOptions.observationTraceparentSupplier() != com.reactor.cachedb.core.observe.CacheTraceparentSupplier.NONE
                ? new com.reactor.cachedb.core.observe.ObservingTraceContextPropagator(
                        config.traceContextPropagator(), runtimeOptions.observationTraceparentSupplier(), true)
                : null;
        this.foregroundJedis = foregroundJedis;
        this.durabilityTracker = new com.reactor.cachedb.redis.RedisDurabilityTracker(backgroundJedis, redisKeyStrategy);
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
            EntityMetadata<T, ID> metadata, EntityCodec<T> codec) {
        EntityBinding<T, ID> binding =
                resolveBinding(metadata, codec, config.resourceLimits().defaultCachePolicy());
        CachePolicy resolvedCachePolicy =
                binding.cachePolicy() == null
                        ? config.resourceLimits().defaultCachePolicy()
                        : binding.cachePolicy();
        return repository(metadata, codec, resolvedCachePolicy);
    }

    @Override
    @SuppressWarnings("unchecked") // The registered entity and policy select this typed factory result.
    public <T, ID> EntityRepository<T, ID> repository(
            EntityMetadata<T, ID> metadata, EntityCodec<T> codec, CachePolicy cachePolicy) {
        CachePolicy defaultPolicy = config.resourceLimits().defaultCachePolicy();
        EntityBinding<T, ID> binding =
                resolveBinding(metadata, codec, cachePolicy == null ? defaultPolicy : cachePolicy);
        CachePolicy resolvedCachePolicy =
                cachePolicy != null
                        ? cachePolicy
                        : (binding.cachePolicy() == null ? defaultPolicy : binding.cachePolicy());
        RepositoryKey repositoryKey = new RepositoryKey(metadata.entityName(), resolvedCachePolicy);
        var existing = repositories.get(repositoryKey);
        if (existing != null) return (EntityRepository<T, ID>) existing;
        Thread owner = Thread.currentThread();
        boolean nested = false;
        for (int i = 0; i < repositoryInitializations.length(); i++) {
            var frame = repositoryInitializations.get(i);
            if (frame != null && frame.owner() == owner) {
                if (frame.key().equals(repositoryKey)) throw new IllegalStateException("Recursive repository initialization");
                nested = true;
            }
        }
        int hash = repositoryKey.hashCode();
        int stripe = (hash ^ (hash >>> 16)) & (repositoryInitialization.length - 1);
        var initialization = repositoryInitialization[stripe];
        // Nested constructors may reenter a free/reentrant stripe, but never wait while holding another stripe.
        if (nested) {
            if (!initialization.tryLock()) throw new IllegalStateException("Nested repository initialization is contended");
        } else initialization.lock();
        try {
            existing = repositories.get(repositoryKey);
            if (existing != null) return (EntityRepository<T, ID>) existing;
            var frame = new RepositoryInitializationFrame(owner, repositoryKey);
            int frameSlot = 0;
            while (frameSlot < repositoryInitializations.length()
                    && !repositoryInitializations.compareAndSet(frameSlot, null, frame)) frameSlot++;
            if (frameSlot == repositoryInitializations.length())
                throw new IllegalStateException("Repository initialization capacity exhausted");
            try {
                RelationBatchLoader<T> relationBatchLoader = binding.relationBatchLoader() == null
                        ? new NoOpRelationBatchLoader<>() : binding.relationBatchLoader();
                EntityPageLoader<T> pageLoader = binding.pageLoader() == null ? new NoOpEntityPageLoader<>() : binding.pageLoader();
                EntityByIdLoader<T, ID> byIdLoader = binding.byIdLoader() == null ? new NoOpEntityByIdLoader<>() : binding.byIdLoader();
                EntityQueryLoader<T> queryLoader = binding.queryLoader() == null ? new NoOpEntityQueryLoader<>() : binding.queryLoader();
                // Constructors can perform Redis I/O; no CHM callback/monitor may surround them.
                var created = createRepository(metadata, binding.codec(), resolvedCachePolicy,
                        relationBatchLoader, pageLoader, byIdLoader, queryLoader);
                repositories.put(repositoryKey, created);
                return created;
            } finally { repositoryInitializations.set(frameSlot, null); }
        } finally { initialization.unlock(); }
    }

    @Override public <T, ID> EntityCodec<T> runtimeCodec(EntityMetadata<T, ID> metadata, EntityCodec<T> fallback) {
        var binding = resolveBinding(metadata, fallback, config.resourceLimits().defaultCachePolicy());
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.sameMapping(metadata, binding.metadata());
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.requireBound(metadata, binding.codec());
        return binding.codec();
    }

    private <T, ID> EntityRepository<T, ID> createRepository(
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            CachePolicy resolvedCachePolicy,
            RelationBatchLoader<T> relationBatchLoader,
            EntityPageLoader<T> pageLoader,
            EntityByIdLoader<T, ID> byIdLoader,
            EntityQueryLoader<T> queryLoader) {
        var protectedProducer = freezeTenantProducer(metadata);
        RedisQueryIndexManager<T, ID> queryIndexManager =
                new RedisQueryIndexManager<>(
                        foregroundJedis,
                        metadata,
                        codec,
                        redisKeyStrategy,
                        entityRegistry,
                        config.queryIndex(),
                        config.relations(),
                        queryEvaluator,
                        producerGuard);
        var repository = new RedisEntityRepository<>(
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
                redisKeyStrategy.writeStreamKey(config.writeBehind().streamKey(), metadata.redisNamespace()),
                config.writeBehind().compactionStreamKey(),
                config.writeBehind().compactionShardCount(),
                entityRegistry,
                relationBatchLoader,
                config.relations(),
                config.queryIndex(),
                pageLoader,
                byIdLoader,
                queryLoader,
                config.pageCache(),
                config.readThrough(),
                config.readShapeGuardrail(),
                new RedisPageCacheManager<>(
                        foregroundJedis,
                        metadata,
                        codec,
                        resolvedCachePolicy,
                        redisKeyStrategy,
                        hotSetManager,
                        queryIndexManager,
                        producerGuard,
                        config.readShapeGuardrail(),
                        performanceCollector),
                queryEvaluator,
                queryIndexManager,
                performanceCollector,
                backgroundJedis,
                projectionRefreshDispatcher,
                projectionRefreshQueue).withMutationConfig(config.mutations())
                .withWatcherLimit(config.resourceLimits().maxWatchersPerEntity())
                .withNearCache(nearCacheCoordinator).withReadRuntime(pointReads).withAsyncExecutor(asyncExecutor)
                .withObservationRecorder(runtimeOptions.observationRecorder(), observationWriteTrace);
        if (protectedProducer != null) repository.withTenantProducer(protectedProducer);
        return repository;
    }

    void stopTenantProducers() {
        protectionConfiguration.lock();
        try {
            protectionFrozen = true;
            tenantProducers.values().forEach(com.reactor.cachedb.redis.RedisTenantProducer::close);
        } finally { protectionConfiguration.unlock(); }
    }

    private record RepositoryKey(String entityName, CachePolicy cachePolicy) {}
    private record RepositoryInitializationFrame(Thread owner, RepositoryKey key) {}

    @SuppressWarnings("unchecked")
    private <T, ID> EntityBinding<T, ID> resolveBinding(
            EntityMetadata<T, ID> metadata, EntityCodec<T> codec, CachePolicy cachePolicy) {
        var existing = entityRegistry.find(metadata.entityName());
        if (existing.isPresent() && (metadata.tenantPurgeEnabled() != existing.get().metadata().tenantPurgeEnabled()
                || !metadata.redisNamespace().equals(existing.get().metadata().redisNamespace())))
            throw new IllegalArgumentException("Registered namespace/protection metadata cannot be replaced");
        if (existing.isPresent() && (com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata)
                || com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(existing.get().metadata())))
            com.reactor.cachedb.core.crypto.EncryptedStorageChecks.sameMapping(metadata, existing.get().metadata());
        return (EntityBinding<T, ID>) existing.orElseGet(
                                () ->
                                        entityRegistry.register(
                                                metadata,
                                                codec,
                                                cachePolicy,
                                                new NoOpRelationBatchLoader<>(),
                                                new NoOpEntityPageLoader<>()));
    }
}

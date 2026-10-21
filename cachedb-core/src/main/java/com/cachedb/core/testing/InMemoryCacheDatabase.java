package com.reactor.cachedb.core.testing;

import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.model.*;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Infrastructure-free unit-test session. SQL durability must be acknowledged explicitly. */
public final class InMemoryCacheDatabase implements CacheSession {
    final ReentrantLock lock = new ReentrantLock();

    record BatchState(List<String> signature, CacheWriteBatchReceipt receipt) {}

    final Map<String, BatchState> batches = new ConcurrentHashMap<>();
    private final Map<Class<?>, InMemoryEntityRepository<?, ?>> repositories =
            new ConcurrentHashMap<>();

    private record Identity(String namespace, String id) {
        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(namespace) + Objects.hashCode(id);
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof Identity value
                            && Objects.equals(namespace, value.namespace)
                            && Objects.equals(id, value.id);
        }
    }

    private final Map<Identity, Long> durable = new ConcurrentHashMap<>();
    private final Clock clock;
    private final com.reactor.cachedb.core.repository.CacheIdGenerator idGenerator;
    private final Map<Identity, com.reactor.cachedb.core.route.RouteCoverage> coverage =
            new ConcurrentHashMap<>();

    public InMemoryCacheDatabase() {
        this(Clock.systemUTC());
    }

    public InMemoryCacheDatabase(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        this.idGenerator = new InMemoryIdGenerator(clock);
    }

    public com.reactor.cachedb.core.repository.CacheIdGenerator idGenerator() {
        return idGenerator;
    }

    public <T, ID> InMemoryEntityRepository<T, ID> repository(
            EntityMetadata<T, ID> metadata, EntityCodec<T> codec) {
        if (repositories.containsKey(metadata.entityType()))
            return registered(metadata.entityType());
        return repository(metadata, codec, CachePolicy.defaults());
    }

    @SuppressWarnings("unchecked")
    public <T, ID> InMemoryEntityRepository<T, ID> repository(
            EntityMetadata<T, ID> metadata, EntityCodec<T> codec, CachePolicy policy) {
        lock.lock();
        try {
            var repository =
                    (InMemoryEntityRepository<T, ID>) repositories.get(metadata.entityType());
            if (repository == null) {
                repository = new InMemoryEntityRepository<>(metadata, codec, policy, clock, lock);
                repositories.put(metadata.entityType(), repository);
            }
            if ((repository.policy != policy && !repository.policy.equals(policy))
                    || !repository.metadata.redisNamespace().equals(metadata.redisNamespace()))
                throw new IllegalArgumentException(
                        "In-memory fixtures use one explicit policy/namespace per entity type; use"
                                + " another test session for another policy");
            return repository;
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    <T, ID> InMemoryEntityRepository<T, ID> registered(Class<T> type) {
        var result = repositories.get(type);
        if (result == null)
            throw new IllegalArgumentException(
                    "Register test metadata/codec before writing " + type.getName());
        return (InMemoryEntityRepository<T, ID>) result;
    }

    public InMemoryCacheWriteBatch writeBatch() {
        return new InMemoryCacheWriteBatch(this, idGenerator.nextUuid().toString());
    }

    public InMemoryCacheWriteBatch writeBatch(String id) {
        return new InMemoryCacheWriteBatch(this, id);
    }

    public void markDurable(WriteReceipt<?, ?> receipt) {
        markDurable(receipt.asDependency());
    }

    public void markDurable(CacheWriteBatchReceipt receipt) {
        for (WriteDependency dependency : receipt.dependencies()) markDurable(dependency);
    }

    private void markDurable(WriteDependency value) {
        var identity = new Identity(value.redisNamespace(), value.id());
        lock.lock();
        try {
            long prior = durable.getOrDefault(identity, 0L);
            if (value.version() > prior) durable.put(identity, value.version());
        } finally {
            lock.unlock();
        }
    }

    public boolean isDurable(WriteReceipt<?, ?> receipt) {
        return isDurable(receipt.asDependency());
    }

    public boolean isDurable(CacheWriteBatchReceipt receipt) {
        if (receipt.dependencies().isEmpty())
            throw new IllegalArgumentException("Batch receipt has no durability dependencies");
        for (WriteDependency dependency : receipt.dependencies())
            if (!isDurable(dependency)) return false;
        return true;
    }

    private boolean isDurable(WriteDependency value) {
        return durable.getOrDefault(new Identity(value.redisNamespace(), value.id()), 0L)
                >= value.version();
    }

    public void putCoverage(com.reactor.cachedb.core.route.RouteCoverage value) {
        coverage.put(new Identity(value.routeName(), value.scope()), value);
    }

    public com.reactor.cachedb.core.route.RouteCoverage routeCoverage(
            String name, String scope, java.time.Duration maxAge) {
        var key = new Identity(name, scope == null || scope.isBlank() ? "global" : scope);
        return coverage.getOrDefault(
                        key, com.reactor.cachedb.core.route.RouteCoverage.notWarmed(name, scope))
                .withStaleness(maxAge, clock.instant());
    }

    public <T, ID, P>
            com.reactor.cachedb.core.projection.EntityProjectionBinding<T, P, ID>
                    registerProjection(
                            EntityMetadata<T, ID> metadata,
                            com.reactor.cachedb.core.projection.EntityProjection<T, P, ID>
                                    projection) {
        return new com.reactor.cachedb.core.projection.EntityProjectionBinding<>(
                metadata.entityName(), projection);
    }

    public <T, ID> SourceRepository<T, ID> sourceRepository(
            EntityMetadata<T, ID> metadata, EntityCodec<T> codec) {
        return new SourceRepository<>() {
            private UnsupportedOperationException absent() {
                return new UnsupportedOperationException(
                        "No SQL source fixture; in-memory tests never silently load the database");
            }

            public Optional<T> findById(ID id) {
                throw absent();
            }

            public List<T> findPage(com.reactor.cachedb.core.cache.PageWindow window) {
                throw absent();
            }

            public List<T> query(com.reactor.cachedb.core.query.QuerySpec query) {
                throw absent();
            }
        };
    }
}

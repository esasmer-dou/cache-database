package com.reactor.cachedb.core.api;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.projection.EntityProjectionBinding;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.repository.CacheIdGenerator;
import com.reactor.cachedb.core.repository.SourceSqlRepository;
import com.reactor.cachedb.core.repository.WriteBatchDurabilityTimeoutException;
import com.reactor.cachedb.core.repository.WriteDurabilityTimeoutException;
import com.reactor.cachedb.core.route.RouteCoverage;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;

public interface CacheSession {
    default boolean isDurable(WriteReceipt<?, ?> receipt) {
        throw new UnsupportedOperationException(
                "Durability tracking is not supported by this CacheSession");
    }

    default boolean isDurable(CacheWriteBatchReceipt receipt) {
        throw new UnsupportedOperationException(
                "Batch durability tracking is not supported by this CacheSession");
    }

    default boolean awaitDurable(WriteReceipt<?, ?> receipt, Duration timeout) {
        Objects.requireNonNull(receipt, "receipt");
        return DurabilityWait.await(() -> isDurable(receipt), timeout);
    }

    default boolean awaitDurable(CacheWriteBatchReceipt receipt, Duration timeout) {
        Objects.requireNonNull(receipt, "receipt");
        return DurabilityWait.await(() -> isDurable(receipt), timeout);
    }

    default <T, ID> WriteReceipt<T, ID> awaitDurableOrThrow(WriteReceipt<T, ID> receipt, Duration timeout) {
        if (!awaitDurable(receipt, timeout))
            throw new WriteDurabilityTimeoutException(receipt, timeout);
        return receipt;
    }

    default boolean areDurable(Collection<? extends WriteReceipt<?, ?>> receipts) {
        Objects.requireNonNull(receipts, "receipts");
        return receipts.stream().allMatch(this::isDurable);
    }

    default <C extends Collection<? extends WriteReceipt<?, ?>>> C awaitDurableOrThrow(
            C receipts, Duration timeout, String operation) {
        Objects.requireNonNull(receipts, "receipts");
        if (!DurabilityWait.await(() -> areDurable(receipts), timeout))
            throw new WriteBatchDurabilityTimeoutException(receipts, timeout, operation);
        return receipts;
    }

    default <T> SourceSqlRepository<T> sourceSqlRepository(EntityCodec<T> codec) {
        return query -> {
            throw new UnsupportedOperationException(
                    "This session has no explicit SQL source fixture");
        };
    }

    default <T, ID, P> EntityProjectionBinding<T, P, ID> registerProjection(
            EntityMetadata<T, ID> metadata, EntityProjection<T, P, ID> projection) {
        throw new UnsupportedOperationException(
                "Projection registration is not supported by this session");
    }

    default CacheIdGenerator idGenerator() {
        throw new UnsupportedOperationException(
                "Register an ID generator fixture for this session");
    }

    default RouteCoverage routeCoverage(String routeName, String scope, Duration maxAge) {
        return RouteCoverage.notWarmed(routeName, scope);
    }

    <T, ID> EntityRepository<T, ID> repository(
            EntityMetadata<T, ID> metadata, EntityCodec<T> codec);

    <T, ID> EntityRepository<T, ID> repository(
            EntityMetadata<T, ID> metadata, EntityCodec<T> codec, CachePolicy cachePolicy);

    default <T, ID> SourceRepository<T, ID> sourceRepository(
            EntityMetadata<T, ID> metadata, EntityCodec<T> codec) {
        throw new UnsupportedOperationException(
                "Durable source reads are not supported by this CacheSession implementation");
    }

    default <T, ID> EntityRepository<T, ID> repository(EntityBinding<T, ID> binding) {
        return repository(binding.metadata(), binding.codec(), binding.cachePolicy());
    }
}

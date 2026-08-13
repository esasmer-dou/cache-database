package com.reactor.cachedb.core.repository;

import com.reactor.cachedb.core.model.WriteDependency;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.page.VersionedEntity;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Primary application-facing repository contract. Hot reads and source reads are deliberately separate.
 */
public interface CacheDbRepository<T, ID> {
    HotLookup<T> findHotById(ID id);

    Optional<VersionedEntity<T>> findVersionedHotById(ID id);

    /** Returns a dependency only while the parent has a Redis version that may still be pending in SQL. */
    default Optional<WriteDependency> dependency(ID id) {
        return Optional.empty();
    }

    Optional<T> findSourceById(ID id);

    WriteReceipt<T, ID> save(T entity);

    WriteReceipt<T, ID> save(T entity, long expectedVersion);

    WriteReceipt<T, ID> saveAfter(T entity, WriteDependency dependency);

    List<WriteReceipt<T, ID>> saveAll(Collection<T> entities);

    WriteReceipt<T, ID> deleteById(ID id);

    boolean isDurable(WriteReceipt<?, ?> receipt);

    boolean awaitDurable(WriteReceipt<?, ?> receipt, Duration timeout);

    /** Waits explicitly for SQL durability and returns the original typed receipt. */
    default <R extends WriteReceipt<?, ?>> R awaitDurableOrThrow(R receipt, Duration timeout) {
        if (receipt == null) {
            throw new IllegalArgumentException("receipt must not be null");
        }
        requireDurabilityTimeout(timeout);
        if (!awaitDurable(receipt, timeout)) {
            throw new WriteDurabilityTimeoutException(receipt, timeout);
        }
        return receipt;
    }

    /** Redis-first save followed by an explicit, bounded SQL durability wait. */
    default WriteReceipt<T, ID> saveDurably(T entity, Duration timeout) {
        requireDurabilityTimeout(timeout);
        return awaitDurableOrThrow(save(entity), timeout);
    }

    /** Optimistic Redis-first save followed by an explicit, bounded SQL durability wait. */
    default WriteReceipt<T, ID> saveDurably(T entity, long expectedVersion, Duration timeout) {
        requireDurabilityTimeout(timeout);
        return awaitDurableOrThrow(save(entity, expectedVersion), timeout);
    }

    /** Dependency-aware save followed by an explicit, bounded SQL durability wait. */
    default WriteReceipt<T, ID> saveAfterDurably(
            T entity,
            WriteDependency dependency,
            Duration timeout
    ) {
        requireDurabilityTimeout(timeout);
        return awaitDurableOrThrow(saveAfter(entity, dependency), timeout);
    }

    /** Redis tombstone followed by an explicit, bounded SQL durability wait. */
    default WriteReceipt<T, ID> deleteDurably(ID id, Duration timeout) {
        requireDurabilityTimeout(timeout);
        return awaitDurableOrThrow(deleteById(id), timeout);
    }

    default WriteReceipt<T, ID> updateHot(ID id, UnaryOperator<T> update) {
        if (update == null) {
            throw new IllegalArgumentException("update must not be null");
        }
        VersionedEntity<T> current = findVersionedHotById(id)
                .orElseThrow(() -> new HotUpdateUnavailableException(id));
        T updated = update.apply(current.entity());
        if (updated == null) {
            throw new IllegalArgumentException("update must return a full entity");
        }
        return save(updated, current.version());
    }

    /** Full hot-entity update followed by an explicit, bounded SQL durability wait. */
    default WriteReceipt<T, ID> updateHotDurably(
            ID id,
            UnaryOperator<T> update,
            Duration timeout
    ) {
        requireDurabilityTimeout(timeout);
        return awaitDurableOrThrow(updateHot(id, update), timeout);
    }

    private static void requireDurabilityTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be greater than zero");
        }
    }
}

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
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be greater than zero");
        }
        if (!awaitDurable(receipt, timeout)) {
            throw new WriteDurabilityTimeoutException(receipt, timeout);
        }
        return receipt;
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
}

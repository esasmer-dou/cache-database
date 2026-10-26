package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.api.*;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/** One bounded Redis envelope; SQL durability can be explicitly awaited after acceptance. */
public final class CacheTransactionTemplate {
    private final CacheDatabase database;
    public CacheTransactionTemplate(CacheDatabase database) { this.database = Objects.requireNonNull(database); }
    public CacheWriteBatchReceipt execute(Consumer<CacheUnitOfWork> commands) {
        return execute(commands, false, Duration.ofSeconds(5));
    }
    public CacheWriteBatchReceipt execute(Consumer<CacheUnitOfWork> commands, boolean awaitDurable, Duration timeout) {
        Objects.requireNonNull(commands); Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("Transaction timeout must be within (0, 5 minutes]");
        CacheWriteBatchReceipt receipt;
        try (var work = CacheUnitOfWork.begin(database.entityRegistry(), database.writeBatch())) {
            commands.accept(work); receipt = work.commit();
        }
        if (awaitDurable && !database.awaitDurable(receipt, timeout))
            throw new com.reactor.cachedb.core.repository.CacheTransactionDurabilityTimeoutException(receipt, timeout);
        return receipt;
    }
}

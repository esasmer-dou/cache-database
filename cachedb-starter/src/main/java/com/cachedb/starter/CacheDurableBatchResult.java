package com.reactor.cachedb.starter;

/** Bounded batch-write accounting returned after every receipt is SQL durable. */
public record CacheDurableBatchResult(
        String operation,
        long submittedRows,
        int writeBatches,
        int durabilityWaits
) {
}

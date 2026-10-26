package com.reactor.cachedb.core.repository;

import com.reactor.cachedb.core.api.CacheWriteBatchReceipt;
import java.time.Duration;

/** Acceptance is NOT rolled back on a durability timeout. Inspect the attached receipt. */
public final class CacheTransactionDurabilityTimeoutException extends RuntimeException {
    private final CacheWriteBatchReceipt receipt;
    public CacheTransactionDurabilityTimeoutException(CacheWriteBatchReceipt receipt, Duration timeout) {
        super("Atomic batch was accepted but SQL durability was not confirmed within " + timeout);
        this.receipt = receipt;
    }
    public CacheWriteBatchReceipt receipt() { return receipt; }
}

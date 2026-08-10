package com.reactor.cachedb.core.repository;

import com.reactor.cachedb.core.model.WriteReceipt;

import java.time.Duration;

public final class WriteDurabilityTimeoutException extends IllegalStateException {
    public WriteDurabilityTimeoutException(WriteReceipt<?, ?> receipt, Duration timeout) {
        super("CacheDB write was accepted by Redis but did not become SQL-durable within "
                + timeout + ": namespace=" + receipt.redisNamespace()
                + ", id=" + receipt.id() + ", version=" + receipt.version());
    }
}

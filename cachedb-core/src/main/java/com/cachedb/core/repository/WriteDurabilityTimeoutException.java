package com.reactor.cachedb.core.repository;

import com.reactor.cachedb.core.model.WriteReceipt;

import java.time.Duration;
import java.util.Objects;

public final class WriteDurabilityTimeoutException extends IllegalStateException {
    private final WriteReceipt<?, ?> receipt;
    private final Duration timeout;

    public WriteDurabilityTimeoutException(WriteReceipt<?, ?> receipt, Duration timeout) {
        super(message(receipt, timeout));
        this.receipt = receipt;
        this.timeout = timeout;
    }

    public WriteReceipt<?, ?> receipt() {
        return receipt;
    }

    public Duration timeout() {
        return timeout;
    }

    private static String message(WriteReceipt<?, ?> receipt, Duration timeout) {
        Objects.requireNonNull(receipt, "receipt");
        requirePositive(timeout);
        return "CacheDB write was accepted by Redis but did not become SQL-durable within "
                + timeout + ": namespace=" + receipt.redisNamespace()
                + ", id=" + receipt.id() + ", version=" + receipt.version();
    }

    private static void requirePositive(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be greater than zero");
        }
    }
}

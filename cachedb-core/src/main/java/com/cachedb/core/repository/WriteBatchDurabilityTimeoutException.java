package com.reactor.cachedb.core.repository;

import com.reactor.cachedb.core.model.WriteReceipt;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Raised when a bounded receipt batch does not become SQL-durable in time. */
public final class WriteBatchDurabilityTimeoutException extends IllegalStateException {
    private final List<WriteReceipt<?, ?>> receipts;
    private final Duration timeout;
    private final String operation;

    public WriteBatchDurabilityTimeoutException(
            Collection<? extends WriteReceipt<?, ?>> receipts,
            Duration timeout
    ) {
        this(receipts, timeout, "");
    }

    public WriteBatchDurabilityTimeoutException(
            Collection<? extends WriteReceipt<?, ?>> receipts,
            Duration timeout,
            String operation
    ) {
        super(message(receipts, timeout, operation));
        this.receipts = List.copyOf(receipts);
        this.timeout = timeout;
        this.operation = operation == null ? "" : operation.trim();
    }

    public List<WriteReceipt<?, ?>> receipts() {
        return receipts;
    }

    public Duration timeout() {
        return timeout;
    }

    public String operation() {
        return operation;
    }

    private static String message(
            Collection<? extends WriteReceipt<?, ?>> receipts,
            Duration timeout,
            String operation
    ) {
        Objects.requireNonNull(receipts, "receipts");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be greater than zero");
        }
        for (WriteReceipt<?, ?> receipt : receipts) {
            Objects.requireNonNull(receipt, "receipts must not contain null");
        }
        int count = receipts.size();
        String context = operation == null || operation.isBlank() ? "" : " for " + operation.trim();
        return "CacheDB batch" + context + " containing " + count
                + " receipt(s) was accepted by Redis but did not become fully SQL-durable within " + timeout;
    }
}

package com.reactor.cachedb.core.async;

import java.util.concurrent.RejectedExecutionException;

/** Deterministic admission failure; no repository operation was started. */
public final class CacheAsyncRejectedException extends RejectedExecutionException {
    public enum Reason { SATURATED, CLOSED, EXECUTOR_REJECTED, INLINE_EXECUTOR }
    private final Reason reason;

    public CacheAsyncRejectedException(Reason reason) {
        super("CacheDB async admission rejected: " + reason);
        this.reason = reason;
    }

    public CacheAsyncRejectedException(Reason reason, Throwable cause) {
        super("CacheDB async admission rejected: " + reason, cause);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}

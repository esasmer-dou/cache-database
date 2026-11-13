package com.reactor.cachedb.core.config;

/** Bounds transport batching only; tenant, causal and decoding policies remain with the caller. */
public record RedisReadCoalescingConfig(boolean enabled, int maxBatchKeys, int maxQueuedRequests,
        int maxInFlightBatches, long maxQueuedBytes, long maxReplyBytes, long timeoutMillis,
        int maxAsyncDirect, long maxLiveReplyBytes) {
    public static final int HARD_BATCH_IDENTITIES = 512;
    public static final int HARD_QUEUED_REQUESTS = 4096;
    public static final int HARD_IN_FLIGHT = 1024;
    public static final int HARD_ASYNC_DIRECT = 1024;
    public static final long HARD_BYTES = 16L * 1024 * 1024;
    public static final long HARD_LIVE_REPLY_BYTES = 256L * 1024 * 1024;

    public RedisReadCoalescingConfig {
        if (maxBatchKeys < 1 || maxBatchKeys > HARD_BATCH_IDENTITIES
                || maxQueuedRequests < 0 || maxQueuedRequests > HARD_QUEUED_REQUESTS
                || maxInFlightBatches < 0 || maxInFlightBatches > HARD_IN_FLIGHT
                || maxQueuedBytes < 1 || maxQueuedBytes > HARD_BYTES
                || maxReplyBytes < 1 || maxReplyBytes > HARD_BYTES
                || timeoutMillis < 1 || timeoutMillis > 300_000
                || maxAsyncDirect < 0 || maxAsyncDirect > HARD_ASYNC_DIRECT
                || maxLiveReplyBytes < maxReplyBytes || maxLiveReplyBytes > HARD_LIVE_REPLY_BYTES)
            throw new IllegalArgumentException("Invalid bounded Redis read coalescing configuration");
    }

    public RedisReadCoalescingConfig(boolean enabled, int maxBatchKeys, int maxQueuedRequests,
            int maxInFlightBatches, long maxQueuedBytes, long maxReplyBytes, long timeoutMillis,
            int maxAsyncDirect) {
        this(enabled, maxBatchKeys, maxQueuedRequests, maxInFlightBatches, maxQueuedBytes,
                maxReplyBytes, timeoutMillis, maxAsyncDirect, HARD_LIVE_REPLY_BYTES);
    }

    /** Zero preserves max(2, poolMaxTotal / 2); a derived value over the hard bound fails closed. */
    public int resolveMaxInFlight(int maxPool) {
        if (maxPool < 1) throw new IllegalArgumentException("Redis pool size must be positive");
        int resolved = maxInFlightBatches == 0 ? Math.max(2, maxPool / 2) : maxInFlightBatches;
        if (resolved > HARD_IN_FLIGHT)
            throw new IllegalArgumentException("Pool-derived read concurrency exceeds 1024; configure an explicit bound");
        return resolved;
    }

    public static RedisReadCoalescingConfig defaults() { return builder().build(); }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private boolean enabled;
        private int maxBatchKeys = 512;
        private int maxQueuedRequests = 4096;
        private int maxInFlightBatches;
        private long maxQueuedBytes = HARD_BYTES;
        private long maxReplyBytes = HARD_BYTES;
        private long timeoutMillis = 5000;
        private int maxAsyncDirect = 16;
        private long maxLiveReplyBytes = HARD_LIVE_REPLY_BYTES;

        public Builder enabled(boolean value) { enabled = value; return this; }
        public Builder maxBatchKeys(int value) { maxBatchKeys = value; return this; }
        public Builder maxQueuedRequests(int value) { maxQueuedRequests = value; return this; }
        public Builder maxInFlightBatches(int value) { maxInFlightBatches = value; return this; }
        public Builder maxQueuedBytes(long value) { maxQueuedBytes = value; return this; }
        public Builder maxReplyBytes(long value) { maxReplyBytes = value; return this; }
        public Builder timeoutMillis(long value) { timeoutMillis = value; return this; }
        public Builder maxAsyncDirect(int value) { maxAsyncDirect = value; return this; }
        public Builder maxLiveReplyBytes(long value) { maxLiveReplyBytes = value; return this; }

        public RedisReadCoalescingConfig build() {
            return new RedisReadCoalescingConfig(enabled, maxBatchKeys, maxQueuedRequests,
                    maxInFlightBatches, maxQueuedBytes, maxReplyBytes, timeoutMillis, maxAsyncDirect, maxLiveReplyBytes);
        }
    }
}

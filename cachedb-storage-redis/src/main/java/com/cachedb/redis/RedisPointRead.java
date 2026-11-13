package com.reactor.cachedb.redis;

/** Raw point-read transport result. Null payload/tombstone means a missing Redis string. */
public record RedisPointRead(String payload, String tombstone, long ttlMillis, long observedAtNanos) {
    /** Distinct from Redis PTTL's -1 (persistent) and -2 (missing). */
    public static final long TTL_NOT_REQUESTED = -3;
}

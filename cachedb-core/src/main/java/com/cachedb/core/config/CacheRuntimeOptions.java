package com.reactor.cachedb.core.config;

import java.util.Objects;
import com.reactor.cachedb.core.crypto.CacheKeyProvider;
import com.reactor.cachedb.core.observe.CacheObservationRecorder;
import com.reactor.cachedb.core.observe.NoOpCacheObservationRecorder;

/** Additive runtime features, deliberately not a record: the base config's public record shape is stable. */
public final class CacheRuntimeOptions {
    private final RedisReadCoalescingConfig redisReadCoalescing;
    private final CacheKeyProvider keyProvider;
    private final int asyncMaxConcurrent;
    private final java.util.concurrent.Executor asyncExecutor;
    private final NearCacheInvalidationConfig nearCacheInvalidation;
    private final CacheObservationRecorder observationRecorder;
    private final com.reactor.cachedb.core.observe.CacheTraceparentSupplier observationTraceparentSupplier;

    private CacheRuntimeOptions(Builder builder) {
        redisReadCoalescing = Objects.requireNonNull(builder.redisReadCoalescing, "redisReadCoalescing");
        keyProvider = builder.keyProvider;
        asyncMaxConcurrent = builder.asyncMaxConcurrent;
        asyncExecutor = builder.asyncExecutor;
        nearCacheInvalidation = Objects.requireNonNull(builder.nearCacheInvalidation, "nearCacheInvalidation");
        observationRecorder = Objects.requireNonNull(builder.observationRecorder, "observationRecorder");
        observationTraceparentSupplier = Objects.requireNonNull(builder.observationTraceparentSupplier, "observationTraceparentSupplier");
    }
    /** Never stringify future provider/credential-bearing runtime fields. */
    @Override public String toString() { return "CacheRuntimeOptions"; }
    public RedisReadCoalescingConfig redisReadCoalescing() { return redisReadCoalescing; }
    public CacheKeyProvider keyProvider() { return keyProvider; }
    public int asyncMaxConcurrent() { return asyncMaxConcurrent; }
    /** Null selects the owned VT executor; a configured executor remains borrowed. */
    public java.util.concurrent.Executor asyncExecutor() { return asyncExecutor; }
    public NearCacheInvalidationConfig nearCacheInvalidation() { return nearCacheInvalidation; }
    public CacheObservationRecorder observationRecorder() { return observationRecorder; }
    public com.reactor.cachedb.core.observe.CacheTraceparentSupplier observationTraceparentSupplier() { return observationTraceparentSupplier; }
    public static CacheRuntimeOptions defaults() { return builder().build(); }
    public static Builder builder() { return new Builder(); }
    public Builder toBuilder() {
        return builder().redisReadCoalescing(redisReadCoalescing).nearCacheInvalidation(nearCacheInvalidation)
                .asyncMaxConcurrent(asyncMaxConcurrent).asyncExecutor(asyncExecutor)
                .observationRecorder(observationRecorder).observationTraceparentSupplier(observationTraceparentSupplier)
                .keyProvider(keyProvider);
    }

    public static final class Builder {
        private RedisReadCoalescingConfig redisReadCoalescing = RedisReadCoalescingConfig.defaults();
        private CacheKeyProvider keyProvider;
        private int asyncMaxConcurrent = com.reactor.cachedb.core.async.CacheAsyncExecutor.DEFAULT_MAX_CONCURRENT;
        private java.util.concurrent.Executor asyncExecutor;
        private NearCacheInvalidationConfig nearCacheInvalidation = NearCacheInvalidationConfig.defaults();
        private CacheObservationRecorder observationRecorder = NoOpCacheObservationRecorder.INSTANCE;
        private com.reactor.cachedb.core.observe.CacheTraceparentSupplier observationTraceparentSupplier =
                com.reactor.cachedb.core.observe.CacheTraceparentSupplier.NONE;
        public Builder observationTraceparentSupplier(com.reactor.cachedb.core.observe.CacheTraceparentSupplier value) {
            observationTraceparentSupplier = Objects.requireNonNull(value, "observationTraceparentSupplier"); return this;
        }
        public Builder observationRecorder(CacheObservationRecorder value) {
            observationRecorder = Objects.requireNonNull(value, "observationRecorder"); return this;
        }
        public Builder nearCacheInvalidation(NearCacheInvalidationConfig value) {
            nearCacheInvalidation = Objects.requireNonNull(value, "nearCacheInvalidation"); return this;
        }
        private Builder() { }
        public Builder keyProvider(CacheKeyProvider provider) { keyProvider = provider; return this; }
        public Builder redisReadCoalescing(RedisReadCoalescingConfig value) {
            redisReadCoalescing = Objects.requireNonNull(value, "redisReadCoalescing"); return this;
        }
        public Builder asyncMaxConcurrent(int value) {
            if (value < 1 || value > com.reactor.cachedb.core.async.CacheAsyncExecutor.MAX_CONCURRENT)
                throw new IllegalArgumentException("Async maxConcurrent must be in 1..65536");
            asyncMaxConcurrent = value;
            return this;
        }
        public Builder asyncExecutor(java.util.concurrent.Executor value) {
            asyncExecutor = value;
            return this;
        }
        public CacheRuntimeOptions build() { return new CacheRuntimeOptions(this); }
    }
}

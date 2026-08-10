package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.starter.CacheDatabase;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.Objects;

public final class CacheDatabaseMetrics implements MeterBinder {
    private final CacheDatabase cacheDatabase;

    public CacheDatabaseMetrics(CacheDatabase cacheDatabase) {
        this.cacheDatabase = Objects.requireNonNull(cacheDatabase, "cacheDatabase");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("cachedb.writebehind.backlog", cacheDatabase,
                        database -> database.workerSnapshot().lastObservedBacklog())
                .description("Last observed CacheDB write-behind stream backlog")
                .register(registry);
        FunctionCounter.builder("cachedb.writebehind.flushed", cacheDatabase,
                        database -> database.workerSnapshot().flushedCount())
                .description("Writes durably flushed to SQL")
                .register(registry);
        FunctionCounter.builder("cachedb.writebehind.deadletters", cacheDatabase,
                        database -> database.workerSnapshot().deadLetterCount())
                .description("Writes moved to the dead-letter stream")
                .register(registry);
        Gauge.builder("cachedb.redis.memory.used.bytes", cacheDatabase,
                        database -> database.redisGuardrailSnapshot().usedMemoryBytes())
                .baseUnit("bytes")
                .description("Redis used memory observed by CacheDB guardrails")
                .register(registry);
        Gauge.builder("cachedb.projection.lag", cacheDatabase,
                        database -> database.projectionRefreshSnapshot().lagEstimateMillis())
                .baseUnit("milliseconds")
                .description("Estimated projection refresh lag")
                .register(registry);
        Gauge.builder("cachedb.projection.pending", cacheDatabase,
                        database -> database.projectionRefreshSnapshot().pendingCount())
                .description("Pending projection refresh entries")
                .register(registry);
    }
}

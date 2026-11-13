package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.starter.CacheDatabase;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Scrapes local counters only: no Redis/SQL requests or percentile sorting during a scrape. */
public final class CacheDatabaseMetricsMeterBinder implements MeterBinder {
    private final CacheDatabase database;
    public CacheDatabaseMetricsMeterBinder(CacheDatabase database) { this.database = Objects.requireNonNull(database); }

    @Override public void bindTo(MeterRegistry registry) {
        Gauge.builder("cachedb.writebehind.backlog", database, db -> db.workerSnapshot().lastObservedBacklog())
                .description("Last observed write-behind backlog").strongReference(true).register(registry);
        FunctionCounter.builder("cachedb.writebehind.flushed", database, db -> db.workerSnapshot().flushedCount()).register(registry);
        FunctionCounter.builder("cachedb.writebehind.stale.skipped", database, db -> db.workerSnapshot().staleSkippedCount()).register(registry);
        FunctionCounter.builder("cachedb.writebehind.deadletter", database, db -> db.workerSnapshot().deadLetterCount()).register(registry);
        FunctionCounter.builder("cachedb.writebehind.deadletters", database, db -> db.workerSnapshot().deadLetterCount())
                .description("Compatibility alias for cachedb.writebehind.deadletter").register(registry);
        FunctionCounter.builder("cachedb.redis.read.coalescing.batches", database,
                db -> db.redisReadMetrics().transport().batches()).register(registry);
        FunctionCounter.builder("cachedb.redis.read.coalescing.coalesced.requests", database,
                db -> db.redisReadMetrics().transport().coalescedRequests()).register(registry);
        FunctionCounter.builder("cachedb.redis.read.coalescing.bypassed", database,
                db -> db.redisReadMetrics().bypassed()).register(registry);
        FunctionCounter.builder("cachedb.redis.read.coalescing.bypass.disabled", database,
                db -> db.redisReadMetrics().disabled()).register(registry);
        FunctionCounter.builder("cachedb.redis.read.coalescing.bypass.causal", database,
                db -> db.redisReadMetrics().causal()).register(registry);
        FunctionCounter.builder("cachedb.redis.read.coalescing.bypass.unitofwork", database,
                db -> db.redisReadMetrics().unitOfWork()).register(registry);
        Gauge.builder("cachedb.redis.read.coalescing.average.batch.size", database,
                db -> db.redisReadMetrics().transport().averageBatchSize()).strongReference(true).register(registry);
        var near = database.nearCacheCoordinator();
        Gauge.builder("cachedb.nearcache.ready", near, n -> n.connected() ? 1 : 0).strongReference(true).register(registry);
        Gauge.builder("cachedb.nearcache.tracking.ready", near, n -> n.trackingReady() ? 1 : 0).strongReference(true).register(registry);
        Gauge.builder("cachedb.nearcache.legacy.publish", near, n -> n.publishLegacyInvalidations() ? 1 : 0).strongReference(true).register(registry);
        for (var mode : com.reactor.cachedb.core.config.NearCacheInvalidationMode.values())
            Gauge.builder("cachedb.nearcache.invalidation.mode", near, n -> n.invalidationMode() == mode ? 1 : 0)
                    .tag("mode", mode.name()).strongReference(true).register(registry);
        FunctionCounter.builder("cachedb.nearcache.invalidations", near, n -> n.invalidationsReceived()).register(registry);
        FunctionCounter.builder("cachedb.nearcache.flushes", near, n -> n.flushAllEvents()).register(registry);
        FunctionCounter.builder("cachedb.nearcache.reconnects", near, n -> n.trackingReconnects()).register(registry);
        var collector = database.storagePerformanceCollector();
        FunctionTimer.builder("cachedb.redis.read.latency", collector,
                c -> c.redisReadOperationCount(), c -> c.redisReadTotalMicros(), TimeUnit.MICROSECONDS).register(registry);
        FunctionTimer.builder("cachedb.redis.write.latency", collector,
                c -> c.redisWriteOperationCount(), c -> c.redisWriteTotalMicros(), TimeUnit.MICROSECONDS).register(registry);
        FunctionTimer.builder("cachedb.sql.flush.latency", collector,
                c -> c.sqlWriteOperationCount(), c -> c.sqlWriteTotalMicros(), TimeUnit.MICROSECONDS).register(registry);
    }
}

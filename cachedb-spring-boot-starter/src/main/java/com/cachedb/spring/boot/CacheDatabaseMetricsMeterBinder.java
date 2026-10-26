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
        var collector = database.storagePerformanceCollector();
        FunctionTimer.builder("cachedb.redis.read.latency", collector,
                c -> c.redisReadOperationCount(), c -> c.redisReadTotalMicros(), TimeUnit.MICROSECONDS).register(registry);
        FunctionTimer.builder("cachedb.redis.write.latency", collector,
                c -> c.redisWriteOperationCount(), c -> c.redisWriteTotalMicros(), TimeUnit.MICROSECONDS).register(registry);
        FunctionTimer.builder("cachedb.sql.flush.latency", collector,
                c -> c.sqlWriteOperationCount(), c -> c.sqlWriteTotalMicros(), TimeUnit.MICROSECONDS).register(registry);
    }
}

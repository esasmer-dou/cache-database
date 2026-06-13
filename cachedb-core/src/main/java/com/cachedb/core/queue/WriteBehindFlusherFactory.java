package com.reactor.cachedb.core.queue;

import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.registry.EntityRegistry;

import javax.sql.DataSource;

@FunctionalInterface
public interface WriteBehindFlusherFactory {
    WriteBehindFlusher create(
            DataSource dataSource,
            EntityRegistry entityRegistry,
            WriteBehindConfig writeBehindConfig,
            StoragePerformanceCollector performanceCollector
    );
}

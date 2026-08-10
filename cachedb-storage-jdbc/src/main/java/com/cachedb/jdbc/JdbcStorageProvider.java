package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.queue.WriteBehindFlusherFactory;

import java.util.Map;
import java.util.Set;

/** Typed provider contribution discovered with ServiceLoader. */
public interface JdbcStorageProvider {
    String id();

    JdbcDatabaseDialect dialect();

    WriteBehindFlusherFactory writeBehindFlusherFactory(Map<String, String> options);

    default Set<String> supportedOptions() {
        return Set.of();
    }
}

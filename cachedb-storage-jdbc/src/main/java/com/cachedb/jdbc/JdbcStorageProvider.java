package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.queue.WriteBehindFlusherFactory;

import java.util.Map;
import java.util.Set;

/** Typed provider contribution discovered with ServiceLoader. */
public interface JdbcStorageProvider {
    String id();

    JdbcDatabaseDialect dialect();

    default JdbcQueryDialect queryDialect() {
        return JdbcQueryDialects.standard(
                id(),
                Set.of(id()),
                dialect().maxParametersPerStatement(),
                "postgres".equalsIgnoreCase(id())
        );
    }

    default JdbcSchemaDialect schemaDialect() {
        return JdbcSchemaDialects.ansi(id());
    }

    WriteBehindFlusherFactory writeBehindFlusherFactory(Map<String, String> options);

    default Set<String> supportedOptions() {
        return Set.of();
    }
}

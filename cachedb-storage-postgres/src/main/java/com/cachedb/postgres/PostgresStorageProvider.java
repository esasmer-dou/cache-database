package com.reactor.cachedb.postgres;

import com.reactor.cachedb.core.queue.WriteBehindFlusherFactory;
import com.reactor.cachedb.jdbc.JdbcDatabaseDialect;
import com.reactor.cachedb.jdbc.JdbcStorageProvider;
import com.reactor.cachedb.jdbc.JdbcStorageProviders;

import java.util.Map;

public final class PostgresStorageProvider implements JdbcStorageProvider {
    private static final JdbcDatabaseDialect DIALECT = new PostgresDatabaseDialect();

    @Override
    public String id() {
        return "postgres";
    }

    @Override
    public JdbcDatabaseDialect dialect() {
        return DIALECT;
    }

    @Override
    public WriteBehindFlusherFactory writeBehindFlusherFactory(Map<String, String> options) {
        JdbcStorageProviders.validateOptions(this, options);
        return PostgresWriteBehindFlusher::new;
    }
}

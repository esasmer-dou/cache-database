package com.reactor.cachedb.postgres;

import com.reactor.cachedb.core.queue.WriteBehindFlusherFactory;
import com.reactor.cachedb.jdbc.JdbcDatabaseDialect;
import com.reactor.cachedb.jdbc.JdbcStorageProvider;
import com.reactor.cachedb.jdbc.JdbcStorageProviders;
import com.reactor.cachedb.jdbc.JdbcQueryDialect;
import com.reactor.cachedb.jdbc.JdbcQueryDialects;
import com.reactor.cachedb.jdbc.JdbcSchemaDialect;
import com.reactor.cachedb.jdbc.JdbcSchemaDialects;

import java.util.Map;
import java.util.Set;

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
    public JdbcQueryDialect queryDialect() {
        return JdbcQueryDialects.standard("postgres", Set.of("postgresql"), 65_535, true);
    }

    @Override
    public JdbcSchemaDialect schemaDialect() {
        return JdbcSchemaDialects.postgres();
    }

    @Override
    public WriteBehindFlusherFactory writeBehindFlusherFactory(Map<String, String> options) {
        JdbcStorageProviders.validateOptions(this, options);
        return PostgresWriteBehindFlusher::new;
    }
}

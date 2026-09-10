package com.reactor.cachedb.oracle;

import com.reactor.cachedb.core.queue.WriteBehindFlusherFactory;
import com.reactor.cachedb.jdbc.JdbcDatabaseDialect;
import com.reactor.cachedb.jdbc.JdbcQueryDialect;
import com.reactor.cachedb.jdbc.JdbcSchemaDialect;
import com.reactor.cachedb.jdbc.JdbcSchemaDialects;
import com.reactor.cachedb.jdbc.JdbcStorageProvider;
import com.reactor.cachedb.jdbc.JdbcStorageProviders;

import java.sql.Connection;
import java.util.Map;
import java.util.Set;

public final class OracleStorageProvider implements JdbcStorageProvider {
    public static final String QUERY_TIMEOUT_SECONDS = "queryTimeoutSeconds";
    public static final String TRANSACTION_ISOLATION = "transactionIsolation";
    public static final String DUPLICATE_RACE_RETRIES = "duplicateRaceRetries";
    public static final String EMPTY_STRING_POLICY = "emptyStringPolicy";

    private static final JdbcDatabaseDialect DATABASE_DIALECT = new OracleDatabaseDialect();
    private static final JdbcQueryDialect QUERY_DIALECT = new OracleQueryDialect();

    @Override
    public String id() {
        return "oracle";
    }

    @Override
    public JdbcDatabaseDialect dialect() {
        return DATABASE_DIALECT;
    }

    @Override
    public JdbcQueryDialect queryDialect() {
        return QUERY_DIALECT;
    }

    @Override
    public JdbcSchemaDialect schemaDialect() {
        return JdbcSchemaDialects.oracle();
    }

    @Override
    public WriteBehindFlusherFactory writeBehindFlusherFactory(Map<String, String> options) {
        Map<String, String> safe = JdbcStorageProviders.validateOptions(this, options);
        OracleWriteBehindOptions defaults = OracleWriteBehindOptions.defaults();
        OracleWriteBehindOptions configured = OracleWriteBehindOptions.builder()
                .queryTimeoutSeconds(integer(safe, QUERY_TIMEOUT_SECONDS, defaults.queryTimeoutSeconds()))
                .transactionIsolation(integer(
                        safe,
                        TRANSACTION_ISOLATION,
                        Connection.TRANSACTION_READ_COMMITTED
                ))
                .duplicateRaceRetries(integer(
                        safe,
                        DUPLICATE_RACE_RETRIES,
                        defaults.duplicateRaceRetries()
                ))
                .emptyStringPolicy(OracleWriteBehindOptions.EmptyStringPolicy.parse(
                        safe.get(EMPTY_STRING_POLICY),
                        defaults.emptyStringPolicy()
                ))
                .build();
        return OracleWriteBehindFlusher.factory(configured);
    }

    @Override
    public Set<String> supportedOptions() {
        return Set.of(
                QUERY_TIMEOUT_SECONDS,
                TRANSACTION_ISOLATION,
                DUPLICATE_RACE_RETRIES,
                EMPTY_STRING_POLICY
        );
    }

    private static int integer(Map<String, String> options, String key, int fallback) {
        String value = options.get(key);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }
}

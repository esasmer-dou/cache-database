package com.reactor.cachedb.mssql;

import com.reactor.cachedb.core.queue.WriteBehindFlusherFactory;
import com.reactor.cachedb.jdbc.JdbcDatabaseDialect;
import com.reactor.cachedb.jdbc.JdbcStorageProvider;
import com.reactor.cachedb.jdbc.JdbcStorageProviders;

import java.sql.Connection;
import java.util.Map;
import java.util.Set;

public final class MssqlStorageProvider implements JdbcStorageProvider {
    public static final String LOCK_TIMEOUT_MILLIS = "lockTimeoutMillis";
    public static final String QUERY_TIMEOUT_SECONDS = "queryTimeoutSeconds";
    public static final String TRANSACTION_ISOLATION = "transactionIsolation";
    public static final String RESTORE_LOCK_TIMEOUT = "restoreLockTimeoutAfterTransaction";

    private static final JdbcDatabaseDialect DIALECT = new MssqlDatabaseDialect();

    @Override
    public String id() {
        return "mssql";
    }

    @Override
    public JdbcDatabaseDialect dialect() {
        return DIALECT;
    }

    @Override
    public WriteBehindFlusherFactory writeBehindFlusherFactory(Map<String, String> options) {
        Map<String, String> safe = JdbcStorageProviders.validateOptions(this, options);
        MssqlWriteBehindOptions defaults = MssqlWriteBehindOptions.sharedPoolDefaults();
        MssqlWriteBehindOptions configured = MssqlWriteBehindOptions.builder()
                .lockTimeoutMillis(integer(safe, LOCK_TIMEOUT_MILLIS, defaults.lockTimeoutMillis()))
                .queryTimeoutSeconds(integer(safe, QUERY_TIMEOUT_SECONDS, defaults.queryTimeoutSeconds()))
                .transactionIsolation(integer(safe, TRANSACTION_ISOLATION, Connection.TRANSACTION_SERIALIZABLE))
                .restoreLockTimeoutAfterTransaction(bool(
                        safe, RESTORE_LOCK_TIMEOUT, defaults.restoreLockTimeoutAfterTransaction()
                ))
                .build();
        return MssqlWriteBehindFlusher.factory(configured);
    }

    @Override
    public Set<String> supportedOptions() {
        return Set.of(LOCK_TIMEOUT_MILLIS, QUERY_TIMEOUT_SECONDS, TRANSACTION_ISOLATION, RESTORE_LOCK_TIMEOUT);
    }

    private static int integer(Map<String, String> options, String key, int fallback) {
        String value = options.get(key);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }

    private static boolean bool(Map<String, String> options, String key, boolean fallback) {
        String value = options.get(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }
}

package com.reactor.cachedb.examples.demo;

import com.reactor.cachedb.starter.CacheDatabaseAdmin;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.sql.Connection;
import javax.sql.DataSource;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DemoEnvironmentResetter {

    private static final String SCAN_CURSOR_START = "0";
    private static final int REDIS_SCAN_COUNT = 10_000;
    private static final int RESET_RETRY_ATTEMPTS = 3;
    private static final long RESET_RETRY_PAUSE_MILLIS = 750L;
    private static final long QUIESCE_TIMEOUT_MILLIS = 15_000L;
    private static final long QUIESCE_POLL_MILLIS = 250L;

    private final JedisPooled jedis;
    private final String keyPrefix;
    private final String jdbcUrl;
    private final String jdbcUser;
    private final String jdbcPassword;
    private final DataSource dataSource;
    private final CacheDatabaseAdmin admin;
    private final boolean useDedicatedRedisDbReset;

    public DemoEnvironmentResetter(
            JedisPooled jedis,
            String keyPrefix,
            String jdbcUrl,
            String jdbcUser,
            String jdbcPassword,
            CacheDatabaseAdmin admin,
            boolean useDedicatedRedisDbReset
    ) {
        this.jedis = Objects.requireNonNull(jedis, "jedis");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        this.jdbcUser = Objects.requireNonNull(jdbcUser, "jdbcUser");
        this.jdbcPassword = Objects.requireNonNull(jdbcPassword, "jdbcPassword");
        this.dataSource = null;
        this.admin = Objects.requireNonNull(admin, "admin");
        this.useDedicatedRedisDbReset = useDedicatedRedisDbReset;
    }

    public DemoEnvironmentResetter(
            JedisPooled jedis,
            String keyPrefix,
            DataSource dataSource,
            CacheDatabaseAdmin admin,
            boolean useDedicatedRedisDbReset
    ) {
        this.jedis = Objects.requireNonNull(jedis, "jedis");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        this.jdbcUrl = null;
        this.jdbcUser = null;
        this.jdbcPassword = null;
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.admin = Objects.requireNonNull(admin, "admin");
        this.useDedicatedRedisDbReset = useDedicatedRedisDbReset;
    }

    public void reset() {
        if (!admin.schemaMigrationPlan().empty()) {
            admin.applySchemaMigrationPlan();
        }
        waitForWriteBehindToSettle();
        clearDataOnlyWithRetry();
        // Redis-side telemetry keys share the same demo key prefix and are already removed by clearDataOnly().
        // Only clear the in-memory/history buffers that survive key deletion.
        admin.resetTelemetryBuffersOnly();
    }

    private void clearDataOnlyWithRetry() {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= RESET_RETRY_ATTEMPTS; attempt++) {
            try {
                clearDataOnly();
                return;
            } catch (JedisConnectionException exception) {
                lastFailure = exception;
                sleepQuietly(RESET_RETRY_PAUSE_MILLIS);
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
    }

    public void clearDataOnly() {
        truncateDemoTables();
        deleteRedisKeys();
    }

    private void truncateDemoTables() {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                      cachedb_demo_order_lines,
                      cachedb_demo_orders,
                      cachedb_demo_carts,
                      cachedb_demo_products,
                      cachedb_demo_customers
                    RESTART IDENTITY
                    """);
        } catch (SQLException exception) {
            throw new IllegalStateException("Demo PostgreSQL verisi sifirlanamadi", exception);
        }
    }

    private Connection openConnection() throws SQLException {
        if (dataSource != null) {
            return dataSource.getConnection();
        }
        return DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
    }

    private void deleteRedisKeys() {
        throwIfInterrupted();
        if (useDedicatedRedisDbReset) {
            jedis.sendCommand(redis.clients.jedis.Protocol.Command.FLUSHDB, "ASYNC");
            return;
        }
        ScanParams params = new ScanParams().match(keyPrefix + "*").count(REDIS_SCAN_COUNT);
        String cursor = SCAN_CURSOR_START;
        do {
            throwIfInterrupted();
            ScanResult<String> scan = jedis.scan(cursor, params);
            cursor = scan.getCursor();
            List<String> keys = scan.getResult();
            if (!keys.isEmpty()) {
                jedis.unlink(keys.toArray(new String[0]));
            }
        } while (!SCAN_CURSOR_START.equals(cursor));
    }

    private void waitForWriteBehindToSettle() {
        long deadline = System.currentTimeMillis() + QUIESCE_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            try {
                var metrics = admin.metrics();
                boolean streamsDrained = metrics.writeBehindStreamLength() == 0L
                        && metrics.deadLetterStreamLength() == 0L
                        && metrics.reconciliationStreamLength() == 0L;
                boolean compactionDrained = metrics.redisGuardrailSnapshot().compactionPendingCount() == 0L;
                if (streamsDrained && compactionDrained) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // Best effort wait; proceed to reset even if telemetry sampling is temporarily unavailable.
            }
            sleepQuietly(QUIESCE_POLL_MILLIS);
        }
    }

    private void sleepQuietly(long pauseMillis) {
        try {
            Thread.sleep(pauseMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Demo environment reset interrupted");
        }
    }
}

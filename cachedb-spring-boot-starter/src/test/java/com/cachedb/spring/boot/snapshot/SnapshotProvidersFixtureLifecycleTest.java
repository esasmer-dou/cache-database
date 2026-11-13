package com.reactor.cachedb.spring.boot.snapshot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

class SnapshotProvidersFixtureLifecycleTest {
    @Test
    void sourceAcquisitionFailureDoesNotAcquireRedis() {
        var failure = new IllegalStateException("SQL acquisition failed");
        var redisAcquired = new AtomicBoolean();
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new SnapshotProvidersIntegrationTest().new Fixture("mssql",
                        () -> { throw failure; },
                        () -> { redisAcquired.set(true); return null; })));
        assertFalse(redisAcquired.get());
    }

    @Test
    void redisAcquisitionFailureClosesAcquiredSqlPool() throws Exception {
        var resources = new Resources();
        var failure = new IllegalStateException("Redis acquisition failed");
        var cleanup = new IllegalArgumentException("SQL close failed");
        resources.failures.put("SQL_CLOSE", cleanup);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new SnapshotProvidersIntegrationTest().new Fixture("mssql",
                        () -> resources.source, () -> { throw failure; })));
        assertArrayEquals(new Throwable[] {cleanup}, failure.getSuppressed());
        assertEquals(List.of("SQL_CLOSE"), resources.events);
        assertTrue(resources.sql.isEmpty());
        verifyNoInteractions(resources.redis);
    }

    @Test
    void failedRootCreateClosesOnlyAttemptedOwnedScope() throws Exception {
        var resources = new Resources();
        var failure = new SQLException("CREATE outcome unknown", "HYT00");
        resources.failures.put("CREATE_ROOT", failure);
        var actual = assertThrows(IllegalStateException.class, resources::open);
        assertSame(failure, actual.getCause());
        assertEquals(List.of("CREATE_ROOT", "DROP_ROOT", "SQL_CLOSE", "REDIS_SCAN", "REDIS_CLOSE"),
                resources.events);
        assertEquals(tableName(resources.sql.getFirst()), tableName(resources.sql.getLast()));
    }

    @Test
    void failedChildCreatePreservesSetupFailureAndAttemptsBothTables() throws Exception {
        var resources = new Resources();
        var failure = new IllegalStateException("child CREATE failed");
        var childDrop = new IllegalArgumentException("child DROP failed");
        var scan = new IllegalStateException("SCAN failed");
        resources.failures.put("CREATE_CHILD", failure);
        resources.failures.put("DROP_CHILD", childDrop);
        resources.failures.put("REDIS_SCAN", scan);
        assertSame(failure, assertThrows(IllegalStateException.class, resources::open));
        assertArrayEquals(new Throwable[] {childDrop, scan}, failure.getSuppressed());
        assertEquals(List.of("CREATE_ROOT", "CREATE_CHILD", "DROP_CHILD", "DROP_ROOT",
                "SQL_CLOSE", "REDIS_SCAN", "REDIS_CLOSE"), resources.events);
        assertEquals(tableName(resources.sql.get(1)), tableName(resources.sql.get(2)));
        assertEquals(tableName(resources.sql.get(0)), tableName(resources.sql.get(3)));
    }

    @Test
    void rootNameCollisionNeverDropsExistingTable() throws Exception {
        for (String provider : List.of("postgres", "mssql", "oracle", "h2")) {
            var resources = new Resources();
            var collision = collision(provider);
            resources.failures.put("CREATE_ROOT", collision);
            var actual = assertThrows(IllegalStateException.class, () -> resources.open(provider));
            assertSame(collision, actual.getCause());
            assertEquals(List.of("CREATE_ROOT", "SQL_CLOSE", "REDIS_SCAN", "REDIS_CLOSE"),
                    resources.events, provider);
            assertEquals(1, resources.sql.size(), provider);
        }
    }

    @Test
    void childNameCollisionDropsOnlyNewRoot() throws Exception {
        for (String provider : List.of("postgres", "mssql", "oracle", "h2")) {
            var resources = new Resources();
            var collision = collision(provider);
            resources.failures.put("CREATE_CHILD", collision);
            var actual = assertThrows(IllegalStateException.class, () -> resources.open(provider));
            assertSame(collision, actual.getCause());
            assertEquals(List.of("CREATE_ROOT", "CREATE_CHILD", "DROP_ROOT", "SQL_CLOSE",
                    "REDIS_SCAN", "REDIS_CLOSE"), resources.events, provider);
            assertEquals(tableName(resources.sql.getFirst()), tableName(resources.sql.getLast()));
        }
    }

    @Test
    void failedInsertCleansBothOwnedTables() throws Exception {
        var resources = new Resources();
        var failure = new IllegalStateException("INSERT failed");
        resources.failures.put("INSERT_ROOT", failure);
        assertSame(failure, assertThrows(IllegalStateException.class, resources::open));
        assertEquals(List.of("CREATE_ROOT", "CREATE_CHILD", "INSERT_ROOT", "DROP_CHILD",
                "DROP_ROOT", "SQL_CLOSE", "REDIS_SCAN", "REDIS_CLOSE"), resources.events);
    }

    @Test
    void setupErrorStillCleansEveryOwnedResource() throws Exception {
        var resources = new Resources();
        var failure = new AssertionError("setup failed");
        resources.failures.put("INSERT_CHILD", failure);
        assertSame(failure, assertThrows(AssertionError.class, resources::open));
        assertEquals(List.of("CREATE_ROOT", "CREATE_CHILD", "INSERT_ROOT", "INSERT_CHILD",
                "DROP_CHILD", "DROP_ROOT", "SQL_CLOSE", "REDIS_SCAN", "REDIS_CLOSE"), resources.events);
    }

    @Test
    void closeAttemptsEveryCleanupAndSuppressesInOrder() throws Exception {
        var resources = new Resources();
        var fixture = resources.open();
        resources.events.clear();
        var child = new IllegalStateException("child DROP failed");
        var root = new IllegalStateException("root DROP failed");
        var pool = new AssertionError("SQL close failed");
        var scan = new IllegalStateException("SCAN failed");
        var redis = new IllegalStateException("Redis close failed");
        resources.failures.put("DROP_CHILD", child);
        resources.failures.put("DROP_ROOT", root);
        resources.failures.put("SQL_CLOSE", pool);
        resources.failures.put("REDIS_SCAN", scan);
        resources.failures.put("REDIS_CLOSE", redis);
        assertSame(child, assertThrows(IllegalStateException.class, fixture::close));
        assertArrayEquals(new Throwable[] {root, pool, scan, redis}, child.getSuppressed());
        assertEquals(List.of("DROP_CHILD", "DROP_ROOT", "SQL_CLOSE", "REDIS_SCAN", "REDIS_CLOSE"),
                resources.events);
    }

    @Test
    void scanFailureStillClosesRedis() throws Exception {
        var resources = new Resources();
        var fixture = resources.open();
        resources.events.clear();
        var failure = new IllegalStateException("SCAN failed");
        resources.failures.put("REDIS_SCAN", failure);
        assertSame(failure, assertThrows(IllegalStateException.class, fixture::close));
        assertEquals(List.of("DROP_CHILD", "DROP_ROOT", "SQL_CLOSE", "REDIS_SCAN", "REDIS_CLOSE"),
                resources.events);
        verify(resources.redis).close();
    }

    @Test
    void unlinkFailureStillClosesRedis() throws Exception {
        var resources = new Resources();
        var fixture = resources.open();
        resources.events.clear();
        var key = fixture.namespace + ":owned";
        resources.scanKeys = List.of(key);
        var failure = new IllegalStateException("UNLINK failed");
        resources.failures.put("REDIS_UNLINK", failure);
        assertSame(failure, assertThrows(IllegalStateException.class, fixture::close));
        assertEquals(List.of("DROP_CHILD", "DROP_ROOT", "SQL_CLOSE", "REDIS_SCAN",
                "REDIS_UNLINK", "REDIS_CLOSE"), resources.events);
        verify(resources.redis).unlink(new String[] {key});
        verify(resources.redis).close();
    }

    @Test
    void repeatedSameFailureDoesNotSelfSuppress() throws Exception {
        var resources = new Resources();
        var fixture = resources.open();
        resources.events.clear();
        var failure = new AssertionError("same cleanup failure");
        for (String stage : List.of("DROP_CHILD", "DROP_ROOT", "SQL_CLOSE", "REDIS_SCAN", "REDIS_CLOSE"))
            resources.failures.put(stage, failure);
        assertSame(failure, assertThrows(AssertionError.class, fixture::close));
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(5, resources.events.size());
        assertEquals("REDIS_CLOSE", resources.events.getLast());
    }

    @Test
    void closeIsIdempotentAndUsesOnlyOwnedScope() throws Exception {
        var resources = new Resources();
        var fixture = resources.open();
        fixture.close();
        fixture.close();
        assertEquals(List.of(
                "CREATE TABLE " + fixture.root + " (id INTEGER PRIMARY KEY, payload VARCHAR(100))",
                "CREATE TABLE " + fixture.child + " (id INTEGER PRIMARY KEY, payload VARCHAR(100))",
                "INSERT INTO " + fixture.root + " VALUES (1,'root')",
                "INSERT INTO " + fixture.child + " VALUES (1,'old')",
                "DROP TABLE " + fixture.child,
                "DROP TABLE " + fixture.root), resources.sql);
        verify(resources.source).close();
        verify(resources.redis).close();
        verify(resources.statement, times(6)).setQueryTimeout(10);
        verify(resources.connection, times(6)).close();
        verify(resources.statement, times(6)).close();
    }

    @Test
    void tryWithResourcesKeepsBodyFailurePrimary() throws Exception {
        var resources = new Resources();
        var body = new IllegalStateException("test body failed");
        var child = new IllegalStateException("child DROP failed");
        var root = new IllegalStateException("root DROP failed");
        resources.failures.put("DROP_CHILD", child);
        resources.failures.put("DROP_ROOT", root);
        var actual = assertThrows(IllegalStateException.class, () -> {
            try (var ignored = resources.open()) {
                throw body;
            }
        });
        assertSame(body, actual);
        assertArrayEquals(new Throwable[] {child}, body.getSuppressed());
        assertArrayEquals(new Throwable[] {root}, child.getSuppressed());
        verify(resources.redis).close();
    }

    private static SQLException collision(String provider) {
        return switch (provider) {
            case "postgres" -> new SQLException("name collision", "42P07");
            case "mssql" -> new SQLException("name collision", "S0001", 2714);
            case "oracle" -> new SQLException("name collision", "42000", 955);
            default -> new SQLException("name collision", "42S01", 42101);
        };
    }

    private static String tableName(String sql) {
        return sql.split(" ")[2];
    }

    private static final class Resources {
        final HikariDataSource source = mock(HikariDataSource.class);
        final JedisPooled redis = mock(JedisPooled.class);
        final Connection connection = mock(Connection.class);
        final Statement statement = mock(Statement.class);
        final List<String> events = new ArrayList<>();
        final List<String> sql = new ArrayList<>();
        final Map<String, Throwable> failures = new HashMap<>();
        List<String> scanKeys = List.of();

        Resources() throws Exception {
            when(source.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.execute(anyString())).thenAnswer(call -> {
                String command = call.getArgument(0);
                sql.add(command);
                String stage = command.startsWith("CREATE TABLE ") ? "CREATE"
                        : command.startsWith("INSERT INTO ") ? "INSERT" : "DROP";
                step(stage + (tableName(command).startsWith("snap_r_") ? "_ROOT" : "_CHILD"));
                return false;
            });
            doAnswer(call -> { step("SQL_CLOSE"); return null; }).when(source).close();
            when(redis.scan(anyString(), any(ScanParams.class))).thenAnswer(call -> {
                step("REDIS_SCAN");
                return new ScanResult<>("0", scanKeys);
            });
            when(redis.unlink(any(String[].class))).thenAnswer(call -> {
                step("REDIS_UNLINK");
                return 0L;
            });
            doAnswer(call -> { step("REDIS_CLOSE"); return null; }).when(redis).close();
        }

        SnapshotProvidersIntegrationTest.Fixture open() {
            return open("mssql");
        }

        SnapshotProvidersIntegrationTest.Fixture open(String provider) {
            return new SnapshotProvidersIntegrationTest().new Fixture(provider, () -> source, () -> redis);
        }

        private void step(String stage) throws Throwable {
            events.add(stage);
            var failure = failures.get(stage);
            if (failure != null) throw failure;
        }
    }
}

package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

class JdbcWriteFenceMigrationTest {
    @Test
    void packagedMigrationsMatchBootstrapDdlForEveryProvider() throws Exception {
        for (var provider : JdbcWriteFence.Provider.values()) {
            String path =
                    "/com/reactor/cachedb/jdbc/migrations/"
                            + provider.name().toLowerCase(java.util.Locale.ROOT)
                            + "/V014__durable_write_fence.sql";
            try (var resource = getClass().getResourceAsStream(path)) {
                assertNotNull(resource, path);
                String sql =
                        new String(resource.readAllBytes(), StandardCharsets.UTF_8)
                                .replaceAll("(?m)^--.*$", "")
                                .replace(";", "");
                assertEquals(normalize(JdbcWriteFence.createTableSql(provider)), normalize(sql));
            }
        }
    }

    @Test
    void schemaBootstrapIsIdempotentButNeverSilentlyAcceptsAMissingPrimaryKey() throws Exception {
        var source = source();
        var fence = new JdbcWriteFence(source, null, JdbcWriteFence.Provider.POSTGRES, 1);
        fence.initialize();
        fence.initialize();
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + JdbcWriteFence.TABLE);
            statement.execute(
                    "CREATE TABLE "
                            + JdbcWriteFence.TABLE
                            + " (fence_key CHAR(64),identity_value TEXT,entity_version"
                            + " BIGINT,deleted SMALLINT)");
        }
        var invalid = new JdbcWriteFence(source, null, JdbcWriteFence.Provider.POSTGRES, 1);
        assertEquals("CDB03", assertThrows(SQLException.class, invalid::initialize).getSQLState());
    }

    @Test
    void cannotFenceAnAutocommitWrite() throws Exception {
        var source = source();
        var fence = new JdbcWriteFence(source, null, JdbcWriteFence.Provider.POSTGRES, 1);
        try (var connection = source.getConnection()) {
            assertEquals(
                    "CDB03",
                    assertThrows(SQLException.class, () -> fence.filter(connection, List.of()))
                            .getSQLState());
        }
    }

    @Test
    void preprovisionedSchemaWorksWithAutocommitDisabledWithoutRunningDdl() throws Exception {
        var source = source();
        new JdbcWriteFence(source, null, JdbcWriteFence.Provider.POSTGRES, 1).initialize();
        source.setURL(source.getURL() + ";AUTOCOMMIT=OFF");
        var fence = new JdbcWriteFence(source, null, JdbcWriteFence.Provider.POSTGRES, 1);
        fence.initialize();
        try (var connection = source.getConnection()) {
            assertFalse(connection.getAutoCommit());
            assertEquals(List.of(), fence.filter(connection, List.of()));
        }
        var missing = source();
        missing.setURL(missing.getURL() + ";AUTOCOMMIT=OFF");
        var unsafe = new JdbcWriteFence(missing, null, JdbcWriteFence.Provider.ORACLE, 1);
        assertEquals("CDB03", assertThrows(SQLException.class, unsafe::initialize).getSQLState());
    }

    @Test
    void transientProbeFailureIsNotConvertedIntoMigrationFailureOrDdl() throws Exception {
        var delegate = source();
        var expected =
                new java.sql.SQLTransientConnectionException("fixture transport failure", "08006");
        java.util.concurrent.atomic.AtomicInteger ddl =
                new java.util.concurrent.atomic.AtomicInteger();
        var source =
                (javax.sql.DataSource)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {javax.sql.DataSource.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("getConnection")) {
                                        var connection = delegate.getConnection();
                                        return java.lang.reflect.Proxy.newProxyInstance(
                                                getClass().getClassLoader(),
                                                new Class<?>[] {java.sql.Connection.class},
                                                (ignored, call, values) -> {
                                                    if (call.getName().equals("prepareStatement"))
                                                        throw expected;
                                                    if (call.getName().equals("createStatement"))
                                                        ddl.incrementAndGet();
                                                    try {
                                                        return call.invoke(connection, values);
                                                    } catch (
                                                            java.lang.reflect
                                                                            .InvocationTargetException
                                                                    error) {
                                                        throw error.getCause();
                                                    }
                                                });
                                    }
                                    try {
                                        return method.invoke(delegate, args);
                                    } catch (java.lang.reflect.InvocationTargetException error) {
                                        throw error.getCause();
                                    }
                                });
        var fence = new JdbcWriteFence(source, null, JdbcWriteFence.Provider.POSTGRES, 1);
        assertSame(expected, assertThrows(SQLException.class, fence::initialize));
        assertEquals(0, ddl.get());
    }

    private static JdbcDataSource source() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:fence_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return source;
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }
}

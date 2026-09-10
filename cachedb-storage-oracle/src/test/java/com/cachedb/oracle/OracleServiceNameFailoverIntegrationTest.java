package com.reactor.cachedb.oracle;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class OracleServiceNameFailoverIntegrationTest {

    private static final String JDBC_URL = System.getProperty(
            "cachedb.it.oracle.url",
            "jdbc:oracle:thin:@//127.0.0.1:15223/CACHEDBPDB"
    );
    private static final String JDBC_USER = System.getProperty("cachedb.it.oracle.user", "cachedb");
    private static final String JDBC_PASSWORD = System.getProperty(
            "cachedb.it.oracle.password",
            "CacheDbOracle123"
    );
    private static final boolean REQUIRED = Boolean.getBoolean(
            "cachedb.it.oracle.serviceFailover.required"
    );
    private static final Path READY_FILE = pathProperty("cachedb.it.oracle.serviceFailover.readyFile");
    private static final Path SWITCH_FILE = pathProperty("cachedb.it.oracle.serviceFailover.switchFile");
    private static final int TIMEOUT_SECONDS = Integer.getInteger(
            "cachedb.it.oracle.serviceFailover.timeoutSeconds",
            180
    );
    private static final long MARKER_ID = Long.getLong(
            "cachedb.it.oracle.serviceFailover.markerId",
            10_001L
    );

    @Test
    void pooledConnectionShouldRecoverThroughStableServiceNameAfterRoleTransition() throws Exception {
        assumeConfigured();

        try (HikariDataSource dataSource = dataSource()) {
            DatabaseIdentity before = waitForPrimaryDifferentFrom(dataSource, null);
            assertEquals("PRIMARY", before.role());
            assertTrue(!before.serviceName().isBlank(), "Oracle service name must not be blank");

            try (Connection staleConnection = dataSource.getConnection()) {
                DatabaseIdentity staleIdentity = identity(staleConnection);
                assertEquals(before, staleIdentity, "Held JDBC connection must use the initial primary");
                upsertMarker(staleConnection, MARKER_ID, "before-role-transition");
                writeFile(READY_FILE, before.uniqueName() + "|" + before.serviceName());
                waitForFile(SWITCH_FILE);

                SQLException staleFailure = assertThrows(
                        SQLException.class,
                        () -> identity(staleConnection),
                        "The connection opened before the endpoint switch must not remain usable"
                );
                assertTrue(
                        staleFailure.getMessage() != null && !staleFailure.getMessage().isBlank(),
                        "The stale Oracle connection failure must contain diagnostic detail"
                );
            }

            DatabaseIdentity after = waitForPrimaryDifferentFrom(dataSource, before.uniqueName());
            assertNotEquals(before.uniqueName(), after.uniqueName());
            assertEquals("PRIMARY", after.role());
            assertEquals(
                    before.serviceName().toLowerCase(Locale.ROOT),
                    after.serviceName().toLowerCase(Locale.ROOT),
                    "The application-facing Oracle service name must remain stable"
            );

            try (Connection connection = dataSource.getConnection()) {
                assertEquals(1L, markerCount(connection, MARKER_ID),
                        "A committed pre-transition marker must exist on the new primary");
                upsertMarker(connection, MARKER_ID + 1, "after-role-transition");
                assertEquals(1L, markerCount(connection, MARKER_ID + 1));
            }
        }
    }

    private static HikariDataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(JDBC_URL);
        config.setUsername(JDBC_USER);
        config.setPassword(JDBC_PASSWORD);
        config.setPoolName("cachedb-oracle-service-failover");
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000);
        config.setValidationTimeout(3_000);
        config.setInitializationFailTimeout(REQUIRED ? 10_000 : -1);
        config.addDataSourceProperty("oracle.net.CONNECT_TIMEOUT", "5000");
        config.addDataSourceProperty("oracle.jdbc.ReadTimeout", "10000");
        return new HikariDataSource(config);
    }

    private static DatabaseIdentity waitForPrimaryDifferentFrom(
            HikariDataSource dataSource,
            String previousUniqueName
    ) throws InterruptedException, SQLException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(TIMEOUT_SECONDS));
        SQLException lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            try (Connection connection = dataSource.getConnection()) {
                DatabaseIdentity current = identity(connection);
                boolean different = previousUniqueName == null
                        || !previousUniqueName.equalsIgnoreCase(current.uniqueName());
                if (different && "PRIMARY".equalsIgnoreCase(current.role())) {
                    return current;
                }
            } catch (SQLException failure) {
                lastFailure = failure;
            }
            Thread.sleep(1_000);
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        fail("Oracle service endpoint did not reach a different primary within " + TIMEOUT_SECONDS + " seconds");
        throw new IllegalStateException("unreachable");
    }

    private static DatabaseIdentity identity(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (ResultSet resultSet = statement.executeQuery("""
                    SELECT SYS_CONTEXT('USERENV', 'DB_UNIQUE_NAME'),
                           SYS_CONTEXT('USERENV', 'DATABASE_ROLE'),
                           SYS_CONTEXT('USERENV', 'SERVICE_NAME')
                    FROM dual
                    """)) {
                if (!resultSet.next()) {
                    throw new SQLException("Oracle identity query returned no row");
                }
                return new DatabaseIdentity(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        resultSet.getString(3)
                );
            }
        }
    }

    private static void upsertMarker(Connection connection, long markerId, String marker) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                MERGE INTO cachedb_dg_evidence target
                USING (SELECT ? AS id, ? AS marker FROM dual) source
                   ON (target.id = source.id)
                WHEN MATCHED THEN
                    UPDATE SET target.marker = source.marker, target.created_at = SYSTIMESTAMP
                WHEN NOT MATCHED THEN
                    INSERT (id, marker, created_at)
                    VALUES (source.id, source.marker, SYSTIMESTAMP)
                """)) {
            statement.setLong(1, markerId);
            statement.setString(2, marker);
            statement.setQueryTimeout(10);
            statement.executeUpdate();
        }
    }

    private static long markerCount(Connection connection, long markerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM cachedb_dg_evidence WHERE id = ?"
        )) {
            statement.setLong(1, markerId);
            statement.setQueryTimeout(10);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static void assumeConfigured() {
        boolean configured = READY_FILE != null && SWITCH_FILE != null;
        if (REQUIRED && !configured) {
            fail("Oracle service failover evidence requires readyFile and switchFile properties");
        }
        Assumptions.assumeTrue(configured, "Oracle service failover coordination files are not configured");
    }

    private static void waitForFile(Path path) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(TIMEOUT_SECONDS));
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(path)) {
                return;
            }
            Thread.sleep(500);
        }
        fail("Timed out waiting for Oracle service switch file: " + path);
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static Path pathProperty(String propertyName) {
        String value = System.getProperty(propertyName, "");
        return value.isBlank() ? null : Path.of(value);
    }

    private record DatabaseIdentity(String uniqueName, String role, String serviceName) {
        private DatabaseIdentity {
            if (uniqueName == null || role == null || serviceName == null) {
                throw new IllegalArgumentException("Oracle database identity fields must not be null");
            }
        }
    }
}

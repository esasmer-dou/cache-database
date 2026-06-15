package com.reactor.cachedb.mssql;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class MssqlListenerFailoverSqlServerTest {

    private static final String JDBC_URL = System.getProperty(
            "cachedb.it.mssql.url",
            "jdbc:sqlserver://127.0.0.1:14333;databaseName=tempdb;encrypt=false;trustServerCertificate=true"
    );
    private static final String JDBC_USER = System.getProperty("cachedb.it.mssql.user", "sa");
    private static final String JDBC_PASSWORD = System.getProperty("cachedb.it.mssql.password", "YourStrong!Passw0rd");
    private static final boolean REQUIRED = Boolean.getBoolean("cachedb.it.mssql.listenerFailover.required");
    private static final Path READY_FILE = pathProperty("cachedb.it.mssql.listenerFailover.readyFile");
    private static final Path SWITCH_FILE = pathProperty("cachedb.it.mssql.listenerFailover.switchFile");
    private static final int TIMEOUT_SECONDS = Integer.getInteger(
            "cachedb.it.mssql.listenerFailover.timeoutSeconds",
            120
    );

    @Test
    void listenerSwitchShouldInvalidateOldJdbcConnectionAndOpenNewConnectionToNewBackend() throws Exception {
        assumeConfigured();
        assumeReachable();

        String primaryServerName = waitForServerNameDifferentFrom(null);
        try (Connection staleConnection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD)) {
            assertNotEquals("", primaryServerName, "Primary SQL Server identity must not be blank");
            writeFile(READY_FILE, primaryServerName);
            waitForFile(SWITCH_FILE);

            SQLException staleFailure = assertThrows(
                    SQLException.class,
                    () -> serverName(staleConnection),
                    "The pre-failover JDBC connection must not keep succeeding after the listener backend is switched"
            );
            assertTrue(
                    staleFailure.getMessage() != null && !staleFailure.getMessage().isBlank(),
                    "The stale connection failure should contain diagnostic detail"
            );
        }

        String secondaryServerName = waitForServerNameDifferentFrom(primaryServerName);
        assertNotEquals(
                primaryServerName,
                secondaryServerName,
                "New listener connections must land on a different SQL Server backend after failover"
        );
    }

    private static void assumeConfigured() {
        boolean configured = READY_FILE != null && SWITCH_FILE != null;
        if (REQUIRED && !configured) {
            fail("Listener failover test requires readyFile and switchFile system properties.");
        }
        Assumptions.assumeTrue(configured, "Listener failover coordination files are not configured");
    }

    private static void assumeReachable() {
        boolean reachable = false;
        try (Connection ignored = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD)) {
            reachable = true;
        } catch (SQLException ignored) {
            // handled below
        }
        if (!reachable && REQUIRED) {
            fail("No reachable SQL Server listener found at " + JDBC_URL);
        }
        Assumptions.assumeTrue(reachable, "No reachable SQL Server listener found");
    }

    private static String waitForServerNameDifferentFrom(String previousServerName) throws SQLException, InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(TIMEOUT_SECONDS));
        SQLException lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD)) {
                String current = serverName(connection);
                if (previousServerName == null || !previousServerName.equals(current)) {
                    return current;
                }
            } catch (SQLException exception) {
                lastFailure = exception;
            }
            Thread.sleep(1_000);
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        fail("SQL Server listener kept returning the same backend within " + TIMEOUT_SECONDS + " seconds");
        return "";
    }

    private static String serverName(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (var resultSet = statement.executeQuery("SELECT CONVERT(varchar(128), SERVERPROPERTY('MachineName'))")) {
                if (!resultSet.next()) {
                    throw new SQLException("SQL Server identity query returned no rows");
                }
                return resultSet.getString(1);
            }
        }
    }

    private static void waitForFile(Path path) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(TIMEOUT_SECONDS));
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(path)) {
                return;
            }
            Thread.sleep(500);
        }
        fail("Timed out waiting for listener switch file: " + path);
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static Path pathProperty(String propertyName) {
        String value = System.getProperty(propertyName, "");
        if (value.isBlank()) {
            return null;
        }
        return Path.of(value);
    }
}

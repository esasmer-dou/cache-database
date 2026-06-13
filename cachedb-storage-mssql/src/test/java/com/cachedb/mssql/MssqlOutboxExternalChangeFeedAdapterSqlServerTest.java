package com.reactor.cachedb.mssql;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import com.reactor.cachedb.core.change.ExternalChangeEvent;
import com.reactor.cachedb.core.change.ExternalChangeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MssqlOutboxExternalChangeFeedAdapterSqlServerTest {

    private static final String JDBC_URL = System.getProperty(
            "cachedb.it.mssql.url",
            "jdbc:sqlserver://127.0.0.1:14333;databaseName=tempdb;encrypt=false;trustServerCertificate=true"
    );
    private static final String JDBC_USER = System.getProperty("cachedb.it.mssql.user", "sa");
    private static final String JDBC_PASSWORD = System.getProperty("cachedb.it.mssql.password", "YourStrong!Passw0rd");

    @BeforeEach
    void setUp() throws Exception {
        assumeReachable();
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_it_outbox_checkpoint");
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_it_outbox");
            statement.executeUpdate("""
                    CREATE TABLE cachedb_it_outbox (
                        id BIGINT IDENTITY(1,1) PRIMARY KEY,
                        entity_name NVARCHAR(200) NOT NULL,
                        entity_id NVARCHAR(200) NOT NULL,
                        event_type NVARCHAR(20) NOT NULL,
                        payload_json NVARCHAR(MAX),
                        entity_version BIGINT NOT NULL,
                        occurred_at DATETIMEOFFSET NOT NULL DEFAULT SYSDATETIMEOFFSET(),
                        event_source NVARCHAR(200) NOT NULL
                    )
                    """);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (!reachable()) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_it_outbox_checkpoint");
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_it_outbox");
        }
    }

    @Test
    void mssqlOutboxAdapterShouldPollEventsAndCheckpointOnceAccepted() throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO cachedb_it_outbox
                    (entity_name, entity_id, event_type, payload_json, entity_version, event_source)
                    VALUES
                    ('OrderEntity', '1001', 'UPSERT', '{"customer_id":42,"status":"OPEN","amount":19.75}', 7, 'orders-outbox'),
                    ('OrderEntity', '1001', 'DELETE', '{"customer_id":42,"status":"DELETED"}', 8, 'orders-outbox')
                    """);
        }

        MssqlOutboxExternalChangeFeedAdapter adapter = MssqlOutboxExternalChangeFeedAdapter.builder(dataSource())
                .adapterName("integration-test-adapter")
                .outboxTable("cachedb_it_outbox")
                .checkpointTable("cachedb_it_outbox_checkpoint")
                .batchSize(10)
                .pollIntervalMillis(Duration.ofMillis(100).toMillis())
                .build();
        ArrayList<ExternalChangeEvent> events = new ArrayList<>();

        assertEquals(2, adapter.pollOnce(events::add));
        assertEquals(0, adapter.pollOnce(events::add));
        adapter.close();

        assertEquals(2, events.size());
        assertEquals("OrderEntity", events.get(0).entityName());
        assertEquals("1001", events.get(0).id());
        assertEquals(ExternalChangeType.UPSERT, events.get(0).type());
        assertEquals(42L, events.get(0).columns().get("customer_id"));
        assertEquals(19.75D, (Double) events.get(0).columns().get("amount"), 0.001D);
        assertEquals(ExternalChangeType.DELETE, events.get(1).type());
        assertEquals(2L, scalarLong("SELECT last_event_id FROM cachedb_it_outbox_checkpoint WHERE adapter_name = 'integration-test-adapter'"));
    }

    private DataSource dataSource() {
        SQLServerDataSource dataSource = new SQLServerDataSource();
        dataSource.setURL(JDBC_URL);
        dataSource.setUser(JDBC_USER);
        dataSource.setPassword(JDBC_PASSWORD);
        return dataSource;
    }

    private long scalarLong(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private boolean reachable() {
        try (Connection ignored = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD)) {
            return true;
        } catch (SQLException ignored) {
            return false;
        }
    }

    private void assumeReachable() {
        boolean reachable = reachable();
        if (!reachable && Boolean.getBoolean("cachedb.it.mssql.required")) {
            fail("No reachable SQL Server test database found at " + JDBC_URL);
        }
        Assumptions.assumeTrue(reachable, "No reachable SQL Server test database found");
    }
}

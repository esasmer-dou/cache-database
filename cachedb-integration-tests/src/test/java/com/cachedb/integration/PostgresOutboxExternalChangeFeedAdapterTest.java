package com.reactor.cachedb.integration;

import com.reactor.cachedb.core.change.ExternalChangeEvent;
import com.reactor.cachedb.core.change.ExternalChangeType;
import com.reactor.cachedb.starter.PostgresOutboxExternalChangeFeedAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class PostgresOutboxExternalChangeFeedAdapterTest {

    private static final String JDBC_USER = System.getProperty("cachedb.it.postgres.user", "postgres");
    private static final String JDBC_PASSWORD = System.getProperty("cachedb.it.postgres.password", "postgresql");
    private String jdbcUrl;

    @BeforeEach
    void setUp() throws Exception {
        jdbcUrl = resolveJdbcUrl();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_it_outbox_checkpoint");
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_it_outbox");
            statement.executeUpdate("""
                    CREATE TABLE cachedb_it_outbox (
                        id BIGSERIAL PRIMARY KEY,
                        entity_name TEXT NOT NULL,
                        entity_id TEXT NOT NULL,
                        event_type TEXT NOT NULL,
                        payload_json TEXT,
                        entity_version BIGINT NOT NULL,
                        occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        event_source TEXT NOT NULL
                    )
                    """);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_it_outbox_checkpoint");
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_it_outbox");
        }
    }

    @Test
    void postgresOutboxAdapterShouldPollEventsAndCheckpointOnceAccepted() throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO cachedb_it_outbox
                    (entity_name, entity_id, event_type, payload_json, entity_version, event_source)
                    VALUES
                    ('OrderEntity', '1001', 'UPSERT', '{"customer_id":42,"status":"OPEN","amount":19.75}', 7, 'orders-outbox'),
                    ('OrderEntity', '1001', 'DELETE', '{"customer_id":42,"status":"DELETED"}', 8, 'orders-outbox')
                    """);
        }

        PostgresOutboxExternalChangeFeedAdapter adapter = PostgresOutboxExternalChangeFeedAdapter.builder(dataSource())
                .adapterName("integration-test-adapter")
                .outboxTable("cachedb_it_outbox")
                .checkpointTable("cachedb_it_outbox_checkpoint")
                .batchSize(10)
                .pollIntervalMillis(Duration.ofMillis(100).toMillis())
                .build();
        ArrayList<ExternalChangeEvent> events = new ArrayList<>();

        Assertions.assertEquals(2, adapter.pollOnce(events::add));
        Assertions.assertEquals(0, adapter.pollOnce(events::add));
        adapter.close();

        Assertions.assertEquals(2, events.size());
        Assertions.assertEquals("OrderEntity", events.get(0).entityName());
        Assertions.assertEquals("1001", events.get(0).id());
        Assertions.assertEquals(ExternalChangeType.UPSERT, events.get(0).type());
        Assertions.assertEquals(42L, events.get(0).columns().get("customer_id"));
        Assertions.assertEquals(19.75D, (Double) events.get(0).columns().get("amount"), 0.001D);
        Assertions.assertEquals(ExternalChangeType.DELETE, events.get(1).type());
        Assertions.assertEquals(2L, countRows("SELECT last_event_id FROM cachedb_it_outbox_checkpoint WHERE adapter_name = 'integration-test-adapter'"));
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(jdbcUrl);
        dataSource.setUser(JDBC_USER);
        dataSource.setPassword(JDBC_PASSWORD);
        return dataSource;
    }

    private long countRows(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private String resolveJdbcUrl() {
        List<String> candidates = new ArrayList<>();
        String explicit = System.getProperty("cachedb.it.postgres.url");
        if (explicit != null && !explicit.isBlank()) {
            candidates.add(explicit);
        }
        candidates.add(System.getProperty("cachedb.prod.postgres.url", "jdbc:postgresql://127.0.0.1:5432/postgres"));
        candidates.add("jdbc:postgresql://127.0.0.1:55432/postgres");
        candidates.add("jdbc:postgresql://localhost:5432/postgres");
        for (String candidate : candidates) {
            try (Connection ignored = DriverManager.getConnection(candidate, JDBC_USER, JDBC_PASSWORD)) {
                return candidate;
            } catch (SQLException ignored) {
            }
        }
        throw new IllegalStateException("No reachable PostgreSQL test database found");
    }
}

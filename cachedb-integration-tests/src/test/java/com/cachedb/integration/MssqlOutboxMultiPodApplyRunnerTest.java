package com.reactor.cachedb.integration;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import com.reactor.cachedb.core.api.CacheSession;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.change.ExternalChangeApplyMode;
import com.reactor.cachedb.core.change.ExternalChangeApplyResult;
import com.reactor.cachedb.core.change.ExternalChangeEvent;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;
import com.reactor.cachedb.mssql.MssqlOutboxExternalChangeFeedAdapter;
import com.reactor.cachedb.starter.ExternalChangeApplyRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public final class MssqlOutboxMultiPodApplyRunnerTest {

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
            statement.executeUpdate("""
                    INSERT INTO cachedb_it_outbox
                    (entity_name, entity_id, event_type, payload_json, entity_version, event_source)
                    VALUES
                    ('OrderEntity', '1001', 'UPSERT', '{"status":"OPEN"}', 7, 'orders-outbox'),
                    ('OrderEntity', '1002', 'DELETE', '{}', 8, 'orders-outbox')
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
    void sameAdapterNameShouldShareCheckpointAcrossApplyRunnerPods() {
        ArrayList<ExternalChangeEvent> applied = new ArrayList<>();
        ExternalChangeApplyRunner runner = ExternalChangeApplyRunner
                .builder(noopSession(), emptyRegistry())
                .mode(ExternalChangeApplyMode.CACHE_ONLY)
                .handler("OrderEntity", event -> {
                    applied.add(event);
                    return ExternalChangeApplyResult.applied(
                            event,
                            event.id(),
                            ExternalChangeApplyMode.CACHE_ONLY,
                            "handled by mssql multi-pod smoke"
                    );
                })
                .build();
        MssqlOutboxExternalChangeFeedAdapter podA = adapter();
        MssqlOutboxExternalChangeFeedAdapter podB = adapter();

        assertEquals(2, podA.pollOnce(runner));
        assertEquals(0, podB.pollOnce(runner));

        assertEquals(2, applied.size());
        podA.close();
        podB.close();
    }

    private MssqlOutboxExternalChangeFeedAdapter adapter() {
        return MssqlOutboxExternalChangeFeedAdapter.builder(dataSource())
                .adapterName("shared-mssql-apply-runner")
                .outboxTable("cachedb_it_outbox")
                .checkpointTable("cachedb_it_outbox_checkpoint")
                .batchSize(10)
                .build();
    }

    private DataSource dataSource() {
        SQLServerDataSource dataSource = new SQLServerDataSource();
        dataSource.setURL(JDBC_URL);
        dataSource.setUser(JDBC_USER);
        dataSource.setPassword(JDBC_PASSWORD);
        return dataSource;
    }

    private CacheSession noopSession() {
        return new CacheSession() {
            @Override
            public <T, ID> EntityRepository<T, ID> repository(EntityMetadata<T, ID> metadata, EntityCodec<T> codec) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T, ID> EntityRepository<T, ID> repository(
                    EntityMetadata<T, ID> metadata,
                    EntityCodec<T> codec,
                    CachePolicy cachePolicy
            ) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private EntityRegistry emptyRegistry() {
        return new EntityRegistry() {
            @Override
            public <T, ID> EntityBinding<T, ID> register(
                    EntityMetadata<T, ID> metadata,
                    EntityCodec<T> codec,
                    CachePolicy cachePolicy,
                    com.reactor.cachedb.core.relation.RelationBatchLoader<T> relationBatchLoader,
                    com.reactor.cachedb.core.page.EntityPageLoader<T> pageLoader
            ) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T, ID, P> com.reactor.cachedb.core.projection.EntityProjectionBinding<T, P, ID> registerProjection(
                    EntityMetadata<T, ID> metadata,
                    com.reactor.cachedb.core.projection.EntityProjection<T, P, ID> projection
            ) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<EntityBinding<?, ?>> find(String entityName) {
                return Optional.empty();
            }

            @Override
            public Optional<com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?>> findProjection(
                    String entityName,
                    String projectionName
            ) {
                return Optional.empty();
            }

            @Override
            public Collection<com.reactor.cachedb.core.projection.EntityProjectionBinding<?, ?, ?>> projections(String entityName) {
                return List.of();
            }

            @Override
            public Collection<EntityBinding<?, ?>> all() {
                return List.of();
            }
        };
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

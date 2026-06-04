package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.page.EntityPageLoader;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.projection.EntityProjectionBinding;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;
import com.reactor.cachedb.core.relation.RelationBatchLoader;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationRedisMemoryEstimatorTest {

    @Test
    void shouldEstimateRedisMemoryFromDiscoveredTablesAndHotWindow() throws SQLException {
        DataSource dataSource = newDataSource("redis-memory-estimator");
        seedRows(dataSource, List.of(
                """
                CREATE TABLE customer_account (
                    customer_id BIGINT PRIMARY KEY,
                    tax_number VARCHAR(32) NOT NULL,
                    customer_type VARCHAR(24) NOT NULL
                )
                """,
                """
                CREATE TABLE customer_order (
                    order_id BIGINT PRIMARY KEY,
                    customer_id BIGINT NOT NULL,
                    order_date TIMESTAMP NOT NULL,
                    order_amount DECIMAL(18, 2) NOT NULL,
                    currency_code VARCHAR(8) NOT NULL,
                    order_type VARCHAR(24) NOT NULL,
                    CONSTRAINT fk_customer_order_customer FOREIGN KEY (customer_id)
                        REFERENCES customer_account (customer_id)
                )
                """,
                "INSERT INTO customer_account (customer_id, tax_number, customer_type) VALUES (1, 'TR1001', 'CORP')",
                "INSERT INTO customer_account (customer_id, tax_number, customer_type) VALUES (2, 'TR1002', 'SMB')",
                "INSERT INTO customer_order (order_id, customer_id, order_date, order_amount, currency_code, order_type) VALUES (101, 1, TIMESTAMP '2026-04-01 10:00:00', 100.00, 'TRY', 'ONLINE')",
                "INSERT INTO customer_order (order_id, customer_id, order_date, order_amount, currency_code, order_type) VALUES (102, 1, TIMESTAMP '2026-04-02 11:00:00', 120.00, 'TRY', 'ONLINE')",
                "INSERT INTO customer_order (order_id, customer_id, order_date, order_amount, currency_code, order_type) VALUES (201, 2, TIMESTAMP '2026-04-03 12:00:00', 90.00, 'USD', 'STORE')"
        ));
        MigrationSchemaDiscovery discovery = new MigrationSchemaDiscovery(dataSource, emptyRegistry());
        MigrationRedisMemoryEstimator estimator = new MigrationRedisMemoryEstimator(dataSource, discovery);
        MigrationPlanner.Result plan = new MigrationPlanner().plan(new MigrationPlanner.Request(
                "customer-orders",
                "customer_account",
                "customer_id",
                "customer_order",
                "order_id",
                "customer_id",
                "order_date",
                "DESC",
                0L,
                0L,
                2L,
                3L,
                50,
                1000,
                true,
                false,
                false,
                false,
                true,
                false,
                true,
                true,
                true
        ));

        MigrationRedisMemoryEstimator.Result result = estimator.estimate(plan);

        assertEquals("JDBC_SAMPLE", result.source());
        assertEquals("HIGH", result.confidence());
        assertEquals(2L, result.estimatedRootRows());
        assertEquals(3L, result.estimatedChildRows());
        assertEquals(2L, result.rootHotRows());
        assertEquals(3L, result.childHotRows());
        assertTrue(result.rootAveragePostgresRowBytes() > 0L);
        assertTrue(result.childAveragePostgresRowBytes() > 0L);
        assertTrue(result.subtotalBytes() > 0L);
        assertTrue(result.estimatedTotalBytes() > result.subtotalBytes());
        assertTrue(result.recommendedMaxmemoryBytes() > result.estimatedTotalBytes());
        assertNotNull(component(result, "ROOT_ENTITY"));
        assertNotNull(component(result, "CHILD_PROJECTION"));
        assertNotNull(component(result, "INDEX_AND_HOTSET"));
        assertNotNull(component(result, "HEADROOM"));
    }

    private MigrationRedisMemoryEstimator.Component component(
            MigrationRedisMemoryEstimator.Result result,
            String code
    ) {
        return result.components().stream()
                .filter(item -> item.code().equals(code))
                .findFirst()
                .orElse(null);
    }

    private DataSource newDataSource(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private void seedRows(DataSource dataSource, List<String> statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private EntityRegistry emptyRegistry() {
        return new EntityRegistry() {
            @Override
            public <T, ID> EntityBinding<T, ID> register(EntityMetadata<T, ID> metadata, EntityCodec<T> codec, CachePolicy cachePolicy, RelationBatchLoader<T> relationBatchLoader, EntityPageLoader<T> pageLoader) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T, ID, P> EntityProjectionBinding<T, P, ID> registerProjection(EntityMetadata<T, ID> metadata, EntityProjection<T, P, ID> projection) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<EntityBinding<?, ?>> find(String entityName) {
                return Optional.empty();
            }

            @Override
            public Optional<EntityProjectionBinding<?, ?, ?>> findProjection(String entityName, String projectionName) {
                return Optional.empty();
            }

            @Override
            public Collection<EntityProjectionBinding<?, ?, ?>> projections(String entityName) {
                return List.of();
            }

            @Override
            public Collection<EntityBinding<?, ?>> all() {
                return List.of();
            }
        };
    }
}

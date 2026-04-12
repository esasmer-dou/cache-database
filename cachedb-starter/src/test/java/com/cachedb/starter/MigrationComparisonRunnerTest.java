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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationComparisonRunnerTest {

    @Test
    void shouldCompareBaselineSqlAgainstResolvedCacheRoute() throws SQLException {
        DataSource dataSource = newDataSource("migration-compare");
        seedRows(dataSource, List.of(
                """
                CREATE TABLE customer_order (
                    order_id BIGINT PRIMARY KEY,
                    customer_id BIGINT NOT NULL,
                    order_date TIMESTAMP NOT NULL,
                    order_amount DECIMAL(18, 2)
                )
                """,
                "INSERT INTO customer_order (order_id, customer_id, order_date, order_amount) VALUES (101, 1, TIMESTAMP '2026-04-01 10:00:00', 100.00)",
                "INSERT INTO customer_order (order_id, customer_id, order_date, order_amount) VALUES (102, 1, TIMESTAMP '2026-04-02 10:00:00', 110.00)",
                "INSERT INTO customer_order (order_id, customer_id, order_date, order_amount) VALUES (103, 1, TIMESTAMP '2026-04-03 10:00:00', 120.00)",
                "INSERT INTO customer_order (order_id, customer_id, order_date, order_amount) VALUES (201, 2, TIMESTAMP '2026-04-01 11:00:00', 90.00)",
                "INSERT INTO customer_order (order_id, customer_id, order_date, order_amount) VALUES (202, 2, TIMESTAMP '2026-04-02 11:00:00', 95.00)"
        ));

        MigrationSchemaDiscovery discovery = new MigrationSchemaDiscovery(dataSource, emptyRegistry());
        MigrationWarmRunner warmRunner = new MigrationWarmRunner(dataSource, ignored -> Optional.empty());
        MigrationComparisonRunner runner = new MigrationComparisonRunner(
                dataSource,
                discovery,
                warmRunner,
                new FakeCacheRouteExecutorFactory(Map.of(
                        "1", List.of("103", "102"),
                        "2", List.of("202", "201")
                ))
        );

        MigrationComparisonRunner.Result result = runner.execute(new MigrationComparisonRunner.Request(
                new MigrationPlanner.Request(
                        "customer-orders",
                        "customer_account",
                        "customer_id",
                        "customer_order",
                        "order_id",
                        "customer_id",
                        "order_date",
                        "DESC",
                        2L,
                        5L,
                        3L,
                        3L,
                        2,
                        3,
                        true,
                        false,
                        false,
                        false,
                        true,
                        false,
                        true,
                        true,
                        true
                ),
                false,
                true,
                25,
                25,
                25,
                "",
                2,
                1,
                4,
                2,
                "",
                ""
        ));

        assertEquals("projection:CustomerAccountCustomerOrderSummaryHot", result.cacheRouteLabel());
        assertEquals(4L, result.baselineMetrics().operations());
        assertEquals(4L, result.cacheMetrics().operations());
        assertEquals(2, result.sampleComparisons().size());
        assertTrue(result.sampleComparisons().stream().allMatch(MigrationComparisonRunner.SampleComparison::exactMatch));
        assertTrue(result.baselineSqlTemplate().contains("WHERE customer_id = :sample_root_id"));
        assertEquals(MigrationComparisonAssessment.ParityStatus.EXACT, result.assessment().parityStatus());
        assertTrue(result.assessment().exactMatchCount() >= 2L);
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

    private static final class FakeCacheRouteExecutorFactory implements MigrationComparisonRunner.CacheRouteExecutorFactory {
        private final Map<String, List<String>> rowsByRootId;

        private FakeCacheRouteExecutorFactory(Map<String, List<String>> rowsByRootId) {
            this.rowsByRootId = new LinkedHashMap<>(rowsByRootId);
        }

        @Override
        public Optional<MigrationComparisonRunner.CacheRouteExecutor> resolve(MigrationPlanner.Result plan, MigrationComparisonRunner.Request request) {
            return Optional.of(new MigrationComparisonRunner.CacheRouteExecutor() {
                @Override
                public String routeLabel() {
                    return "projection:" + plan.summaryProjectionName();
                }

                @Override
                public String idColumn() {
                    return plan.request().childPrimaryKeyColumn();
                }

                @Override
                public boolean usesProjection() {
                    return true;
                }

                @Override
                public MigrationComparisonRunner.RoutePage execute(Object sampleRootId, int pageSize) {
                    List<String> ids = rowsByRootId.getOrDefault(String.valueOf(sampleRootId), List.of());
                    List<String> page = ids.subList(0, Math.min(Math.max(1, pageSize), ids.size()));
                    return new MigrationComparisonRunner.RoutePage(page.size(), List.copyOf(page));
                }
            });
        }
    }
}

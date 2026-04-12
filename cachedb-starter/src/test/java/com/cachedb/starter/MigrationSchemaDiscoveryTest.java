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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationSchemaDiscoveryTest {

    @Test
    void shouldDiscoverTablesAndForeignKeyRouteSuggestions() throws SQLException {
        DataSource dataSource = newDataSource("schema-discovery");
        seedRows(dataSource, List.of(
                """
                CREATE TABLE customer_account (
                    customer_id BIGINT PRIMARY KEY,
                    tax_number VARCHAR(32),
                    customer_type VARCHAR(32)
                )
                """,
                """
                CREATE TABLE customer_order (
                    order_id BIGINT PRIMARY KEY,
                    customer_id BIGINT NOT NULL,
                    order_date TIMESTAMP NOT NULL,
                    order_amount DECIMAL(18, 2),
                    currency_code VARCHAR(3),
                    order_type VARCHAR(32),
                    CONSTRAINT fk_customer_order_customer FOREIGN KEY (customer_id) REFERENCES customer_account(customer_id)
                )
                """,
                """
                CREATE VIEW customer_order_metrics_v AS
                SELECT customer_id, COUNT(*) AS order_count
                FROM customer_order
                GROUP BY customer_id
                """
        ));

        MigrationSchemaDiscovery discovery = new MigrationSchemaDiscovery(dataSource, emptyRegistry());

        MigrationSchemaDiscovery.Result result = discovery.discover();

        assertEquals(3, result.tables().size());
        assertTrue(result.tables().stream().anyMatch(table ->
                table.tableName().equalsIgnoreCase("customer_account")
                        && table.primaryKeyColumn().equalsIgnoreCase("customer_id")));
        assertTrue(result.tables().stream().anyMatch(table ->
                table.tableName().equalsIgnoreCase("customer_order")
                        && table.temporalColumns().stream().anyMatch(column -> column.equalsIgnoreCase("order_date"))
                        && table.foreignKeyColumns().stream().anyMatch(column -> column.equalsIgnoreCase("customer_id"))));
        assertTrue(result.tables().stream().anyMatch(table ->
                table.tableName().equalsIgnoreCase("customer_order_metrics_v")
                        && table.objectType().equalsIgnoreCase("VIEW")));

        assertEquals(1, result.routeSuggestions().size());
        MigrationSchemaDiscovery.RouteSuggestion suggestion = result.routeSuggestions().get(0);
        assertTrue(suggestion.relationColumn().equalsIgnoreCase("customer_id"));
        assertTrue(suggestion.sortColumn().equalsIgnoreCase("order_date"));
        assertTrue(suggestion.sortCandidates().stream().anyMatch(column -> column.equalsIgnoreCase("order_date")));
        assertTrue(suggestion.plannerRequest().rootTableOrEntity().equalsIgnoreCase("customer_account"));
        assertTrue(suggestion.plannerRequest().childTableOrEntity().equalsIgnoreCase("customer_order"));
        assertTrue(suggestion.plannerRequest().relationColumn().equalsIgnoreCase("customer_id"));
        assertFalse(suggestion.rankedSortCandidate());
        assertTrue(result.warnings().isEmpty());
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

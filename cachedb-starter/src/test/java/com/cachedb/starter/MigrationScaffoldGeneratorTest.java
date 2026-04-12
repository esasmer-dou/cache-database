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

import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationScaffoldGeneratorTest {

    @Test
    void shouldGenerateEntityAndLoaderSkeletonsFromDiscovery() throws SQLException {
        DataSource dataSource = newDataSource("migration-scaffold");
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
                """
        ));

        MigrationScaffoldGenerator generator = new MigrationScaffoldGenerator(
                new MigrationSchemaDiscovery(dataSource, emptyRegistry())
        );

        MigrationScaffoldGenerator.Result result = generator.generate(new MigrationScaffoldGenerator.Request(
                new MigrationPlanner.Request(
                        "customer-orders",
                        "customer_account",
                        "customer_id",
                        "customer_order",
                        "order_id",
                        "customer_id",
                        "order_date",
                        "DESC",
                        100L,
                        5_000L,
                        40L,
                        2_000L,
                        100,
                        1_000,
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
                "com.acme.cachedb.migration",
                "",
                "",
                "",
                "",
                true,
                true
        ));

        assertTrue(result.files().stream().anyMatch(file ->
                file.fileName().equals("CustomerAccountEntity.java")
                        && file.content().contains("@CacheRelation")
                        && file.content().contains("customerOrders")));
        assertTrue(result.files().stream().anyMatch(file ->
                file.fileName().equals("CustomerOrderEntity.java")
                        && file.content().contains("@CacheNamedQuery(\"listByCustomerAccount\")")
                        && file.content().contains("QuerySort.desc(\"order_date\")")));
        assertTrue(result.files().stream().anyMatch(file ->
                file.fileName().equals("CustomerAccountCustomerOrderRelationBatchLoader.java")
                        && file.content().contains("implements RelationBatchLoader<CustomerAccountEntity>")));
        assertTrue(result.files().stream().anyMatch(file ->
                file.fileName().equals("CustomerAccountCustomerOrderReadModels.java")
                        && file.content().contains("Suggested projection name: CustomerAccountCustomerOrderSummaryHot")));
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

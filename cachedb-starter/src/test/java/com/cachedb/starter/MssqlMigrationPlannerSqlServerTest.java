package com.reactor.cachedb.starter;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MssqlMigrationPlannerSqlServerTest {

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
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_mssql_orders");
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_mssql_customers");
            statement.executeUpdate("""
                    CREATE TABLE cachedb_mssql_customers (
                        customer_id BIGINT NOT NULL PRIMARY KEY,
                        tax_number NVARCHAR(40) NOT NULL,
                        customer_type NVARCHAR(40) NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE cachedb_mssql_orders (
                        order_id BIGINT NOT NULL PRIMARY KEY,
                        customer_id BIGINT NOT NULL,
                        order_date DATE NOT NULL,
                        order_amount DECIMAL(18,2) NOT NULL,
                        currency_code NVARCHAR(3) NOT NULL,
                        order_type NVARCHAR(40) NOT NULL,
                        entity_version BIGINT NOT NULL,
                        CONSTRAINT fk_cachedb_mssql_orders_customer
                            FOREIGN KEY (customer_id) REFERENCES cachedb_mssql_customers(customer_id)
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO cachedb_mssql_customers (customer_id, tax_number, customer_type)
                    VALUES (1, 'TAX-1', 'VIP'), (2, 'TAX-2', 'STANDARD')
                    """);
            statement.executeUpdate("""
                    INSERT INTO cachedb_mssql_orders
                    (order_id, customer_id, order_date, order_amount, currency_code, order_type, entity_version)
                    VALUES
                    (101, 1, '2026-01-01', 10.00, 'USD', 'ONLINE', 1),
                    (102, 1, '2026-02-01', 20.00, 'USD', 'ONLINE', 2),
                    (201, 2, '2026-03-01', 30.00, 'EUR', 'STORE', 3)
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
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_mssql_orders");
            statement.executeUpdate("DROP TABLE IF EXISTS cachedb_mssql_customers");
        }
    }

    @Test
    void mssqlSchemaWarmAndComparisonSqlShouldRunAgainstSqlServer() {
        DataSource dataSource = dataSource();
        MigrationSchemaDiscovery discovery = new MigrationSchemaDiscovery(
                dataSource,
                new DefaultEntityRegistry(ResourceLimits.defaults())
        );

        MigrationSchemaDiscovery.Result discovered = discovery.discover();

        assertTrue(discovered.tables().stream().anyMatch(table -> table.tableName().equalsIgnoreCase("cachedb_mssql_orders")));
        assertFalse(discovered.routeSuggestions().isEmpty());

        MigrationPlanner.Request plannerRequest = new MigrationPlanner.Request(
                "mssql-customer-orders",
                "cachedb_mssql_customers",
                "customer_id",
                "cachedb_mssql_orders",
                "order_id",
                "customer_id",
                "order_date",
                "DESC",
                2L,
                3L,
                2L,
                2L,
                2,
                2,
                true,
                false,
                false,
                false,
                true,
                false,
                true,
                true,
                true
        );
        MigrationWarmRunner warmRunner = new MigrationWarmRunner(dataSource, fakeHydrators());
        MigrationWarmRunner.Result warmResult = warmRunner.execute(new MigrationWarmRunner.Request(
                plannerRequest,
                false,
                true,
                10,
                10,
                10
        ));

        assertEquals(3L, warmResult.childRowsRead());
        assertFalse(warmResult.childWarmSql().contains("LIMIT"));
        assertTrue(warmResult.childWarmSql().contains("ROW_NUMBER() OVER"));

        MigrationComparisonRunner comparisonRunner = new MigrationComparisonRunner(
                dataSource,
                discovery,
                warmRunner,
                (plan, request) -> Optional.of(new MigrationComparisonRunner.CacheRouteExecutor() {
                    @Override
                    public String routeLabel() {
                        return "projection:mssql-test";
                    }

                    @Override
                    public String idColumn() {
                        return "order_id";
                    }

                    @Override
                    public boolean usesProjection() {
                        return true;
                    }

                    @Override
                    public MigrationComparisonRunner.RoutePage execute(Object sampleRootId, int pageSize) {
                        return new MigrationComparisonRunner.RoutePage(2, List.of("102", "101"));
                    }
                })
        );
        MigrationComparisonRunner.Result comparison = comparisonRunner.execute(new MigrationComparisonRunner.Request(
                plannerRequest,
                false,
                false,
                10,
                10,
                10,
                "1",
                1,
                0,
                1,
                2,
                "",
                ""
        ));

        assertTrue(comparison.baselineSqlTemplate().contains("OFFSET 0 ROWS FETCH NEXT :page_size ROWS ONLY"));
        assertEquals(1, comparison.sampleComparisons().size());
        assertTrue(comparison.sampleComparisons().get(0).exactMatch());
    }

    private MigrationWarmRunner.WarmEntityHydratorFactory fakeHydrators() {
        return surface -> {
            if ("cachedb_mssql_orders".equalsIgnoreCase(surface)) {
                return Optional.of(fakeHydrator("OrderEntity", "cachedb_mssql_orders", "order_id"));
            }
            if ("cachedb_mssql_customers".equalsIgnoreCase(surface)) {
                return Optional.of(fakeHydrator("CustomerEntity", "cachedb_mssql_customers", "customer_id"));
            }
            return Optional.empty();
        };
    }

    private MigrationWarmRunner.WarmEntityHydrator fakeHydrator(String entityName, String tableName, String idColumn) {
        return new MigrationWarmRunner.WarmEntityHydrator() {
            @Override
            public String entityName() {
                return entityName;
            }

            @Override
            public String tableName() {
                return tableName;
            }

            @Override
            public String idColumn() {
                return idColumn;
            }

            @Override
            public String versionColumn() {
                return "entity_version";
            }

            @Override
            public String deletedColumn() {
                return "";
            }

            @Override
            public String deletedMarkerValue() {
                return "true";
            }

            @Override
            public void hydrate(Map<String, Object> row, long version) {
                throw new AssertionError("Dry-run warm must not hydrate rows");
            }
        };
    }

    private DataSource dataSource() {
        SQLServerDataSource dataSource = new SQLServerDataSource();
        dataSource.setURL(JDBC_URL);
        dataSource.setUser(JDBC_USER);
        dataSource.setPassword(JDBC_PASSWORD);
        return dataSource;
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

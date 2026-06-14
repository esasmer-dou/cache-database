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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
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
    private static final int VOLUME_CUSTOMER_COUNT = Integer.getInteger("cachedb.it.mssql.migrationCustomers", 200);
    private static final int VOLUME_ORDERS_PER_CUSTOMER = Integer.getInteger("cachedb.it.mssql.migrationOrdersPerCustomer", 50);
    private static final int VOLUME_HOT_WINDOW_PER_ROOT = Integer.getInteger("cachedb.it.mssql.migrationHotWindowPerRoot", 25);

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

    @Test
    void mssqlMigrationWarmAndComparisonShouldHandleRepresentativeWindowedVolume() throws Exception {
        replaceWithVolumeFixture(VOLUME_CUSTOMER_COUNT, VOLUME_ORDERS_PER_CUSTOMER);
        DataSource dataSource = dataSource();
        MigrationSchemaDiscovery discovery = new MigrationSchemaDiscovery(
                dataSource,
                new DefaultEntityRegistry(ResourceLimits.defaults())
        );
        MigrationSchemaDiscovery.Result discovered = discovery.discover();

        assertTrue(discovered.tables().stream().anyMatch(table -> table.tableName().equalsIgnoreCase("cachedb_mssql_orders")));

        MigrationPlanner.Request plannerRequest = new MigrationPlanner.Request(
                "mssql-customer-orders-volume",
                "cachedb_mssql_customers",
                "customer_id",
                "cachedb_mssql_orders",
                "order_id",
                "customer_id",
                "order_date",
                "DESC",
                VOLUME_CUSTOMER_COUNT,
                (long) VOLUME_CUSTOMER_COUNT * VOLUME_ORDERS_PER_CUSTOMER,
                VOLUME_ORDERS_PER_CUSTOMER,
                VOLUME_ORDERS_PER_CUSTOMER,
                20,
                VOLUME_HOT_WINDOW_PER_ROOT,
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
                true,
                true,
                256,
                128,
                128
        ));

        long expectedWindowedRows = (long) VOLUME_CUSTOMER_COUNT
                * Math.min(VOLUME_ORDERS_PER_CUSTOMER, warmResult.plan().recommendedHotWindowPerRoot());
        assertEquals(expectedWindowedRows, warmResult.childRowsRead());
        assertEquals(VOLUME_CUSTOMER_COUNT, warmResult.rootRowsRead());
        assertTrue(warmResult.childWarmSql().contains("ROW_NUMBER() OVER"));
        assertFalse(warmResult.childWarmSql().contains("LIMIT"));

        int sampleCustomerId = Math.min(15, VOLUME_CUSTOMER_COUNT);
        int samplePageSize = Math.min(20, VOLUME_ORDERS_PER_CUSTOMER);
        List<String> expectedTopIds = expectedTopOrderIds(sampleCustomerId, samplePageSize, VOLUME_ORDERS_PER_CUSTOMER);
        MigrationComparisonRunner comparisonRunner = new MigrationComparisonRunner(
                dataSource,
                discovery,
                warmRunner,
                (plan, request) -> Optional.of(new MigrationComparisonRunner.CacheRouteExecutor() {
                    @Override
                    public String routeLabel() {
                        return "projection:mssql-volume-window";
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
                        return new MigrationComparisonRunner.RoutePage(
                                Math.min(pageSize, expectedTopIds.size()),
                                expectedTopIds.subList(0, Math.min(pageSize, expectedTopIds.size()))
                        );
                    }
                })
        );
        MigrationComparisonRunner.Result comparison = comparisonRunner.execute(new MigrationComparisonRunner.Request(
                plannerRequest,
                false,
                false,
                256,
                128,
                128,
                String.valueOf(sampleCustomerId),
                1,
                0,
                1,
                samplePageSize,
                "",
                ""
        ));

        assertTrue(comparison.baselineSqlTemplate().contains("OFFSET 0 ROWS FETCH NEXT :page_size ROWS ONLY"));
        assertEquals(1, comparison.sampleComparisons().size());
        assertTrue(comparison.sampleComparisons().get(0).exactMatch());
    }

    private void replaceWithVolumeFixture(int customerCount, int ordersPerCustomer) throws SQLException {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM cachedb_mssql_orders");
            statement.executeUpdate("DELETE FROM cachedb_mssql_customers");
        }
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
             PreparedStatement customerInsert = connection.prepareStatement("""
                     INSERT INTO cachedb_mssql_customers (customer_id, tax_number, customer_type)
                     VALUES (?, ?, ?)
                     """);
             PreparedStatement orderInsert = connection.prepareStatement("""
                     INSERT INTO cachedb_mssql_orders
                     (order_id, customer_id, order_date, order_amount, currency_code, order_type, entity_version)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     """)) {
            connection.setAutoCommit(false);
            for (int customerId = 1; customerId <= customerCount; customerId++) {
                customerInsert.setLong(1, customerId);
                customerInsert.setString(2, "TAX-" + customerId);
                customerInsert.setString(3, customerId % 5 == 0 ? "VIP" : "STANDARD");
                customerInsert.addBatch();
                for (int sequence = 1; sequence <= ordersPerCustomer; sequence++) {
                    long orderId = orderId(customerId, sequence);
                    orderInsert.setLong(1, orderId);
                    orderInsert.setLong(2, customerId);
                    orderInsert.setObject(3, LocalDate.of(2026, 1, 1).plusDays(sequence));
                    orderInsert.setBigDecimal(4, java.math.BigDecimal.valueOf(1000L + orderId, 2));
                    orderInsert.setString(5, sequence % 3 == 0 ? "EUR" : "USD");
                    orderInsert.setString(6, sequence % 2 == 0 ? "ONLINE" : "STORE");
                    orderInsert.setLong(7, orderId);
                    orderInsert.addBatch();
                }
                if (customerId % 50 == 0) {
                    customerInsert.executeBatch();
                    orderInsert.executeBatch();
                }
            }
            customerInsert.executeBatch();
            orderInsert.executeBatch();
            connection.commit();
        }
    }

    private static List<String> expectedTopOrderIds(int customerId, int pageSize, int ordersPerCustomer) {
        ArrayList<String> ids = new ArrayList<>();
        for (int sequence = ordersPerCustomer; sequence >= 1 && ids.size() < pageSize; sequence--) {
            ids.add(String.valueOf(orderId(customerId, sequence)));
        }
        return List.copyOf(ids);
    }

    private static long orderId(int customerId, int sequence) {
        return customerId * 100_000L + sequence;
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

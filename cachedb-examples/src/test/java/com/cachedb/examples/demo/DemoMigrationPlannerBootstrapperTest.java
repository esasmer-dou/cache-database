package com.reactor.cachedb.examples.demo;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoMigrationPlannerBootstrapperTest {

    @Test
    void shouldCreateSeededCustomerOrderSchemaAndViews() throws SQLException {
        DataSource dataSource = newDataSource("migration-demo-bootstrap");
        DemoMigrationPlannerBootstrapper bootstrapper = new DemoMigrationPlannerBootstrapper(
                dataSource,
                () -> { },
                () -> { },
                () -> { }
        );

        var result = bootstrapper.bootstrap(new com.reactor.cachedb.starter.MigrationPlannerDemoSupport.BootstrapRequest(48, 6, 1200));

        assertEquals("MigrationDemoCustomerEntity", result.rootSurface());
        assertEquals("MigrationDemoOrderEntity", result.childSurface());
        assertEquals(48L, result.customerCount());
        assertTrue(result.orderCount() > 48L);
        assertTrue(result.hottestCustomerOrderCount() >= 1050L);
        assertEquals(3, result.viewNames().size());
        assertEquals("MigrationDemoCustomerEntity", result.plannerDefaults().rootTableOrEntity());
        assertEquals("MigrationDemoOrderEntity", result.plannerDefaults().childTableOrEntity());
        assertEquals(48L, countRows(dataSource, "SELECT COUNT(*) FROM cachedb_migration_demo_customers"));
        assertEquals(result.orderCount(), countRows(dataSource, "SELECT COUNT(*) FROM cachedb_migration_demo_orders"));
        assertEquals(3L, countRows(
                dataSource,
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_name IN (
                    'CACHEDB_MIGRATION_DEMO_CUSTOMER_ORDER_TIMELINE_V',
                    'CACHEDB_MIGRATION_DEMO_CUSTOMER_METRICS_V',
                    'CACHEDB_MIGRATION_DEMO_RANKED_ORDERS_V'
                )
                AND table_type = 'VIEW'
                """
        ));
    }

    private DataSource newDataSource(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private long countRows(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}

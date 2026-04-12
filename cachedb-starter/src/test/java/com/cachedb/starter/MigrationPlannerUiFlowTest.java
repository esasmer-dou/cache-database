package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.AdminHttpConfig;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationPlannerUiFlowTest {

    @Test
    void shouldRenderStepperProgressAndModeAwarePlannerPage() throws Exception {
        try (TestHarness harness = new TestHarness("planner-render")) {
            CacheDatabaseAdminHttpServer.DashboardTemplateModel page =
                    harness.adminHttpServer.renderMigrationPlannerTemplateModel("tr", "/cachedb-admin/migration-planner");

            String body = page.bodyMarkup();
            assertTrue(body.contains("planner-stepper"));
            assertTrue(body.contains("plannerProgressFill"));
            assertTrue(body.contains("data-planner-mode=\"beginner\""));
            assertTrue(body.contains("data-planner-mode=\"advanced\""));
            assertTrue(body.contains("const apiBase='/cachedb-admin';"));
            assertTrue(body.contains("/api/migration-planner/discovery"));
            assertTrue(body.contains("/api/migration-planner/warm"));
            assertTrue(body.contains("/api/migration-planner/compare"));
        }
    }

    @Test
    void shouldServeDiscoveryPlanAndScaffoldEndpointsThroughAdminDispatch() throws Exception {
        try (TestHarness harness = new TestHarness("planner-dispatch")) {
            harness.seedSchema();

            CacheDatabaseAdminHttpServer.AdminHttpResponse templateResponse =
                    harness.adminHttpServer.dispatch("GET", URI.create("/api/migration-planner/template"), new byte[0]);
            assertEquals(200, templateResponse.statusCode());
            assertTrue(body(templateResponse).contains("\"defaults\""));

            CacheDatabaseAdminHttpServer.AdminHttpResponse discoveryResponse =
                    harness.adminHttpServer.dispatch("GET", URI.create("/api/migration-planner/discovery"), new byte[0]);
            assertEquals(200, discoveryResponse.statusCode());
            String discoveryJson = body(discoveryResponse).toLowerCase();
            assertTrue(discoveryJson.contains("customer_account"));
            assertTrue(discoveryJson.contains("customer_order"));

            String form = "workloadName=customer-orders"
                    + "&rootTableOrEntity=customer_account"
                    + "&rootPrimaryKeyColumn=customer_id"
                    + "&childTableOrEntity=customer_order"
                    + "&childPrimaryKeyColumn=order_id"
                    + "&relationColumn=customer_id"
                    + "&sortColumn=order_date"
                    + "&sortDirection=DESC"
                    + "&rootRowCount=2"
                    + "&childRowCount=3"
                    + "&typicalChildrenPerRoot=2"
                    + "&maxChildrenPerRoot=3"
                    + "&firstPageSize=50"
                    + "&hotWindowPerRoot=1000"
                    + "&listScreen=true"
                    + "&firstPaintNeedsFullAggregate=false"
                    + "&globalSortedScreen=false"
                    + "&thresholdOrRangeScreen=false"
                    + "&archiveHistoryRequired=true"
                    + "&fullHistoryMustStayHot=false"
                    + "&detailLookupIsHot=true"
                    + "&currentOrmUsesEagerLoading=true"
                    + "&includeRelationLoader=true"
                    + "&includeProjectionSkeleton=true"
                    + "&sideBySideComparisonRequired=true"
                    + "&warmRootRows=true"
                    + "&dryRun=false"
                    + "&warmBeforeCompare=false"
                    + "&comparisonWarmupIterations=2"
                    + "&comparisonMeasuredIterations=8"
                    + "&comparisonSampleRootCount=3"
                    + "&comparisonPageSize=100"
                    + "&basePackage=com.example.cachedb.migration"
                    + "&rootClassName=CustomerEntity"
                    + "&childClassName=OrderEntity"
                    + "&relationLoaderClassName=CustomerOrderRelationBatchLoader"
                    + "&projectionSupportClassName=CustomerOrderReadModels";

            CacheDatabaseAdminHttpServer.AdminHttpResponse planResponse = harness.adminHttpServer.dispatch(
                    "POST",
                    URI.create("/api/migration-planner/plan"),
                    form.getBytes(StandardCharsets.UTF_8)
            );
            assertEquals(200, planResponse.statusCode());
            String planJson = body(planResponse);
            assertTrue(planJson.contains("\"projectionRequired\":true"));
            assertTrue(planJson.contains("CustomerAccountCustomerOrderSummaryHot"));

            CacheDatabaseAdminHttpServer.AdminHttpResponse scaffoldResponse = harness.adminHttpServer.dispatch(
                    "POST",
                    URI.create("/api/migration-planner/scaffold"),
                    form.getBytes(StandardCharsets.UTF_8)
            );
            assertEquals(200, scaffoldResponse.statusCode());
            String scaffoldJson = body(scaffoldResponse);
            assertTrue(scaffoldJson.contains("CustomerEntity"));
            assertTrue(scaffoldJson.contains("OrderEntity"));
            assertTrue(scaffoldJson.contains("CustomerOrderRelationBatchLoader"));
        }
    }

    @Test
    void shouldRenderServerSideSelectionFallbackLinksAndPrefillForm() throws Exception {
        try (TestHarness harness = new TestHarness("planner-server-side-selection")) {
            harness.seedSchema();

            CacheDatabaseAdminHttpServer.DashboardTemplateModel page =
                    harness.adminHttpServer.renderMigrationPlannerTemplateModel(
                            "tr",
                            "/cachedb-admin/migration-planner",
                            "lang=tr&discover=true&applySuggestion=0"
                    );

            String body = page.bodyMarkup().toLowerCase();
            assertTrue(body.contains("applysuggestion=0"));
            assertTrue(body.contains("name=\"relationcolumn\""));
            assertTrue(body.contains("name=\"sortcolumn\""));
            assertTrue(body.contains("data-planner-suggestion"));
        }
    }

    private String body(CacheDatabaseAdminHttpServer.AdminHttpResponse response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static final class TestHarness implements AutoCloseable {
        private final DataSource dataSource;
        private final JedisPooled jedis;
        private final CacheDatabase cacheDatabase;
        private final CacheDatabaseAdminHttpServer adminHttpServer;

        private TestHarness(String schemaName) {
            this.dataSource = newDataSource(schemaName);
            this.jedis = new JedisPooled("redis://127.0.0.1:6379");
            this.cacheDatabase = new CacheDatabase(jedis, dataSource, CacheDatabaseConfig.defaults());
            this.adminHttpServer = cacheDatabase.adminHttpServer(AdminHttpConfig.builder()
                    .enabled(false)
                    .host("127.0.0.1")
                    .port(0)
                    .backlog(0)
                    .workerThreads(1)
                    .dashboardEnabled(true)
                    .dashboardTitle("CacheDB Admin")
                    .build());
        }

        private void seedSchema() throws SQLException {
            executeAll(List.of(
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
                    "CREATE INDEX idx_customer_order_customer_date ON customer_order (customer_id, order_date DESC)",
                    "INSERT INTO customer_account (customer_id, tax_number, customer_type) VALUES (1, 'TR1001', 'CORP')",
                    "INSERT INTO customer_account (customer_id, tax_number, customer_type) VALUES (2, 'TR1002', 'SMB')",
                    "INSERT INTO customer_order (order_id, customer_id, order_date, order_amount, currency_code, order_type) VALUES (101, 1, TIMESTAMP '2026-04-01 10:00:00', 100.00, 'TRY', 'ONLINE')",
                    "INSERT INTO customer_order (order_id, customer_id, order_date, order_amount, currency_code, order_type) VALUES (102, 1, TIMESTAMP '2026-04-02 11:00:00', 120.00, 'TRY', 'ONLINE')",
                    "INSERT INTO customer_order (order_id, customer_id, order_date, order_amount, currency_code, order_type) VALUES (201, 2, TIMESTAMP '2026-04-03 12:00:00', 90.00, 'USD', 'STORE')"
            ));
        }

        private void executeAll(List<String> statements) throws SQLException {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                }
            }
        }

        @Override
        public void close() {
            cacheDatabase.close();
        }

        private static DataSource newDataSource(String schemaName) {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:" + schemaName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }
    }
}

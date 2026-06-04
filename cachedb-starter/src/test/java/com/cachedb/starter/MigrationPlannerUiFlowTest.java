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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationPlannerUiFlowTest {

    @Test
    void shouldRenderStepperProgressAndModeAwarePlannerPage() throws Exception {
        try (TestHarness harness = new TestHarness("planner-render")) {
            CacheDatabaseAdminHttpServer.DashboardTemplateModel page =
                    harness.adminHttpServer.renderMigrationPlannerTemplateModel("tr", "/cachedb-admin/migration-planner");

            String body = page.bodyMarkup();
            String head = page.headMarkup();
            assertTrue(body.contains("planner-stepper"));
            assertTrue(body.contains("planner-command-strip"));
            assertTrue(body.contains("plannerWorkspace"));
            assertTrue(body.contains("plannerStatusMirror"));
            assertTrue(body.contains("data-planner-shortcut=\"plan\""));
            assertTrue(body.contains("handlePlannerShortcut"));
            assertTrue(Thread.currentThread().getContextClassLoader()
                    .getResource("cachedb-admin/migration-planner.css") != null);
            assertTrue(Thread.currentThread().getContextClassLoader()
                    .getResource("cachedb-admin/migration-planner.js.template") != null);
            assertTrue(Thread.currentThread().getContextClassLoader()
                    .getResource("cachedb-admin/migration-planner-page.html.template") != null);
            assertTrue(Thread.currentThread().getContextClassLoader()
                    .getResource("cachedb-admin/migration-planner-command-strip.html.template") != null);
            assertTrue(Thread.currentThread().getContextClassLoader()
                    .getResource("cachedb-admin/migration-planner-body.html.template") != null);
            assertTrue(Thread.currentThread().getContextClassLoader()
                    .getResource("cachedb-admin/migration-planner-input-panel.html.template") != null);
            assertTrue(Thread.currentThread().getContextClassLoader()
                    .getResource("cachedb-admin/migration-planner-demo-card.html.template") != null);
            assertTrue(Thread.currentThread().getContextClassLoader()
                    .getResource("cachedb-admin/migration-planner-discovery-card.html.template") != null);
            assertTrue(Thread.currentThread().getContextClassLoader()
                    .getResource("cachedb-admin/migration-planner-route-form.html.template") != null);
            assertTrue(Thread.currentThread().getContextClassLoader()
                    .getResource("cachedb-admin/migration-planner-workspace.html.template") != null);
            assertTrue(Thread.currentThread().getContextClassLoader()
                    .getResource("cachedb-admin/migration-planner-results-card.html.template") != null);
            assertTrue(Thread.currentThread().getContextClassLoader()
                    .getResource("cachedb-admin/migration-planner-warm-card.html.template") != null);
            assertTrue(Thread.currentThread().getContextClassLoader()
                    .getResource("cachedb-admin/migration-planner-scaffold-card.html.template") != null);
            assertTrue(Thread.currentThread().getContextClassLoader()
                    .getResource("cachedb-admin/migration-planner-compare-card.html.template") != null);
            assertTrue(body.contains("plannerProgressFill"));
            assertTrue(body.contains("data-planner-mode=\"beginner\""));
            assertTrue(body.contains("data-planner-mode=\"advanced\""));
            assertTrue(body.contains("const apiBase = \"/cachedb-admin\";"));
            assertTrue(body.contains("/api/migration-planner/discovery"));
            assertTrue(body.contains("/api/migration-planner/warm"));
            assertTrue(body.contains("/api/migration-planner/warm/start"));
            assertTrue(body.contains("/api/migration-planner/warm/status"));
            assertTrue(body.contains("/api/migration-planner/compare"));
            assertTrue(body.contains("/api/migration-planner/compare/start"));
            assertTrue(body.contains("/api/migration-planner/compare/status"));
            assertTrue(body.contains("plannerCompareReportPanel"));
            assertTrue(body.contains("plannerCompareReportPreview"));
            assertTrue(body.contains("plannerCompareCopyReportAction"));
            assertTrue(body.contains("plannerMemoryEstimateCard"));
            assertTrue(body.contains("plannerMemoryRows"));
            assertTrue(body.contains("function renderComparisonReportPreview"));
            assertTrue(body.contains("function renderMemoryEstimate"));
            assertTrue(body.contains("function copyComparisonReport"));
            assertTrue(body.contains("function ensureRegisteredSurfacesForExecution"));
            assertTrue(body.contains("function canonicalizeDiscoveredSurface"));
            assertTrue(body.contains("orders gibi manuel placeholder değerler warm çalıştırmaz"));
            assertTrue(body.contains("navigateToPlanFallback"));
            assertTrue(body.contains("plannerGenerateAction"));
            assertTrue(body.contains("id=\"plannerGenerateAction\" type=\"submit\""));
            assertTrue(body.contains("__cachedbPlannerGenerate"));
            assertTrue(body.contains("Warm execution arka planda başlatılıyor"));
            assertTrue(body.contains("field.checked = toBooleanValue(value);"));
            assertTrue(body.contains("field.name === \"generatePlan\""));
            assertTrue(body.contains("function compactSurfaceLabel"));
            assertTrue(body.contains("compactIdentifier(current)"));
            assertTrue(head.contains(".planner-form-section .planner-form-grid"));
            assertTrue(head.contains("overflow-wrap: anywhere"));
            assertTrue(head.contains("white-space: pre-wrap"));
            assertFalse(body.contains("__CACHEDB_HTML_"));
            assertFalse(body.contains("__CACHEDB_MIGRATION_PLANNER_TOKEN_"));
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
            assertTrue(planJson.contains("\"redisMemoryEstimate\""));
            assertTrue(planJson.contains("\"recommendedMaxmemoryBytes\""));

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
            assertTrue(body.contains("planner-suggestion-row"));
            assertTrue(body.contains("applysuggestion=1"));
            assertFalse(body.contains("warmexecution=true&dryrunexecution=true&applysuggestion=0"));
        }
    }

    @Test
    void shouldRenderServerSideDemoBootstrapFallbackAndPrefillPlanner() throws Exception {
        try (TestHarness harness = new TestHarness("planner-demo-fallback")) {
            CacheDatabaseAdminHttpServer.DashboardTemplateModel page =
                    harness.adminHttpServer.renderMigrationPlannerTemplateModel(
                            "tr",
                            "/cachedb-admin/migration-planner",
                            "lang=tr&demoBootstrap=true&discover=true&demoCustomerCount=24&demoHotCustomerCount=4&demoMaxOrdersPerCustomer=300"
                    );

            String body = page.bodyMarkup().toLowerCase();
            assertTrue(body.contains("plannerdemobootstrapfallbackform"));
            assertTrue(body.contains("name=\"demobootstrap\" value=\"true\""));
            assertTrue(body.contains("plannerdemostatus"));
            assertTrue(body.contains("name=\"democustomercount\""));
            assertTrue(body.contains("value=\"24\""));
            assertTrue(body.contains("value=\"300\""));
        }
    }

    @Test
    void shouldRenderServerSidePlanFallbackWhenGeneratePlanIsRequested() throws Exception {
        try (TestHarness harness = new TestHarness("planner-plan-fallback")) {
            harness.seedSchema();

            CacheDatabaseAdminHttpServer.DashboardTemplateModel page =
                    harness.adminHttpServer.renderMigrationPlannerTemplateModel(
                            "tr",
                            "/cachedb-admin/migration-planner",
                            "lang=tr&discover=true&rootTableOrEntity=customer_account&rootPrimaryKeyColumn=customer_id"
                                    + "&childTableOrEntity=customer_order&childPrimaryKeyColumn=order_id"
                                    + "&relationColumn=customer_id&sortColumn=order_date&sortDirection=DESC"
                                    + "&rootRowCount=2&childRowCount=3&typicalChildrenPerRoot=2&maxChildrenPerRoot=3"
                                    + "&firstPageSize=50&hotWindowPerRoot=1000&listScreen=true"
                                    + "&firstPaintNeedsFullAggregate=false&globalSortedScreen=false"
                                    + "&thresholdOrRangeScreen=false&archiveHistoryRequired=true"
                                    + "&fullHistoryMustStayHot=false&detailLookupIsHot=true"
                                    + "&currentOrmUsesEagerLoading=true&sideBySideComparisonRequired=true"
                                    + "&generatePlan=true"
                    );

            String body = page.bodyMarkup();
            assertTrue(body.contains("name=\"generatePlan\" value=\"true\""));
            assertTrue(body.contains("const bootstrapPlanResult = {"));
            assertTrue(body.contains("Plan hazır. İstersen hemen staging warm çalıştırabilirsin."));
            assertTrue(body.contains("planner-empty d-none"));
            assertTrue(body.contains("id=\"plannerResults\" class=\"\""));
            assertTrue(body.contains("id=\"plannerSurface\" class=\"result-metric-value\">Use GeneratedCacheModule for normal CRUD and a ProjectionRepository for the hot list screen."));
            assertTrue(body.contains("id=\"plannerMemoryEstimateCard\" class=\"result-card\""));
            assertTrue(body.contains("Redis bellek tahmini"));
            assertFalse(body.contains("plannerServerPlanFallback"));
        }
    }

    @Test
    void shouldRenderServerSideWarmResultWhenDryRunIsRequested() throws Exception {
        try (TestHarness harness = new TestHarness("planner-warm-fallback")) {
            harness.seedSchema();

            CacheDatabaseAdminHttpServer.DashboardTemplateModel page =
                    harness.adminHttpServer.renderMigrationPlannerTemplateModel(
                            "tr",
                            "/cachedb-admin/migration-planner",
                            "lang=tr&discover=true&rootTableOrEntity=customer_account&rootPrimaryKeyColumn=customer_id"
                                    + "&childTableOrEntity=customer_order&childPrimaryKeyColumn=order_id"
                                    + "&relationColumn=customer_id&sortColumn=order_date&sortDirection=DESC"
                                    + "&rootRowCount=2&childRowCount=3&typicalChildrenPerRoot=2&maxChildrenPerRoot=3"
                                    + "&firstPageSize=50&hotWindowPerRoot=1000&listScreen=true"
                                    + "&firstPaintNeedsFullAggregate=false&globalSortedScreen=false"
                                    + "&thresholdOrRangeScreen=false&archiveHistoryRequired=true"
                                    + "&fullHistoryMustStayHot=false&detailLookupIsHot=true"
                                    + "&currentOrmUsesEagerLoading=true&sideBySideComparisonRequired=true"
                                    + "&generatePlan=true&dryRunExecution=true"
                        );

            String body = page.bodyMarkup();
            assertTrue(body.contains("id=\"plannerWarmPreviewAction\" type=\"submit\""));
            assertTrue(body.contains("name=\"dryRunExecution\" value=\"true\""));
            assertTrue(body.contains("form=\"plannerForm\""));
            assertTrue(body.contains("warmExecution(false)"));
            assertTrue(body.contains("warmExecution(true)"));
            assertTrue(body.contains("runWarmSyncFallback"));
            assertTrue(body.contains("pollWarmJob"));
            assertTrue(body.contains("pollCompareJob"));
            assertTrue(body.contains("runComparisonSyncFallback"));
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
            this.jedis = new JedisPooled(resolveRedisUri());
            this.cacheDatabase = new CacheDatabase(jedis, dataSource, CacheDatabaseConfig.defaults());
            this.cacheDatabase.admin().configureMigrationPlannerDemo(new TestMigrationPlannerDemoSupport());
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

        private static String resolveRedisUri() {
            String configured = System.getProperty("cachedb.test.redisUri");
            if (configured == null || configured.isBlank()) {
                configured = System.getenv("CACHEDB_TEST_REDIS_URI");
            }
            return configured == null || configured.isBlank()
                    ? "redis://default:welcome1@127.0.0.1:6379"
                    : configured;
        }
    }

    private static final class TestMigrationPlannerDemoSupport implements MigrationPlannerDemoSupport {

        @Override
        public Descriptor descriptor() {
            return new Descriptor(
                    true,
                    "Demo migration planner",
                    "Demo schema hazır.",
                    120,
                    12,
                    1500,
                    plannerDefaults()
            );
        }

        @Override
        public BootstrapResult bootstrap(BootstrapRequest request) {
            MigrationPlanner.Request defaults = plannerDefaults();
            return new BootstrapResult(
                    "MigrationDemoCustomerEntity",
                    "MigrationDemoOrderEntity",
                    "public.migration_demo_customer",
                    "public.migration_demo_order",
                    List.of("public.migration_demo_customer_order_timeline_v"),
                    request.customerCount(),
                    (long) request.customerCount() * Math.max(1, request.hotCustomerCount()),
                    Math.max(1, request.maxOrdersPerCustomer()),
                    List.of("1001", "1002"),
                    List.of("Demo schema seeded", "Discovery is ready"),
                    defaults
            );
        }

        private MigrationPlanner.Request plannerDefaults() {
            return new MigrationPlanner.Request(
                    "migration-demo-timeline",
                    "MigrationDemoCustomerEntity",
                    "customer_id",
                    "MigrationDemoOrderEntity",
                    "order_id",
                    "customer_id",
                    "order_date",
                    "DESC",
                    24,
                    7200,
                    120,
                    300,
                    100,
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
            );
        }
    }
}

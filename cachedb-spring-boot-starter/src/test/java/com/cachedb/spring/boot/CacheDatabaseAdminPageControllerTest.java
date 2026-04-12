package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.config.AdminHttpConfig;
import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheDatabaseAdminHttpServer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ConcurrentModel;
import redis.clients.jedis.JedisPooled;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheDatabaseAdminPageControllerTest {

    @Test
    void shouldRedirectDashboardAndPlannerToVersionedUrlsWhenVersionMissing() {
        try (TestHarness harness = new TestHarness("page-controller-version")) {
            CacheDatabaseAdminPageController controller = harness.controller();

            MockHttpServletRequest plannerRequest = new MockHttpServletRequest("GET", "/cachedb-admin/migration-planner");
            plannerRequest.setParameter("lang", "tr");
            String plannerView = controller.migrationPlanner(plannerRequest, new MockHttpServletResponse(), new ConcurrentModel());
            assertEquals("redirect:/cachedb-admin/migration-planner?lang=tr&v=" + harness.adminHttpServer().dashboardInstanceId(), plannerView);

            MockHttpServletRequest dashboardRequest = new MockHttpServletRequest("GET", "/cachedb-admin");
            dashboardRequest.setParameter("lang", "tr");
            String dashboardView = controller.dashboardRoot(dashboardRequest, new MockHttpServletResponse(), new ConcurrentModel());
            assertEquals("redirect:/cachedb-admin?lang=tr&v=" + harness.adminHttpServer().dashboardInstanceId(), dashboardView);
        }
    }

    @Test
    void shouldRenderVersionedPlannerPageWithNoCacheHeaders() {
        try (TestHarness harness = new TestHarness("page-controller-render")) {
            CacheDatabaseAdminPageController controller = harness.controller();

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/cachedb-admin/migration-planner");
            request.setParameter("lang", "tr");
            request.setParameter("v", harness.adminHttpServer().dashboardInstanceId());
            MockHttpServletResponse response = new MockHttpServletResponse();
            ConcurrentModel model = new ConcurrentModel();

            String view = controller.migrationPlanner(request, response, model);

            assertEquals("cachedb-admin/dashboard", view);
            assertEquals("no-store, no-cache, must-revalidate, max-age=0", response.getHeader("Cache-Control"));
            assertEquals("no-cache", response.getHeader("Pragma"));
            assertTrue(String.valueOf(model.getAttribute("bodyMarkup")).contains("plannerDiscoverAction"));
        }
    }

    @Test
    void shouldRenderServerSideDiscoveryFallbackWhenRequested() throws Exception {
        try (TestHarness harness = new TestHarness("page-controller-discovery")) {
            harness.seedSchema();
            CacheDatabaseAdminPageController controller = harness.controller();

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/cachedb-admin/migration-planner");
            request.setParameter("lang", "tr");
            request.setParameter("discover", "true");
            request.setParameter("v", harness.adminHttpServer().dashboardInstanceId());
            MockHttpServletResponse response = new MockHttpServletResponse();
            ConcurrentModel model = new ConcurrentModel();

            String view = controller.migrationPlanner(request, response, model);

            assertEquals("cachedb-admin/dashboard", view);
            String body = String.valueOf(model.getAttribute("bodyMarkup")).toLowerCase();
            assertTrue(body.contains("customer_account"));
            assertTrue(body.contains("customer_order"));
            assertTrue(body.contains("plannerdiscoveryfallbackform"));
            assertTrue(body.contains("data-planner-suggestion"));
            assertTrue(body.contains("applysuggestion=0"));
        }
    }

    @Test
    void shouldApplyServerSideDiscoverySelectionToPlannerForm() throws Exception {
        try (TestHarness harness = new TestHarness("page-controller-selection")) {
            harness.seedSchema();
            CacheDatabaseAdminPageController controller = harness.controller();

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/cachedb-admin/migration-planner");
            request.setParameter("lang", "tr");
            request.setParameter("discover", "true");
            request.setParameter("selectObject", "0");
            request.setParameter("plannerRole", "root");
            request.setParameter("childTableOrEntity", "customer_order");
            request.setParameter("v", harness.adminHttpServer().dashboardInstanceId());
            MockHttpServletResponse response = new MockHttpServletResponse();
            ConcurrentModel model = new ConcurrentModel();

            String view = controller.migrationPlanner(request, response, model);

            assertEquals("cachedb-admin/dashboard", view);
            String body = String.valueOf(model.getAttribute("bodyMarkup")).toLowerCase();
            assertTrue(body.contains("name=\"roottableorentity\""));
            assertTrue(body.contains("selected>public.customer_account</option>") || body.contains("selected>customer_account</option>"));
            assertTrue(body.contains("name=\"rootprimarykeycolumn\""));
            assertTrue(body.contains("customer_id"));
        }
    }

    private static final class TestHarness implements AutoCloseable {
        private final CacheDatabase cacheDatabase;
        private final CacheDatabaseAdminHttpServer adminHttpServer;
        private final CacheDatabaseAdminPageController controller;
        private final DataSource dataSource;

        private TestHarness(String schemaName) {
            this.dataSource = newDataSource(schemaName);
            JedisPooled jedis = new JedisPooled("redis://127.0.0.1:6379");
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
            this.controller = new CacheDatabaseAdminPageController(adminHttpServer, new CacheDbSpringProperties());
        }

        private CacheDatabaseAdminPageController controller() {
            return controller;
        }

        private CacheDatabaseAdminHttpServer adminHttpServer() {
            return adminHttpServer;
        }

        private void seedSchema() throws Exception {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE customer_account (
                            customer_id BIGINT PRIMARY KEY,
                            tax_number VARCHAR(32) NOT NULL,
                            customer_type VARCHAR(24) NOT NULL
                        )
                        """);
                statement.execute("""
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
                        """);
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

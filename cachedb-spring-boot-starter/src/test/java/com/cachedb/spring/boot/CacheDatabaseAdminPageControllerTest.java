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

    private static final class TestHarness implements AutoCloseable {
        private final CacheDatabase cacheDatabase;
        private final CacheDatabaseAdminHttpServer adminHttpServer;
        private final CacheDatabaseAdminPageController controller;

        private TestHarness(String schemaName) {
            DataSource dataSource = newDataSource(schemaName);
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

package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.AdminHttpConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CacheDatabaseAdminHttpServerTest {

    @Test
    void shouldResolveAdminBasePathForDashboardRoutes() {
        assertEquals("/cachedb-admin", CacheDatabaseAdminHttpServer.resolveDashboardBasePath("/cachedb-admin"));
        assertEquals("/cachedb-admin", CacheDatabaseAdminHttpServer.resolveDashboardBasePath("/cachedb-admin/dashboard"));
        assertEquals("/cachedb-admin", CacheDatabaseAdminHttpServer.resolveDashboardBasePath("/cachedb-admin/dashboard-v3"));
    }

    @Test
    void shouldResolveAdminBasePathForMigrationPlannerRoutes() {
        assertEquals("/cachedb-admin", CacheDatabaseAdminHttpServer.resolveDashboardBasePath("/cachedb-admin/migration-planner"));
        assertEquals("", CacheDatabaseAdminHttpServer.resolveDashboardBasePath("/migration-planner"));
    }

    @Test
    void shouldFailClosedWhenAdminAuthIsEnabledWithoutToken() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AdminHttpConfig.builder().authEnabled(true).build()
        );
    }

    @Test
    void shouldNormalizeAdminAuthDefaults() {
        AdminHttpConfig config = AdminHttpConfig.builder()
                .host(" ")
                .dashboardTitle(" ")
                .authHeaderName(" ")
                .build();

        assertEquals("127.0.0.1", config.host());
        assertEquals("CacheDB Admin", config.dashboardTitle());
        assertEquals("Authorization", config.authHeaderName());
    }
}

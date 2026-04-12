package com.reactor.cachedb.starter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}

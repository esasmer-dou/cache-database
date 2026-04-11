package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.config.CacheDatabaseConfig;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheDatabaseBootstrapTest {

    @Test
    void shouldPreviewSelectedProfileBeforeBuilding() {
        CacheDatabaseConfig config = CacheDatabaseBootstrap
                .using(noOpDataSource())
                .production()
                .previewConfig();

        assertTrue(config.writeBehind().enabled());
        assertTrue(config.redisGuardrail().enabled());
        assertFalse(config.adminHttp().enabled());
        assertEquals("VALIDATE_ONLY", config.schemaBootstrap().mode().name());
    }

    @Test
    void shouldApplyCustomizationsOnTopOfSelectedProfile() {
        CacheDatabaseConfig config = CacheDatabaseBootstrap
                .using(noOpDataSource())
                .minimalOverhead()
                .keyPrefix("app-cache")
                .previewConfig();

        assertFalse(config.adminMonitoring().enabled());
        assertFalse(config.adminHttp().enabled());
        assertEquals("app-cache", config.keyspace().keyPrefix());
    }

    private static DataSource noOpDataSource() {
        return new javax.sql.DataSource() {
            @Override
            public java.sql.Connection getConnection() {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.sql.Connection getConnection(String username, String password) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T unwrap(Class<T> iface) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }

            @Override
            public java.io.PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(java.io.PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getGlobal();
            }
        };
    }
}

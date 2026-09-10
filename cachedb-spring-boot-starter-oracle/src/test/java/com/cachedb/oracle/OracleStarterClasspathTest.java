package com.reactor.cachedb.oracle;

import com.reactor.cachedb.jdbc.JdbcStorageProviders;
import com.reactor.cachedb.spring.boot.oracle.CacheDbOracleStarter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OracleStarterClasspathTest {
    @Test
    void shouldProvideExactlyOneOracleProviderAndJdbcDriver() throws Exception {
        ClassLoader classLoader = getClass().getClassLoader();

        assertEquals("oracle", JdbcStorageProviders.requireSingle(classLoader).id());
        assertEquals("oracle", CacheDbOracleStarter.PROVIDER_ID);
        assertEquals("cachedb-spring-boot-starter-oracle", CacheDbOracleStarter.ARTIFACT_ID);
        assertNotNull(Class.forName("oracle.jdbc.OracleDriver", true, classLoader));
    }
}

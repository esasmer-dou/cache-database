package com.reactor.cachedb.jdbc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcQueryDialectsTest {
    @Test
    void shouldKeepH2AsExplicitTestDialect() throws Exception {
        try (URLClassLoader noProviders = new URLClassLoader(new URL[0], null)) {
            assertEquals("h2", JdbcQueryDialects.resolve(connection("H2"), noProviders).name());
        }
    }

    @Test
    void shouldFailFastForUnknownDatabaseProduct() throws Exception {
        try (URLClassLoader noProviders = new URLClassLoader(new URL[0], null)) {
            assertThrows(
                    CacheDbDatabaseProductUnsupportedException.class,
                    () -> JdbcQueryDialects.resolve(connection("UnknownDB"), noProviders)
            );
        }
    }

    private Connection connection(String productName) {
        DatabaseMetaData metadata = (DatabaseMetaData) Proxy.newProxyInstance(
                DatabaseMetaData.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> method.getName().equals("getDatabaseProductName") ? productName : null
        );
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> method.getName().equals("getMetaData") ? metadata : null
        );
    }
}

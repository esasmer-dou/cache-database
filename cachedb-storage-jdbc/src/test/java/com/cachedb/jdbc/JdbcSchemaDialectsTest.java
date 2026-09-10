package com.reactor.cachedb.jdbc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcSchemaDialectsTest {
    @Test
    void shouldMapProviderSpecificTypesAndDdl() {
        JdbcSchemaDialect postgres = JdbcSchemaDialects.postgres();
        JdbcSchemaDialect mssql = JdbcSchemaDialects.mssql();
        JdbcSchemaDialect oracle = JdbcSchemaDialects.oracle();

        assertEquals("TEXT", postgres.sqlType(String.class.getName()));
        assertEquals("BOOLEAN", postgres.sqlType(Boolean.class.getName()));
        assertEquals("NVARCHAR(MAX)", mssql.sqlType(String.class.getName()));
        assertEquals("BIT", mssql.sqlType(Boolean.class.getName()));
        assertEquals("VARCHAR2(4000 CHAR)", oracle.sqlType(String.class.getName()));
        assertEquals("NUMBER(1)", oracle.sqlType(Boolean.class.getName()));
        assertEquals("NUMBER(38, 0)", oracle.sqlType(BigInteger.class.getName()));
        assertEquals("entity_version NUMBER(19) DEFAULT 0 NOT NULL",
                oracle.versionColumnDefinition("entity_version"));
        assertEquals("ALTER TABLE orders ADD status VARCHAR2(4000 CHAR)",
                oracle.addColumnSql("orders", oracle.columnDefinition("status", String.class.getName(), false)));
    }

    @Test
    void shouldKeepH2AsExplicitTestDialect() throws Exception {
        try (URLClassLoader noProviders = new URLClassLoader(new URL[0], null)) {
            assertEquals("h2", JdbcSchemaDialects.resolve(connection("H2"), noProviders).name());
        }
    }

    @Test
    void shouldFailFastForUnknownDatabaseProduct() throws Exception {
        try (URLClassLoader noProviders = new URLClassLoader(new URL[0], null)) {
            assertThrows(
                    CacheDbDatabaseProductUnsupportedException.class,
                    () -> JdbcSchemaDialects.resolve(connection("UnknownDB"), noProviders)
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

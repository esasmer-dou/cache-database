package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JdbcColumnLimitsTest {
    @Test void defaultsFollowProviderFamilies() throws Exception {
        for (JdbcSchemaDialect dialect : List.of(JdbcSchemaDialects.oracle(), JdbcSchemaDialects.mssql(),
                JdbcSchemaDialects.postgres(), JdbcSchemaDialects.ansi("test"))) {
            int expected = switch (dialect.name()) { case "oracle" -> 1000; case "postgres" -> 1600; default -> 1024; };
            assertEquals(expected, dialect.maxColumnsInTable());
            assertEquals(expected, dialect.resolveMaxColumnsInTable(null));
            for (int unknown : new int[]{0, -1}) assertEquals(expected, dialect.resolveMaxColumnsInTable(metadata(unknown, null, new AtomicInteger())));
        }
    }

    @Test void dictionaryIsBoundedAndReadOnlyOnceWithoutCatalogQueries() throws Exception {
        for (int reported : new int[]{500, 1000, 1024, 1600, 4096, 32767}) {
            AtomicInteger calls = new AtomicInteger();
            assertEquals(Math.min(reported, 4096), JdbcSchemaDialects.oracle().resolveMaxColumnsInTable(metadata(reported, null, calls)));
            assertEquals(1, calls.get());
        }
    }

    @Test void unsupportedCapabilitiesFallbackButConnectionFailuresPropagate() throws Exception {
        assertEquals(1000, JdbcSchemaDialects.oracle().resolveMaxColumnsInTable(metadata(0,
                new SQLFeatureNotSupportedException(), new AtomicInteger())));
        SQLException disconnected = new SQLException("connection lost", "08006");
        assertSame(disconnected, assertThrows(SQLException.class, () -> JdbcColumnLimits.resolve(metadata(0, disconnected, new AtomicInteger()), 1000)));
        assertThrows(IllegalArgumentException.class, () -> JdbcColumnLimits.resolve(null, 4097));
    }

    @Test void ddlAllowsOnlyTheDeclaredOrResolvedBudget() {
        var oracle = JdbcSchemaDialects.oracle();
        assertThrows(IllegalArgumentException.class, () -> oracle.createTableSql("wide", Collections.nCopies(1001, "c INT"), "id"));
        assertTrue(oracle.createTableSql("wide", Collections.nCopies(4096, "c INT"), "id", 4096).startsWith("CREATE TABLE"));
        assertThrows(IllegalArgumentException.class, () -> oracle.createTableSql("wide", Collections.nCopies(4097, "c INT"), "id", 32767));
        var pg = JdbcSchemaDialects.postgres();
        assertTrue(pg.createTableSql("wide", Collections.nCopies(1600, "c INT"), "id").startsWith("CREATE TABLE"));
        assertThrows(IllegalArgumentException.class, () -> pg.createTableSql("wide", Collections.nCopies(1601, "c INT"), "id"));
    }

    @Test void connectedDdlRetainsExistingCustomDialectOverrides() {
        JdbcSchemaDialect custom = new JdbcSchemaDialect() {
            public String name() { return "custom"; }
            public String sqlType(String javaTypeName) { return "TEXT"; }
            public String createTableSql(String table, List<String> columns, String id) { return "CUSTOM DDL"; }
        };
        assertEquals("CUSTOM DDL", custom.createTableSql("t", List.of("id INT"), "id", 1024));
    }

    private DatabaseMetaData metadata(int limit, SQLException failure, AtomicInteger calls) {
        return (DatabaseMetaData) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, arguments) -> {
                    if (!method.getName().equals("getMaxColumnsInTable")) throw new AssertionError("Unexpected metadata call: " + method.getName());
                    calls.incrementAndGet();
                    if (failure != null) throw failure;
                    return limit;
                });
    }
}

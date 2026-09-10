package com.reactor.cachedb.oracle;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleQueryDialectTest {
    private final OracleQueryDialect dialect = new OracleQueryDialect();

    @Test
    void shouldExposeOraclePagingAndSafeInWindow() {
        assertTrue(dialect.supportsDatabaseProduct("Oracle Database 23ai Free"));
        assertEquals(900, dialect.maxInListExpressions());
        assertEquals(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", dialect.offsetFetchClause());
    }

    @Test
    void shouldBindBooleanAndInstantWithoutDriverSpecificAllocation() throws Exception {
        List<Object> values = new ArrayList<>();
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setInt") || method.getName().equals("setObject")) {
                        values.add(args[1]);
                    }
                    return null;
                }
        );

        dialect.bindParameter(statement, 1, true);
        dialect.bindParameter(statement, 2, Instant.parse("2026-08-23T00:00:00Z"));

        assertEquals(1, values.get(0));
        assertInstanceOf(OffsetDateTime.class, values.get(1));
    }
}

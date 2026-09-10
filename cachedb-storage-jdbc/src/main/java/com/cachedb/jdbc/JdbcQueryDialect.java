package com.reactor.cachedb.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Provider contribution for bounded JDBC reads and parameter binding. */
public interface JdbcQueryDialect {
    String name();

    boolean supportsDatabaseProduct(String databaseProductName);

    int maxInListExpressions();

    default String offsetFetchClause() {
        return " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
    }

    default String limitTail(int limit) {
        return "OFFSET 0 ROWS FETCH NEXT " + Math.max(1, limit) + " ROWS ONLY";
    }

    default String parameterizedLimitTail() {
        return "OFFSET 0 ROWS FETCH NEXT :page_size ROWS ONLY";
    }

    default void bindParameter(PreparedStatement statement, int parameterIndex, Object value) throws SQLException {
        statement.setObject(parameterIndex, value);
    }
}

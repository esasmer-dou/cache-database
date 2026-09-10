package com.reactor.cachedb.oracle;

import com.reactor.cachedb.jdbc.JdbcQueryDialect;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

public final class OracleQueryDialect implements JdbcQueryDialect {
    public static final int SAFE_IN_LIST_EXPRESSIONS = 900;

    @Override
    public String name() {
        return "oracle";
    }

    @Override
    public boolean supportsDatabaseProduct(String databaseProductName) {
        String normalized = databaseProductName == null ? "" : databaseProductName.toLowerCase(Locale.ROOT);
        return normalized.contains("oracle");
    }

    @Override
    public int maxInListExpressions() {
        return SAFE_IN_LIST_EXPRESSIONS;
    }

    @Override
    public void bindParameter(PreparedStatement statement, int parameterIndex, Object value) throws SQLException {
        if (value instanceof Boolean booleanValue) {
            statement.setInt(parameterIndex, booleanValue ? 1 : 0);
            return;
        }
        if (value instanceof Instant instant) {
            statement.setObject(parameterIndex, OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
            return;
        }
        statement.setObject(parameterIndex, value);
    }
}

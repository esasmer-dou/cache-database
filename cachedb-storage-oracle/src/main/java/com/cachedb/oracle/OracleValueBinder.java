package com.reactor.cachedb.oracle;

import com.reactor.cachedb.jdbc.JdbcWriteBehindSupport;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

final class OracleValueBinder {
    private OracleValueBinder() {
    }

    static void bind(
            PreparedStatement statement,
            int parameterIndex,
            String encodedValue,
            String javaTypeName,
            OracleWriteBehindOptions.EmptyStringPolicy emptyStringPolicy
    ) throws SQLException {
        if (encodedValue != null && encodedValue.isEmpty() && "java.lang.String".equals(javaTypeName)) {
            if (emptyStringPolicy == OracleWriteBehindOptions.EmptyStringPolicy.REJECT) {
                throw new SQLException(
                        "Oracle converts an empty character value to NULL; configure NORMALIZE_TO_NULL explicitly or avoid empty strings",
                        "CDB03"
                );
            }
            statement.setObject(parameterIndex, null);
            return;
        }
        Object value = JdbcWriteBehindSupport.convertValue(encodedValue, javaTypeName);
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

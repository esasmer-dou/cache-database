package com.reactor.cachedb.starter;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

enum MigrationSqlDialect {
    POSTGRES,
    MSSQL,
    ORACLE,
    H2;

    static MigrationSqlDialect from(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String productName = metadata.getDatabaseProductName();
        String normalized = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
        if (normalized.contains("microsoft") || normalized.contains("sql server")) {
            return MSSQL;
        }
        if (normalized.contains("postgresql")) {
            return POSTGRES;
        }
        if (normalized.contains("oracle")) {
            return ORACLE;
        }
        if (normalized.contains("h2")) {
            return H2;
        }
        throw new SQLException("Unsupported migration database product: " + productName);
    }

    String limitTail(int limit) {
        int safeLimit = Math.max(1, limit);
        return switch (this) {
            case POSTGRES, H2 -> "LIMIT " + safeLimit;
            case MSSQL, ORACLE -> "OFFSET 0 ROWS FETCH NEXT " + safeLimit + " ROWS ONLY";
        };
    }

    String parameterizedLimitTail() {
        return switch (this) {
            case POSTGRES, H2 -> "LIMIT :page_size";
            case MSSQL, ORACLE -> "OFFSET 0 ROWS FETCH NEXT :page_size ROWS ONLY";
        };
    }

    String sampleRootLimitTail(int limit) {
        return limitTail(limit);
    }
}

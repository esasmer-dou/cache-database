package com.reactor.cachedb.starter;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

enum MigrationSqlDialect {
    POSTGRES,
    MSSQL;

    static MigrationSqlDialect from(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String productName = metadata.getDatabaseProductName();
        String normalized = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
        if (normalized.contains("microsoft") || normalized.contains("sql server")) {
            return MSSQL;
        }
        return POSTGRES;
    }

    String limitTail(int limit) {
        int safeLimit = Math.max(1, limit);
        return switch (this) {
            case POSTGRES -> "LIMIT " + safeLimit;
            case MSSQL -> "OFFSET 0 ROWS FETCH NEXT " + safeLimit + " ROWS ONLY";
        };
    }

    String parameterizedLimitTail() {
        return switch (this) {
            case POSTGRES -> "LIMIT :page_size";
            case MSSQL -> "OFFSET 0 ROWS FETCH NEXT :page_size ROWS ONLY";
        };
    }

    String sampleRootLimitTail(int limit) {
        return limitTail(limit);
    }
}

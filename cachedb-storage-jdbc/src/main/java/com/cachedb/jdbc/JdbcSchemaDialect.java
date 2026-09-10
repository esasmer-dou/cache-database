package com.reactor.cachedb.jdbc;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;

/** Provider contribution for schema bootstrap and migration DDL. */
public interface JdbcSchemaDialect {
    String name();

    String sqlType(String javaTypeName);

    default String columnDefinition(String columnName, String javaTypeName, boolean required) {
        return columnName + " " + sqlType(javaTypeName) + (required ? " NOT NULL" : "");
    }

    default String versionColumnDefinition(String columnName) {
        return columnName + " " + sqlType(Long.class.getName()) + " DEFAULT 0 NOT NULL";
    }

    default String createTableSql(String tableName, List<String> columnDefinitions, String idColumn) {
        return "CREATE TABLE " + tableName + " ("
                + String.join(", ", columnDefinitions)
                + ", PRIMARY KEY (" + idColumn + "))";
    }

    default String addColumnSql(String tableName, String columnDefinition) {
        return "ALTER TABLE " + tableName + " ADD " + columnDefinition;
    }

    default String metadataIdentifier(DatabaseMetaData metaData, String identifier) throws SQLException {
        if (identifier == null || identifier.isBlank()) {
            return identifier;
        }
        if (metaData.storesUpperCaseIdentifiers()) {
            return identifier.toUpperCase(java.util.Locale.ROOT);
        }
        if (metaData.storesLowerCaseIdentifiers()) {
            return identifier.toLowerCase(java.util.Locale.ROOT);
        }
        return identifier;
    }
}

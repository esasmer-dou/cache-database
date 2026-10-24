package com.reactor.cachedb.jdbc;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;

/** Provider contribution for schema bootstrap and migration DDL. */
public interface JdbcSchemaDialect {
    String name();

    default int maxColumnsInTable() { return 1_024; }

    default int resolveMaxColumnsInTable(DatabaseMetaData metaData) throws SQLException {
        return JdbcColumnLimits.resolve(metaData, maxColumnsInTable());
    }

    String sqlType(String javaTypeName);

    default String columnDefinition(String columnName, String javaTypeName, boolean required) {
        return columnName + " " + sqlType(javaTypeName) + (required ? " NOT NULL" : "");
    }

    default String versionColumnDefinition(String columnName) {
        return columnName + " " + sqlType(Long.class.getName()) + " DEFAULT 0 NOT NULL";
    }

    default String createTableSql(String tableName, List<String> columnDefinitions, String idColumn) {
        JdbcColumnLimits.validate(columnDefinitions.size(), maxColumnsInTable(), name());
        return "CREATE TABLE " + tableName + " ("
                + String.join(", ", columnDefinitions)
                + ", PRIMARY KEY (" + idColumn + "))";
    }

    default String createTableSql(String tableName, List<String> columnDefinitions, String idColumn, int maxColumns) {
        JdbcColumnLimits.validate(columnDefinitions.size(), maxColumns, name());
        return createTableSql(tableName, columnDefinitions, idColumn);
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

    default String metadataPattern(DatabaseMetaData metaData, String identifier) throws SQLException {
        String normalized = metadataIdentifier(metaData, identifier);
        String escape = metaData.getSearchStringEscape();
        if (normalized == null || escape == null || escape.isEmpty()) return normalized;
        return normalized.replace(escape, escape + escape)
                .replace("_", escape + "_").replace("%", escape + "%");
    }
}

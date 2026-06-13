package com.reactor.cachedb.mssql;

import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.jdbc.JdbcDatabaseDialect;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public final class MssqlDatabaseDialect implements JdbcDatabaseDialect {
    public static final int MSSQL_PARAMETER_LIMIT = 2_100;

    @Override
    public String name() {
        return "mssql";
    }

    @Override
    public int maxParametersPerStatement() {
        return MSSQL_PARAMETER_LIMIT;
    }

    @Override
    public boolean supportsMultiRowUpsert() {
        return false;
    }

    @Override
    public String deleteSql(QueuedWriteOperation operation) {
        return "DELETE FROM " + operation.tableName()
                + " WHERE " + operation.idColumn() + " = ?"
                + " AND (" + operation.versionColumn() + " IS NULL OR " + operation.versionColumn() + " <= ?)";
    }

    @Override
    public String deleteMultiRowSql(QueuedWriteOperation operation, int rowCount) {
        throw new UnsupportedOperationException("MSSQL flusher uses single-row version-guarded deletes by default");
    }

    @Override
    public String upsertSql(QueuedWriteOperation operation, List<Map.Entry<String, String>> entries) {
        return updateSql(operation, entries);
    }

    @Override
    public String upsertMultiRowSql(QueuedWriteOperation operation, List<Map.Entry<String, String>> entries, int rowCount) {
        throw new UnsupportedOperationException("MSSQL flusher uses update-exists-insert instead of multi-row MERGE");
    }

    public String updateSql(QueuedWriteOperation operation, List<Map.Entry<String, String>> entries) {
        StringJoiner updateSet = new StringJoiner(", ");
        for (Map.Entry<String, String> entry : entries) {
            if (!entry.getKey().equals(operation.idColumn())) {
                updateSet.add(entry.getKey() + " = ?");
            }
        }
        if (updateSet.length() == 0) {
            throw new IllegalArgumentException("MSSQL upsert requires at least one non-id column: " + operation.tableName());
        }
        return "UPDATE " + operation.tableName() + " WITH (UPDLOCK, HOLDLOCK)"
                + " SET " + updateSet
                + " WHERE " + operation.idColumn() + " = ?"
                + " AND (" + operation.versionColumn() + " IS NULL OR " + operation.versionColumn() + " <= ?)";
    }

    public String existsSql(QueuedWriteOperation operation) {
        return "SELECT 1 FROM " + operation.tableName() + " WITH (UPDLOCK, HOLDLOCK)"
                + " WHERE " + operation.idColumn() + " = ?";
    }

    public String insertSql(QueuedWriteOperation operation, List<Map.Entry<String, String>> entries) {
        StringJoiner insertColumns = new StringJoiner(", ");
        StringJoiner insertValues = new StringJoiner(", ");
        for (Map.Entry<String, String> entry : entries) {
            insertColumns.add(entry.getKey());
            insertValues.add("?");
        }
        return "INSERT INTO " + operation.tableName()
                + " (" + insertColumns + ") VALUES (" + insertValues + ")";
    }

    @Override
    public String sqlCastType(String javaTypeName) {
        return switch (javaTypeName) {
            case "int", "java.lang.Integer" -> "INT";
            case "long", "java.lang.Long" -> "BIGINT";
            case "boolean", "java.lang.Boolean" -> "BIT";
            case "double", "java.lang.Double" -> "FLOAT";
            case "float", "java.lang.Float" -> "REAL";
            case "short", "java.lang.Short" -> "SMALLINT";
            case "byte", "java.lang.Byte" -> "TINYINT";
            case "java.time.Instant", "java.time.OffsetDateTime" -> "DATETIMEOFFSET";
            case "java.time.LocalDate", "java.time.LocalDateTime" -> "DATETIME2";
            default -> "NVARCHAR(MAX)";
        };
    }
}

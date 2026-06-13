package com.reactor.cachedb.postgres;

import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.jdbc.JdbcDatabaseDialect;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public final class PostgresDatabaseDialect implements JdbcDatabaseDialect {
    private static final int POSTGRES_PARAMETER_LIMIT = 65_535;

    @Override
    public String name() {
        return "postgres";
    }

    @Override
    public int maxParametersPerStatement() {
        return POSTGRES_PARAMETER_LIMIT;
    }

    @Override
    public boolean supportsBulkCopy() {
        return true;
    }

    @Override
    public String deleteSql(QueuedWriteOperation operation) {
        return "DELETE FROM " + operation.tableName()
                + " WHERE " + operation.idColumn() + " = ?"
                + " AND (" + operation.versionColumn() + " IS NULL OR " + operation.versionColumn() + " <= ?)";
    }

    @Override
    public String deleteMultiRowSql(QueuedWriteOperation operation, int rowCount) {
        StringJoiner valuesJoiner = new StringJoiner(", ");
        for (int index = 0; index < rowCount; index++) {
            valuesJoiner.add("(?, ?)");
        }
        return "DELETE FROM " + operation.tableName() + " target USING (VALUES " + valuesJoiner + ") AS staged("
                + operation.idColumn() + ", " + operation.versionColumn() + ") "
                + "WHERE target." + operation.idColumn() + " = staged." + operation.idColumn()
                + " AND (target." + operation.versionColumn() + " IS NULL OR target." + operation.versionColumn()
                + " <= staged." + operation.versionColumn() + ")";
    }

    @Override
    public String upsertSql(QueuedWriteOperation operation, List<Map.Entry<String, String>> entries) {
        StringJoiner insertColumns = new StringJoiner(", ");
        StringJoiner insertValues = new StringJoiner(", ");
        StringJoiner updateSet = new StringJoiner(", ");

        for (Map.Entry<String, String> entry : entries) {
            insertColumns.add(entry.getKey());
            insertValues.add("?");
            if (!entry.getKey().equals(operation.idColumn())) {
                updateSet.add(entry.getKey() + " = EXCLUDED." + entry.getKey());
            }
        }

        return "INSERT INTO " + operation.tableName()
                + " (" + insertColumns + ") VALUES (" + insertValues + ") "
                + "ON CONFLICT (" + operation.idColumn() + ") DO UPDATE SET " + updateSet
                + " WHERE " + operation.tableName() + "." + operation.versionColumn()
                + " IS NULL OR EXCLUDED." + operation.versionColumn()
                + " > " + operation.tableName() + "." + operation.versionColumn();
    }

    @Override
    public String upsertMultiRowSql(
            QueuedWriteOperation operation,
            List<Map.Entry<String, String>> entries,
            int rowCount
    ) {
        StringJoiner insertColumns = new StringJoiner(", ");
        StringJoiner valuesRows = new StringJoiner(", ");
        StringJoiner updateSet = new StringJoiner(", ");

        for (Map.Entry<String, String> entry : entries) {
            insertColumns.add(entry.getKey());
            if (!entry.getKey().equals(operation.idColumn())) {
                updateSet.add(entry.getKey() + " = EXCLUDED." + entry.getKey());
            }
        }
        for (int row = 0; row < rowCount; row++) {
            StringJoiner rowValues = new StringJoiner(", ", "(", ")");
            for (int index = 0; index < entries.size(); index++) {
                rowValues.add("?");
            }
            valuesRows.add(rowValues.toString());
        }

        return "INSERT INTO " + operation.tableName()
                + " (" + insertColumns + ") VALUES " + valuesRows
                + " ON CONFLICT (" + operation.idColumn() + ") DO UPDATE SET " + updateSet
                + " WHERE " + operation.tableName() + "." + operation.versionColumn()
                + " IS NULL OR EXCLUDED." + operation.versionColumn()
                + " > " + operation.tableName() + "." + operation.versionColumn();
    }

    @Override
    public String sqlCastType(String javaTypeName) {
        return switch (javaTypeName) {
            case "int", "java.lang.Integer" -> "INTEGER";
            case "long", "java.lang.Long" -> "BIGINT";
            case "boolean", "java.lang.Boolean" -> "BOOLEAN";
            case "double", "java.lang.Double" -> "DOUBLE PRECISION";
            case "float", "java.lang.Float" -> "REAL";
            case "short", "java.lang.Short" -> "SMALLINT";
            case "byte", "java.lang.Byte" -> "SMALLINT";
            default -> "TEXT";
        };
    }
}

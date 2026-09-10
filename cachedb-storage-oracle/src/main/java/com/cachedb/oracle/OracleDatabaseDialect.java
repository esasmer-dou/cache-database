package com.reactor.cachedb.oracle;

import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.jdbc.JdbcDatabaseDialect;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public final class OracleDatabaseDialect implements JdbcDatabaseDialect {
    private static final int SAFE_PARAMETER_LIMIT = 65_535;

    @Override
    public String name() {
        return "oracle";
    }

    @Override
    public int maxParametersPerStatement() {
        return SAFE_PARAMETER_LIMIT;
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
        throw new UnsupportedOperationException("Oracle flusher uses JDBC prepared-statement batching for deletes");
    }

    @Override
    public String upsertSql(QueuedWriteOperation operation, List<Map.Entry<String, String>> entries) {
        StringJoiner sourceColumns = new StringJoiner(", ");
        StringJoiner updateSet = new StringJoiner(", ");
        StringJoiner insertColumns = new StringJoiner(", ");
        StringJoiner insertValues = new StringJoiner(", ");
        for (Map.Entry<String, String> entry : entries) {
            String column = entry.getKey();
            sourceColumns.add("? " + column);
            insertColumns.add(column);
            insertValues.add("source." + column);
            if (!column.equals(operation.idColumn())) {
                updateSet.add("target." + column + " = source." + column);
            }
        }
        if (updateSet.length() == 0) {
            throw new IllegalArgumentException("Oracle upsert requires at least one non-id column: " + operation.tableName());
        }
        return "MERGE INTO " + operation.tableName() + " target"
                + " USING (SELECT " + sourceColumns + " FROM dual) source"
                + " ON (target." + operation.idColumn() + " = source." + operation.idColumn() + ")"
                + " WHEN MATCHED THEN UPDATE SET " + updateSet
                + " WHERE target." + operation.versionColumn() + " IS NULL"
                + " OR source." + operation.versionColumn() + " > target." + operation.versionColumn()
                + " WHEN NOT MATCHED THEN INSERT (" + insertColumns + ")"
                + " VALUES (" + insertValues + ")";
    }

    @Override
    public String upsertMultiRowSql(
            QueuedWriteOperation operation,
            List<Map.Entry<String, String>> entries,
            int rowCount
    ) {
        throw new UnsupportedOperationException("Oracle flusher uses JDBC batching of version-guarded MERGE statements");
    }

    @Override
    public String sqlCastType(String javaTypeName) {
        return switch (javaTypeName) {
            case "int", "java.lang.Integer" -> "NUMBER(10)";
            case "long", "java.lang.Long" -> "NUMBER(19)";
            case "boolean", "java.lang.Boolean" -> "NUMBER(1)";
            case "double", "java.lang.Double" -> "BINARY_DOUBLE";
            case "float", "java.lang.Float" -> "BINARY_FLOAT";
            case "short", "java.lang.Short" -> "NUMBER(5)";
            case "byte", "java.lang.Byte" -> "NUMBER(3)";
            case "java.math.BigDecimal" -> "NUMBER";
            case "java.math.BigInteger" -> "NUMBER(38,0)";
            case "java.time.Instant", "java.time.OffsetDateTime" -> "TIMESTAMP WITH TIME ZONE";
            case "java.time.LocalDate" -> "DATE";
            case "java.time.LocalDateTime" -> "TIMESTAMP";
            default -> "VARCHAR2(4000 CHAR)";
        };
    }
}

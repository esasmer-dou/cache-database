package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.registry.EntityRegistry;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class JdbcWriteBehindSupport {

    private JdbcWriteBehindSupport() {
    }

    public static String columnValue(QueuedWriteOperation operation, String columnName) {
        return operation.columns().get(columnName);
    }

    public static List<String> orderedColumnValues(QueuedWriteOperation operation, List<Map.Entry<String, String>> entries) {
        ArrayList<String> values = new ArrayList<>(entries.size());
        for (Map.Entry<String, String> entry : entries) {
            values.add(columnValue(operation, entry.getKey()));
        }
        return values;
    }

    public static void bindUpsert(
            PreparedStatement statement,
            QueuedWriteOperation operation,
            EntityRegistry entityRegistry,
            List<Map.Entry<String, String>> entries
    ) throws SQLException {
        List<String> values = orderedColumnValues(operation, entries);
        for (int i = 0; i < values.size(); i++) {
            statement.setObject(
                    i + 1,
                    convertValue(values.get(i), columnType(entityRegistry, operation, entries.get(i).getKey()))
            );
        }
    }

    public static Object convertValue(String value, String typeName) {
        if (value == null) {
            return null;
        }

        return switch (typeName) {
            case "java.lang.String" -> value;
            case "int", "java.lang.Integer" -> Integer.valueOf(value);
            case "long", "java.lang.Long" -> Long.valueOf(value);
            case "boolean", "java.lang.Boolean" -> Boolean.valueOf(value);
            case "double", "java.lang.Double" -> Double.valueOf(value);
            case "float", "java.lang.Float" -> Float.valueOf(value);
            case "short", "java.lang.Short" -> Short.valueOf(value);
            case "byte", "java.lang.Byte" -> Byte.valueOf(value);
            case "java.time.Instant" -> Instant.parse(value);
            case "java.time.LocalDate" -> LocalDate.parse(value);
            case "java.time.LocalDateTime" -> LocalDateTime.parse(value);
            case "java.time.OffsetDateTime" -> OffsetDateTime.parse(value);
            default -> value;
        };
    }

    public static String columnType(EntityRegistry entityRegistry, QueuedWriteOperation operation, String columnName) {
        if (operation.versionColumn() != null && operation.versionColumn().equals(columnName)) {
            return "java.lang.Long";
        }
        if (entityRegistry == null) {
            return "java.lang.String";
        }
        return entityRegistry.find(operation.entityName())
                .map(binding -> binding.metadata().columnTypes().get(columnName))
                .orElse("java.lang.String");
    }

    public static List<List<QueuedWriteOperation>> partitionDistinctIdentityBatches(
            Collection<QueuedWriteOperation> operations,
            int rowLimitOverride
    ) {
        int rowLimit = Math.max(1, rowLimitOverride);
        ArrayList<List<QueuedWriteOperation>> partitions = new ArrayList<>();
        ArrayList<QueuedWriteOperation> current = new ArrayList<>(Math.min(rowLimit, operations.size()));
        LinkedHashSet<EntityOperationKey> seenKeys = new LinkedHashSet<>();
        for (QueuedWriteOperation operation : operations) {
            EntityOperationKey key = EntityOperationKey.of(operation);
            if (!current.isEmpty() && (current.size() >= rowLimit || seenKeys.contains(key))) {
                partitions.add(List.copyOf(current));
                current.clear();
                seenKeys.clear();
            }
            current.add(operation);
            seenKeys.add(key);
        }
        if (!current.isEmpty()) {
            partitions.add(List.copyOf(current));
        }
        return partitions;
    }

    private record EntityOperationKey(OperationType type, String tableName, String id) {
        private static EntityOperationKey of(QueuedWriteOperation operation) {
            return new EntityOperationKey(operation.type(), operation.tableName(), operation.id());
        }
    }
}

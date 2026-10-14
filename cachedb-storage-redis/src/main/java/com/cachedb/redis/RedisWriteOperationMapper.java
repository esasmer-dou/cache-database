package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.model.WriteOperation;
import com.reactor.cachedb.core.queue.PerformanceObservationContext;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RedisWriteOperationMapper {

    public <T, ID> Map<String, String> toBody(WriteOperation<T, ID> operation) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("type", operation.type().name());
        body.put("entity", operation.metadata().entityName());
        body.put("table", operation.metadata().tableName());
        body.put("namespace", operation.metadata().redisNamespace());
        body.put("observationTag", PerformanceObservationContext.currentTag());
        body.put("idColumn", operation.metadata().idColumn());
        body.put("versionColumn", operation.metadata().versionColumn());
        body.put(
                "deletedColumn",
                operation.metadata().deletedColumn() == null
                        ? ""
                        : operation.metadata().deletedColumn());
        body.put("id", String.valueOf(operation.id()));
        body.put("version", String.valueOf(operation.version()));
        body.put("createdAt", operation.createdAt().toString());
        if (operation.dependency() != null) {
            body.put("dependencyNamespace", operation.dependency().redisNamespace());
            body.put("dependencyId", operation.dependency().id());
            body.put("dependencyVersion", String.valueOf(operation.dependency().version()));
        }

        for (Map.Entry<String, Object> entry : operation.columns().entrySet()) {
            body.put(
                    "col:" + entry.getKey(),
                    entry.getValue() == null ? "__NULL__" : String.valueOf(entry.getValue()));
        }
        return body;
    }

    public Map<String, String> toBody(QueuedWriteOperation operation) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("type", operation.type().name());
        body.put("entity", operation.entityName());
        body.put("table", operation.tableName());
        body.put("namespace", operation.redisNamespace());
        body.put(
                "observationTag",
                operation.observationTag() == null ? "" : operation.observationTag());
        body.put("idColumn", operation.idColumn());
        body.put("versionColumn", operation.versionColumn());
        body.put(
                "deletedColumn",
                operation.deletedColumn() == null ? "" : operation.deletedColumn());
        body.put("id", operation.id());
        body.put("version", String.valueOf(operation.version()));
        body.put("createdAt", operation.createdAt().toString());
        if (operation.transactionGroupId() != null)
            body.put("transactionGroupId", operation.transactionGroupId());
        if (operation.atomicBatch()) {
            body.put("batchCount", Integer.toString(operation.batchOperations().size()));
            for (int i = 0; i < operation.batchOperations().size(); i++)
                for (var field : toBody(operation.batchOperations().get(i)).entrySet())
                    body.put("b" + i + ":" + field.getKey(), field.getValue());
        }
        if (operation.hasDependency()) {
            body.put("dependencyNamespace", operation.dependencyNamespace());
            body.put("dependencyId", operation.dependencyId());
            body.put("dependencyVersion", String.valueOf(operation.dependencyVersion()));
        }
        for (Map.Entry<String, String> entry : operation.columns().entrySet()) {
            body.put(
                    "col:" + entry.getKey(),
                    entry.getValue() == null ? "__NULL__" : entry.getValue());
        }
        return body;
    }

    public QueuedWriteOperation fromBody(Map<String, String> body) {
        Map<String, String> columns = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : body.entrySet()) {
            if (entry.getKey().startsWith("col:")) {
                String value = "__NULL__".equals(entry.getValue()) ? null : entry.getValue();
                columns.put(entry.getKey().substring(4), value);
            }
        }

        String operationType = firstNonBlank(body.get("type"), body.get("operationType"));
        if (operationType == null) {
            throw new IllegalArgumentException("Missing operation type in write operation body");
        }

        java.util.List<QueuedWriteOperation> members = new java.util.ArrayList<>();
        if (body.containsKey("batchCount")) {
            int count = Integer.parseInt(body.get("batchCount"));
            if (count < 1 || count > 1000)
                throw new IllegalArgumentException("Invalid write batch count");
            for (int i = 0; i < count; i++) {
                String prefix = "b" + i + ":";
                Map<String, String> member = new LinkedHashMap<>();
                for (var field : body.entrySet())
                    if (field.getKey().startsWith(prefix))
                        member.put(field.getKey().substring(prefix.length()), field.getValue());
                if (member.containsKey("batchCount"))
                    throw new IllegalArgumentException("Nested write batch");
                members.add(fromBody(member));
            }
        }
        return new QueuedWriteOperation(
                OperationType.valueOf(operationType),
                body.get("entity"),
                body.get("table"),
                body.get("namespace"),
                emptyToNull(body.get("observationTag")),
                body.get("idColumn"),
                body.get("versionColumn"),
                emptyToNull(body.get("deletedColumn")),
                body.get("id"),
                columns,
                Long.parseLong(body.get("version")),
                Instant.parse(body.get("createdAt")),
                emptyToNull(body.get("dependencyNamespace")),
                emptyToNull(body.get("dependencyId")),
                parseLong(body.get("dependencyVersion")),
                emptyToNull(body.get("transactionGroupId")),
                members);
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value);
    }
}

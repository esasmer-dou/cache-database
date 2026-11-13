package com.reactor.cachedb.core.crypto;

import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.registry.EntityRegistry;
import java.sql.SQLException;
import java.util.*;

/** Creates transient SQL copies; never mutates a queue/retry/DLQ envelope. */
public final class EncryptedQueuedWrites {
    private EncryptedQueuedWrites() {}

    public static boolean protectedOperation(QueuedWriteOperation operation) {
        if (operation == null) return false;
        if (operation.columns() != null && operation.columns().containsKey(EncryptedCodecSupport.QUEUE_FORMAT)) return true;
        for (var member : operation.batchOperations())
            if (member.columns() != null && member.columns().containsKey(EncryptedCodecSupport.QUEUE_FORMAT)) return true;
        return false;
    }

    public static boolean protectedBatch(EntityRegistry registry, List<QueuedWriteOperation> operations) {
        if (operations == null || operations.size() > 10000) throw EncryptedCodecSupport.invalidValue();
        for (var operation : operations) {
            if (protectedOperation(operation)) return true;
            if (operation.atomicBatch()) {
                if (operation.batchOperations().size() > 1000) throw EncryptedCodecSupport.invalidValue();
                for (var member : operation.batchOperations()) {
                    if (member.atomicBatch()) throw EncryptedCodecSupport.invalidValue();
                    if (registry != null && registry.find(member.entityName())
                            .map(binding -> EncryptedStorageChecks.protectedEntity(binding.metadata())).orElse(false)) return true;
                }
            }
            if (registry != null && registry.find(operation.entityName())
                    .map(binding -> EncryptedStorageChecks.protectedEntity(binding.metadata())).orElse(false)) return true;
        }
        return false;
    }

    public static List<QueuedWriteOperation> sourceColumns(EntityRegistry registry, List<QueuedWriteOperation> operations) {
        return sourceColumns(registry, operations, new long[1], 0);
    }

    private static List<QueuedWriteOperation> sourceColumns(EntityRegistry registry, List<QueuedWriteOperation> operations,
            long[] budget, int depth) {
        if (operations == null || operations.size() > 10000) throw EncryptedCodecSupport.invalidValue();
        boolean protectedBatch = protectedBatch(registry, operations);
        if (!protectedBatch) return operations;
        var result = new ArrayList<QueuedWriteOperation>(operations.size());
        for (var operation : operations) {
            if (operation.atomicBatch()) {
                if (depth != 0 || operation.batchOperations().size() > 1000) throw EncryptedCodecSupport.invalidValue();
                var members = sourceColumns(registry, operation.batchOperations(), budget, depth + 1);
                result.add(new QueuedWriteOperation(operation.type(), operation.entityName(), operation.tableName(),
                        operation.redisNamespace(), operation.observationTag(), operation.idColumn(), operation.versionColumn(),
                        operation.deletedColumn(), operation.id(), Collections.unmodifiableMap(new LinkedHashMap<>(operation.columns())),
                        operation.version(), operation.createdAt(), operation.dependencyNamespace(), operation.dependencyId(),
                        operation.dependencyVersion(), operation.transactionGroupId(), members, operation.traceMetadata()));
                continue;
            }
            var binding = registry == null ? null : registry.find(operation.entityName()).orElse(null);
            boolean encrypted = binding != null && EncryptedStorageChecks.protectedEntity(binding.metadata());
            if (!encrypted) {
                if (protectedOperation(operation)) throw EncryptedCodecSupport.unbound();
                budget[0] = EncryptedStorageChecks.preparedBytes(budget[0], null, operation.columns());
                result.add(sqlCopy(operation, operation.columns()));
                continue;
            }
            var metadata = binding.metadata();
            EncryptedStorageChecks.requireBound(metadata, binding.codec());
            if (!Objects.equals(metadata.tableName(), operation.tableName())
                    || !Objects.equals(metadata.redisNamespace(), operation.redisNamespace())
                    || !Objects.equals(metadata.idColumn(), operation.idColumn())
                    || !Objects.equals(metadata.versionColumn(), operation.versionColumn())
                    || !Objects.equals(metadata.deletedColumn(), operation.deletedColumn()))
                throw EncryptedCodecSupport.invalidValue();
            if (operation.type() == OperationType.DELETE) { result.add(sqlCopy(operation, operation.columns())); continue; }
            String queuedVersion = operation.columns().get(metadata.versionColumn());
            if (queuedVersion != null && !Long.toString(operation.version()).equals(queuedVersion))
                throw EncryptedCodecSupport.invalidValue();
            var columns = binding.codec().sourceColumnsFromQueued(operation.id(), operation.columns());
            budget[0] = EncryptedStorageChecks.preparedBytes(budget[0], null, columns);
            Map<String, String> immutable = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
            result.add(new QueuedWriteOperation(operation.type(), operation.entityName(), operation.tableName(),
                    operation.redisNamespace(), operation.observationTag(), operation.idColumn(), operation.versionColumn(),
                    operation.deletedColumn(), operation.id(), immutable, operation.version(), operation.createdAt(),
                    operation.dependencyNamespace(), operation.dependencyId(), operation.dependencyVersion(),
                    operation.transactionGroupId(), List.of(), operation.traceMetadata()));
        }
        return List.copyOf(result);
    }

    private static QueuedWriteOperation sqlCopy(QueuedWriteOperation operation, Map<String, String> columns) {
        return new QueuedWriteOperation(operation.type(), operation.entityName(), operation.tableName(),
                operation.redisNamespace(), operation.observationTag(), operation.idColumn(), operation.versionColumn(),
                operation.deletedColumn(), operation.id(), Collections.unmodifiableMap(new LinkedHashMap<>(columns)),
                operation.version(), operation.createdAt(), operation.dependencyNamespace(), operation.dependencyId(),
                operation.dependencyVersion(), operation.transactionGroupId(), List.of(), operation.traceMetadata());
    }

    public static Exception safeFailure(boolean protectedOperation, Exception failure) {
        if (!protectedOperation) return failure;
        if (failure instanceof SQLException sql) return CacheFieldRedactor.redactSqlFailure(sql);
        if (failure instanceof CacheEncryptionException safe) return new CacheEncryptionException(safe.code());
        return EncryptedCodecSupport.invalidValue();
    }
}

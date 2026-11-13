package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.model.WriteOperation;
import com.reactor.cachedb.core.queue.PerformanceObservationContext;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RedisWriteOperationMapper {
    private final com.reactor.cachedb.core.trace.TraceContextPropagator trace;
    private boolean generationProtectedOperation(QueuedWriteOperation operation){
        return registry!=null && registry.find(operation.entityName())
                .map(binding->binding.metadata().tenantPurgeEnabled()).orElse(false);
    }
    private final com.reactor.cachedb.core.registry.EntityRegistry registry;
    public RedisWriteOperationMapper() { this(com.reactor.cachedb.core.trace.TraceContexts.threadLocal()); }
    public RedisWriteOperationMapper(com.reactor.cachedb.core.trace.TraceContextPropagator trace) {
        this(trace, null);
    }
    public RedisWriteOperationMapper(com.reactor.cachedb.core.trace.TraceContextPropagator trace,
            com.reactor.cachedb.core.registry.EntityRegistry registry) {
        this.trace = java.util.Objects.requireNonNull(trace);
        this.registry = registry;
    }

    public <T, ID> Map<String, String> toBody(WriteOperation<T, ID> operation) {
        if(operation.metadata().tenantPurgeEnabled())throw new com.reactor.cachedb.core.protection.ProtectionException(
                com.reactor.cachedb.core.protection.ProtectionException.Code.UNWIRED_PROTOCOL);
        return legacyBody(operation);
    }

    public <T,ID> Map<String,String> toBody(com.reactor.cachedb.core.protection.TenantGenerationEnvelope<WriteOperation<T,ID>> envelope){
        var operation=envelope.payload();var stamp=envelope.generation();
        if(!com.reactor.cachedb.core.protection.ProtectionKey.hash(operation.metadata().redisNamespace()).equals(stamp.key().scopeToken())
                || operation.version()<1)throw new com.reactor.cachedb.core.protection.ProtectionException(
                        com.reactor.cachedb.core.protection.ProtectionException.Code.GENERATION_MISMATCH);
        var body=legacyBody(operation);body.put("tenantGeneration",Long.toString(stamp.value()));
        body.put("tenantToken",stamp.key().tenantToken());body.put("generationScope",stamp.key().scopeToken());return body;
    }

    private <T,ID> Map<String,String> legacyBody(WriteOperation<T,ID> operation){
        if (operation.type() != OperationType.DELETE)
            com.reactor.cachedb.core.crypto.EncryptedStorageChecks.validateQueuedColumns(operation.metadata(), operation.columns());
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
        String encodedTrace = com.reactor.cachedb.core.trace.TraceMetadataCodec.encode(
                operation.traceMetadata().isEmpty() ? trace.capture() : operation.traceMetadata());
        if (!encodedTrace.isEmpty()) body.put("traceMetadata", encodedTrace);
        if (operation.dependency() != null) {
            body.put("dependencyNamespace", operation.dependency().redisNamespace());
            body.put("dependencyId", operation.dependency().id());
            body.put("dependencyVersion", String.valueOf(operation.dependency().version()));
        }

        for (Map.Entry<String, Object> entry : operation.columns().entrySet()) {
            body.put(
                    "col:" + entry.getKey(),
                    operation.type() == OperationType.PATCH ? PatchColumnValues.encode(entry.getValue())
                            : entry.getValue() == null ? "__NULL__" : String.valueOf(entry.getValue()));
        }
        return body;
    }

    public Map<String, String> toBody(QueuedWriteOperation operation) {
        validateStoredOperation(operation);
        Map<String, String> body = new LinkedHashMap<>();
        if(generationProtectedOperation(operation) && com.reactor.cachedb.core.protection.TenantWriteStampContext.current(operation)==null
                && !operation.atomicBatch())throw new com.reactor.cachedb.core.protection.ProtectionException(
                        com.reactor.cachedb.core.protection.ProtectionException.Code.MIGRATION_REQUIRED);
        RedisQueuedGenerationCodec.append(body,operation);
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
        if (!operation.traceMetadata().isEmpty()) body.put("traceMetadata",
                com.reactor.cachedb.core.trace.TraceMetadataCodec.encode(operation.traceMetadata()));
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
                    operation.type() == OperationType.PATCH ? PatchColumnValues.encode(entry.getValue())
                            : entry.getValue() == null ? "__NULL__" : entry.getValue());
        }
        return body;
    }

    public boolean protectedOperation(QueuedWriteOperation operation) {
        if (com.reactor.cachedb.core.crypto.EncryptedQueuedWrites.protectedOperation(operation)) return true;
        if (operation == null || registry == null) return false;
        var members = operation.atomicBatch() ? operation.batchOperations() : java.util.List.of(operation);
        if (members.size() > 1000) throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue();
        for (var member : members)
            if (registry.find(member.entityName())
                    .map(binding -> com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(binding.metadata())).orElse(false))
                return true;
        return false;
    }

    public Exception safeFailure(QueuedWriteOperation operation, Exception failure) {
        return com.reactor.cachedb.core.crypto.EncryptedQueuedWrites.safeFailure(protectedOperation(operation), failure);
    }

    public com.reactor.cachedb.core.queue.WriteFailureDetails classifyFailure(QueuedWriteOperation operation,
            com.reactor.cachedb.core.queue.WriteBehindFlusher flusher, Exception failure) {
        boolean protectedWrite = protectedOperation(operation);
        Exception safe = com.reactor.cachedb.core.crypto.EncryptedQueuedWrites.safeFailure(protectedWrite, failure);
        com.reactor.cachedb.core.queue.WriteFailureDetails details;
        try { details = com.reactor.cachedb.core.queue.FailureClassifyingFlusher.classify(flusher, safe); }
        catch (RuntimeException unsafe) {
            if (!protectedWrite) throw unsafe;
            return com.reactor.cachedb.core.queue.WriteFailureDetails.unknown(safe);
        }
        if (!protectedWrite) return details;
        if (details == null) return com.reactor.cachedb.core.queue.WriteFailureDetails.unknown(safe);
        return new com.reactor.cachedb.core.queue.WriteFailureDetails(
                details.category() == null ? com.reactor.cachedb.core.queue.WriteFailureCategory.UNKNOWN : details.category(),
                safe instanceof java.sql.SQLException sql ? sql.getSQLState() : "",
                safe instanceof java.sql.SQLException sql ? sql.getErrorCode() : 0,
                details.retryable(), safe.getClass().getName(), safe.getMessage());
    }

    private void validateStoredOperation(QueuedWriteOperation operation) {
        if (registry == null) return;
        if (operation.atomicBatch()) {
            if (operation.batchOperations().size() > 1000) throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue();
            for (var member : operation.batchOperations()) {
                if (member.atomicBatch()) throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue();
                validateStoredOperation(member);
            }
            return;
        }
        var binding = registry.find(operation.entityName()).orElse(null);
        if (binding == null || !com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(binding.metadata())) return;
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.requireBound(binding.metadata(), binding.codec());
        if (operation.type() != OperationType.DELETE)
            com.reactor.cachedb.core.crypto.EncryptedStorageChecks.validateQueuedColumns(binding.metadata(), operation.columns());
    }

    /** Rebuild protected recovery/archive records; do not copy old error text or unknown fields. */
    public Map<String, String> protectedStoredFields(QueuedWriteOperation operation, Map<String, String> original) {
        var envelope=RedisQueuedGenerationCodec.decode(operation,original);
        try(var scope=com.reactor.cachedb.core.protection.TenantWriteStampContext.restore(java.util.List.of(envelope))){
            return protectedStoredFieldsInOriginalScope(operation,original);
        }
    }

    private Map<String,String> protectedStoredFieldsInOriginalScope(QueuedWriteOperation operation,Map<String,String> original){
        if (!protectedOperation(operation)) return original;
        var fields = new LinkedHashMap<>(toBody(operation));
        var safe = com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue();
        fields.put("deadLetterReason", safe.getClass().getName());
        fields.put("deadLetterMessage", safe.getMessage());
        fields.put("deadLetterRootType", safe.getClass().getName());
        fields.put("deadLetterEntity", operation.entityName());
        String category = "UNKNOWN";
        try { category = com.reactor.cachedb.core.queue.WriteFailureCategory.valueOf(
                original.getOrDefault("deadLetterFailureCategory", "UNKNOWN")).name(); }
        catch (IllegalArgumentException | NullPointerException ignored) { }
        fields.put("deadLetterFailureCategory", category);
        String state = original.getOrDefault("deadLetterSqlState", "");
        boolean validState = state != null && state.length() == 5;
        if (validState) for (int i = 0; i < state.length(); i++) {
            char c = state.charAt(i);
            if (!(c >= 'A' && c <= 'Z' || c >= '0' && c <= '9')) validState = false;
        }
        fields.put("deadLetterSqlState", validState ? state : "");
        int vendor = 0;
        String code = original.get("deadLetterVendorCode");
        if (code != null && code.length() <= 11) {
            try { vendor = Integer.parseInt(code); }
            catch (NumberFormatException ignored) { }
        }
        fields.put("deadLetterVendorCode", Integer.toString(vendor));
        fields.put("deadLetterRetryable", Boolean.toString("true".equals(original.get("deadLetterRetryable"))));
        String attempts = original.get("deadLetterAttempts");
        int count = 0;
        if (attempts != null && attempts.length() <= 10) {
            try { count = Math.max(0, Integer.parseInt(attempts)); }
            catch (NumberFormatException ignored) { }
        }
        fields.put("deadLetterAttempts", Integer.toString(count));
        return fields;
    }

    public void validateGenerationNotice(Map<String,String> body){
        String entity=body.get("entity");
        boolean required=registry!=null&&registry.find(entity).map(b->b.metadata().tenantPurgeEnabled()).orElse(false);
        String generation=body.get("tenantGeneration"),scope=body.get("generationScope"),tenant=body.get("tenantToken");
        if(!required&&generation==null&&scope==null&&tenant==null)return;
        if(generation==null||!generation.matches("[1-9][0-9]{0,18}")||scope==null||tenant==null||body.get("namespace")==null)
            throw new com.reactor.cachedb.core.protection.ProtectionException(
                    com.reactor.cachedb.core.protection.ProtectionException.Code.MIGRATION_REQUIRED);
        try{
            com.reactor.cachedb.core.protection.ProtectionKey.token(tenant);
            if(Long.parseLong(generation)<1||!scope.equals(com.reactor.cachedb.core.protection.ProtectionKey.hash(body.get("namespace"))))
                throw new IllegalArgumentException();
        }catch(RuntimeException invalid){
            throw new com.reactor.cachedb.core.protection.ProtectionException(
                    com.reactor.cachedb.core.protection.ProtectionException.Code.GENERATION_MISMATCH);
        }
    }

    public com.reactor.cachedb.core.protection.QueuedGenerationEnvelope fromStampedBody(Map<String,String> body) {
        var envelope=RedisQueuedGenerationCodec.decode(fromBody(body),body);
        var operation=envelope.operation();
        var members=operation.atomicBatch()?operation.batchOperations():java.util.List.of(operation);
        for(var member:members)if(generationProtectedOperation(member) && envelope.generation(member)==null)
            throw new com.reactor.cachedb.core.protection.ProtectionException(
                    com.reactor.cachedb.core.protection.ProtectionException.Code.MIGRATION_REQUIRED);
        return envelope;
    }

    public QueuedWriteOperation fromBody(Map<String, String> body) {
        boolean protectedBody = body != null && (body.containsKey("col:"
                + com.reactor.cachedb.core.crypto.EncryptedCodecSupport.QUEUE_FORMAT)
                || registry != null && registry.find(body.get("entity"))
                .map(binding -> com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(binding.metadata())).orElse(false));
        try { return fromBodyUnchecked(body); }
        catch (RuntimeException unsafe) {
            if (protectedBody) throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue();
            throw unsafe;
        }
    }

    private QueuedWriteOperation fromBodyUnchecked(Map<String, String> body) {
        Map<String, String> columns = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : body.entrySet()) {
            if (entry.getKey().startsWith("col:")) {
                boolean patch = "PATCH".equals(firstNonBlank(body.get("type"), body.get("operationType")));
                String value = patch ? PatchColumnValues.decode(entry.getValue())
                        : "__NULL__".equals(entry.getValue()) ? null : entry.getValue();
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
                members,
                com.reactor.cachedb.core.trace.TraceMetadataCodec.decode(body.get("traceMetadata")));
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

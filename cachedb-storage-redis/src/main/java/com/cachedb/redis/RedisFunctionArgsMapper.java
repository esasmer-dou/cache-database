package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.RedisGuardrailConfig;
import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.model.WriteOperation;
import com.reactor.cachedb.core.queue.PerformanceObservationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RedisFunctionArgsMapper {

    public <T, ID> List<String> upsertArgs(
            WriteOperation<T, ID> operation,
            CachePolicy cachePolicy,
            RedisGuardrailConfig guardrailConfig,
            boolean cacheEntity
    ) {
        return upsertArgs(operation, cachePolicy, guardrailConfig, WriteBehindConfig.defaults(), cacheEntity);
    }

    public <T, ID> List<String> upsertArgs(
            WriteOperation<T, ID> operation,
            CachePolicy cachePolicy,
            RedisGuardrailConfig guardrailConfig,
            WriteBehindConfig writeBehindConfig,
            boolean cacheEntity
    ) {
        Map<String, Object> columns = filteredColumns(operation);
        List<String> args = new ArrayList<>(16 + (columns.size() * 2));
        args.add(operation.redisPayload());
        args.add(cacheEntity ? "1" : "0");
        args.add(String.valueOf(cachePolicy.entityTtlSeconds()));
        args.add("0");
        args.add("0");
        args.add("0");
        args.add(String.valueOf(guardrailConfig.tombstoneTtlSeconds()));
        args.add(writeBehindConfig.compactionActive() ? "1" : "0");
        args.add(normalizeObservationTag(PerformanceObservationContext.currentTag()));
        args.add(operation.type().name());
        args.add(operation.metadata().entityName());
        args.add(operation.metadata().tableName());
        args.add(operation.metadata().redisNamespace());
        args.add(operation.metadata().idColumn());
        args.add(operation.metadata().versionColumn());
        args.add(operation.metadata().deletedColumn() == null ? "" : operation.metadata().deletedColumn());
        args.add(operation.metadata().activeMarkerValue());
        args.add(String.valueOf(operation.id()));
        args.add(operation.createdAt().toString());
        args.add(String.valueOf(columns.size()));

        for (Map.Entry<String, Object> entry : columns.entrySet()) {
            args.add(entry.getKey());
            args.add(entry.getValue() == null ? "__NULL__" : String.valueOf(entry.getValue()));
        }
        return args;
    }

    public <T, ID> List<String> deleteArgs(
            WriteOperation<T, ID> operation,
            RedisGuardrailConfig guardrailConfig,
            WriteBehindConfig writeBehindConfig
    ) {
        return List.of(
                "0",
                "0",
                "0",
                String.valueOf(guardrailConfig.tombstoneTtlSeconds()),
                writeBehindConfig.compactionActive() ? "1" : "0",
                normalizeObservationTag(PerformanceObservationContext.currentTag()),
                operation.metadata().entityName(),
                operation.metadata().tableName(),
                operation.metadata().redisNamespace(),
                operation.metadata().idColumn(),
                operation.metadata().versionColumn(),
                operation.metadata().deletedColumn() == null ? "" : operation.metadata().deletedColumn(),
                operation.metadata().deletedMarkerValue(),
                String.valueOf(operation.id()),
                operation.createdAt().toString()
        );
    }

    public <T, ID> List<String> deleteArgs(
            WriteOperation<T, ID> operation,
            RedisGuardrailConfig guardrailConfig
    ) {
        return deleteArgs(operation, guardrailConfig, WriteBehindConfig.defaults());
    }

    private <T, ID> Map<String, Object> filteredColumns(WriteOperation<T, ID> operation) {
        Map<String, Object> filtered = new java.util.LinkedHashMap<>(operation.columns());
        filtered.remove(operation.metadata().versionColumn());
        if (operation.metadata().deletedColumn() != null) {
            filtered.remove(operation.metadata().deletedColumn());
        }
        return filtered;
    }

    private String normalizeObservationTag(String observationTag) {
        return observationTag == null ? "" : observationTag;
    }
}

package com.reactor.cachedb.core.queue;

public record SchemaMigrationStep(
        String entityName,
        String tableName,
        String sql,
        String reason
) {
}

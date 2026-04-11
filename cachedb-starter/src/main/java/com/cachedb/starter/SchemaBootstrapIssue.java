package com.reactor.cachedb.starter;

public record SchemaBootstrapIssue(
        String entityName,
        String tableName,
        String issue
) {
}

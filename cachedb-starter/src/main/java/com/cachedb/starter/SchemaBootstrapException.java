package com.reactor.cachedb.starter;

import java.util.stream.Collectors;

public final class SchemaBootstrapException extends IllegalStateException {
    public SchemaBootstrapException(SchemaBootstrapResult result) {
        super("CacheDB schema bootstrap failed in mode " + result.mode() + ": "
                + result.issues().stream()
                .map(issue -> issue.entityName() + "@" + issue.tableName() + " - " + issue.issue())
                .collect(Collectors.joining("; ")));
    }
}

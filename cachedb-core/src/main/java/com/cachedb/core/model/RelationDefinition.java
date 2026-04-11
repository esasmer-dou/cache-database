package com.reactor.cachedb.core.model;

public record RelationDefinition(
        String name,
        String targetEntity,
        String mappedBy,
        RelationKind kind,
        boolean batchLoadOnly
) {
}

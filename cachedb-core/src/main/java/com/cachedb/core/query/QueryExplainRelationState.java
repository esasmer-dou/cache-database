package com.reactor.cachedb.core.query;

public record QueryExplainRelationState(
        String relationName,
        String usage,
        String status,
        String kind,
        String targetEntity,
        String mappedBy,
        boolean batchLoadOnly,
        boolean indexed,
        int candidateCount,
        String detail
) {
}

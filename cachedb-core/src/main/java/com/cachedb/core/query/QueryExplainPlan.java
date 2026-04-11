package com.reactor.cachedb.core.query;

import java.util.List;

public record QueryExplainPlan(
        String entityName,
        int filterCount,
        int sortCount,
        int offset,
        int limit,
        boolean fullyIndexed,
        int candidateCount,
        long estimatedCost,
        String plannerStrategy,
        String sortStrategy,
        int sortablePrefixLength,
        List<String> fetchRelations,
        List<QueryExplainStep> steps,
        List<QueryExplainRelationState> relationStates,
        List<String> warnings
) {
}

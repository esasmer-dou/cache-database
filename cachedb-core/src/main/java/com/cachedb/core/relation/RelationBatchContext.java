package com.reactor.cachedb.core.relation;

import com.reactor.cachedb.core.config.RelationConfig;
import com.reactor.cachedb.core.plan.FetchPlan;

public record RelationBatchContext(
        FetchPlan fetchPlan,
        RelationConfig relationConfig
) {
    public boolean hasRelationLimit(String relationName) {
        return fetchPlan.hasRelationLimit(relationName);
    }

    public int relationLimit(String relationName) {
        return fetchPlan.relationLimit(relationName);
    }

    public RelationBatchContext childContext(String relationName) {
        return new RelationBatchContext(fetchPlan.nestedUnder(relationName), relationConfig);
    }
}

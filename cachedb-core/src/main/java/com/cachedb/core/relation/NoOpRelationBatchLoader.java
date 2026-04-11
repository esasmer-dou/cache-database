package com.reactor.cachedb.core.relation;

import java.util.List;

public final class NoOpRelationBatchLoader<T> implements RelationBatchLoader<T> {
    @Override
    public void preload(List<T> entities, RelationBatchContext context) {
    }
}

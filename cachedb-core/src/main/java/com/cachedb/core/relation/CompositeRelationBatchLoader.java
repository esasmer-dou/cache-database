package com.reactor.cachedb.core.relation;

import java.util.List;

/** Runs multiple generated relation loaders through one entity registration. */
public final class CompositeRelationBatchLoader<T> implements RelationBatchLoader<T> {

    private final List<RelationBatchLoader<T>> delegates;

    public CompositeRelationBatchLoader(List<? extends RelationBatchLoader<T>> delegates) {
        this.delegates = List.copyOf(delegates);
        if (this.delegates.isEmpty()) {
            throw new IllegalArgumentException("At least one relation loader is required");
        }
    }

    @Override
    public void preload(List<T> parents, RelationBatchContext context) {
        for (RelationBatchLoader<T> delegate : delegates) {
            delegate.preload(parents, context);
        }
    }
}

package com.reactor.cachedb.core.relation;

import java.util.List;

public interface RelationBatchLoader<T> {
    void preload(List<T> entities, RelationBatchContext context);
}

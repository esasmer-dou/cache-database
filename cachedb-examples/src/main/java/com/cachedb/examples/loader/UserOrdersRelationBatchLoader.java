package com.reactor.cachedb.examples.loader;

import com.reactor.cachedb.core.relation.RelationBatchContext;
import com.reactor.cachedb.core.relation.RelationBatchLoader;
import com.reactor.cachedb.examples.entity.UserEntity;

import java.util.List;

public final class UserOrdersRelationBatchLoader implements RelationBatchLoader<UserEntity> {
    @Override
    public void preload(List<UserEntity> entities, RelationBatchContext context) {
        for (UserEntity entity : entities) {
            if (entity.orders == null) {
                entity.orders = List.of();
            }
        }
    }
}

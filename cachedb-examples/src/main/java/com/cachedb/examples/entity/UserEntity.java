package com.reactor.cachedb.examples.entity;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheDeleteCommand;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheFetchPreset;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheNamedQuery;
import com.reactor.cachedb.annotations.CachePagePreset;
import com.reactor.cachedb.annotations.CacheRelation;
import com.reactor.cachedb.annotations.CacheSaveCommand;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.examples.loader.UserOrdersRelationBatchLoader;
import com.reactor.cachedb.examples.loader.UserPageLoader;

import java.util.List;

@CacheEntity(
        table = "cachedb_example_users",
        redisNamespace = "users",
        relationLoader = UserOrdersRelationBatchLoader.class,
        pageLoader = UserPageLoader.class
)
public class UserEntity {

    @CacheId(column = "id")
    public Long id;

    @CacheColumn("username")
    public String username;

    @CacheColumn("status")
    public String status;

    @CacheRelation(
            targetEntity = "OrderEntity",
            mappedBy = "userId",
            kind = CacheRelation.RelationKind.ONE_TO_MANY,
            batchLoadOnly = true
    )
    public List<OrderEntity> orders;

    public UserEntity() {
    }

    @CacheNamedQuery("activeUsers")
    public static QuerySpec activeUsersQuery(int limit) {
        return QuerySpec.where(QueryFilter.eq("status", "ACTIVE"))
                .orderBy(QuerySort.asc("username"))
                .limitTo(limit);
    }

    @CacheFetchPreset("ordersPreview")
    public static FetchPlan ordersPreviewFetchPlan(int relationLimit) {
        return FetchPlan.of("orders").withRelationLimit("orders", relationLimit);
    }

    @CachePagePreset("usersPage")
    public static PageWindow usersPageWindow(int pageNumber, int pageSize) {
        return new PageWindow(pageNumber, pageSize);
    }

    @CacheSaveCommand("activateUser")
    public static UserEntity activateUserCommand(long id, String username) {
        UserEntity entity = new UserEntity();
        entity.id = id;
        entity.username = username;
        entity.status = "ACTIVE";
        return entity;
    }

    @CacheDeleteCommand("deleteUser")
    public static Long deleteUserId(long id) {
        return id;
    }
}

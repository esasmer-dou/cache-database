package com.example.cachedb.sample.domain;

import com.example.cachedb.sample.relation.CustomerOrdersRelationBatchLoader;
import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheFetchPreset;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheNamedQuery;
import com.reactor.cachedb.annotations.CacheRelation;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;

import java.util.List;

@CacheEntity(
        table = "sample_customers",
        redisNamespace = "sample-customers",
        relationLoader = CustomerOrdersRelationBatchLoader.class
)
public class CustomerEntity {

    @CacheId(column = "customer_id")
    public Long customerId;

    @CacheColumn("tax_number")
    public String taxNumber;

    @CacheColumn("customer_type")
    public String customerType;

    @CacheColumn("segment")
    public String segment;

    @CacheColumn("status")
    public String status;

    @CacheColumn("created_at")
    public Long createdAt;

    @CacheColumn("updated_at")
    public Long updatedAt;

    @CacheRelation(
            targetEntity = "OrderEntity",
            mappedBy = "customerId",
            kind = CacheRelation.RelationKind.ONE_TO_MANY,
            batchLoadOnly = true
    )
    public List<OrderEntity> orders;

    public CustomerEntity() {
    }

    @CacheNamedQuery("activeCustomers")
    public static QuerySpec activeCustomersQuery(int limit) {
        return QuerySpec.where(QueryFilter.eq("status", "ACTIVE"))
                .orderBy(QuerySort.desc("updated_at"), QuerySort.asc("customer_id"))
                .limitTo(limit);
    }

    @CacheFetchPreset("ordersPreview")
    public static FetchPlan ordersPreviewFetchPlan(int orderLimit) {
        return FetchPlan.of("orders").withRelationLimit("orders", Math.max(1, orderLimit));
    }
}

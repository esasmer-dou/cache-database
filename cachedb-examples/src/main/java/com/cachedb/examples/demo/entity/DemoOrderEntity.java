package com.reactor.cachedb.examples.demo.entity;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheFetchPreset;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheNamedQuery;
import com.reactor.cachedb.annotations.CacheProjectionDefinition;
import com.reactor.cachedb.annotations.CacheRelation;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.examples.demo.DemoOrderLinesRelationBatchLoader;
import com.reactor.cachedb.examples.demo.DemoOrderReadModelPatterns;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;

import java.util.List;

@CacheEntity(
        table = "cachedb_demo_orders",
        redisNamespace = "demo-orders",
        relationLoader = DemoOrderLinesRelationBatchLoader.class
)
public class DemoOrderEntity {

    @CacheId(column = "id")
    public Long id;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheColumn("product_id")
    public Long productId;

    @CacheColumn("total_amount")
    public Double totalAmount;

    @CacheColumn("line_item_count")
    public Integer lineItemCount;

    @CacheColumn("status")
    public String status;

    @CacheRelation(
            targetEntity = "DemoOrderLineEntity",
            mappedBy = "orderId",
            kind = CacheRelation.RelationKind.ONE_TO_MANY,
            batchLoadOnly = true
    )
    public List<DemoOrderLineEntity> orderLines;

    public DemoOrderEntity() {
    }

    @CacheProjectionDefinition("orderSummary")
    public static EntityProjection<DemoOrderEntity, DemoOrderReadModelPatterns.OrderSummaryReadModel, Long> orderSummaryProjection() {
        return DemoOrderReadModelPatterns.ORDER_SUMMARY_PROJECTION;
    }

    @CacheNamedQuery("topCustomerOrders")
    public static QuerySpec topCustomerOrdersQuery(long customerId, int limit) {
        return QuerySpec.where(QueryFilter.eq("customer_id", customerId))
                .orderBy(QuerySort.desc("line_item_count"), QuerySort.desc("total_amount"))
                .limitTo(limit);
    }

    @CacheNamedQuery("highLineOrders")
    public static QuerySpec highLineOrdersQuery(int minimumLineCount, int limit) {
        return QuerySpec.where(QueryFilter.gte("line_item_count", minimumLineCount))
                .orderBy(QuerySort.desc("line_item_count"), QuerySort.desc("total_amount"))
                .limitTo(limit);
    }

    @CacheFetchPreset("orderLinesPreview")
    public static FetchPlan orderLinesPreviewFetchPlan(int lineLimit) {
        return FetchPlan.of("orderLines").withRelationLimit("orderLines", lineLimit);
    }
}

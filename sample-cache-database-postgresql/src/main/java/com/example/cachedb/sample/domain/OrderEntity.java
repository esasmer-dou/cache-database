package com.example.cachedb.sample.domain;

import com.example.cachedb.sample.readmodel.OrderReadModels;
import com.example.cachedb.sample.relation.OrderLinesRelationBatchLoader;
import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheFetchPreset;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheNamedQuery;
import com.reactor.cachedb.annotations.CacheProjectionDefinition;
import com.reactor.cachedb.annotations.CacheRelation;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;

import java.math.BigDecimal;
import java.util.List;

@CacheEntity(
        table = "sample_orders",
        redisNamespace = "sample-orders",
        relationLoader = OrderLinesRelationBatchLoader.class
)
public class OrderEntity {

    @CacheId(column = "order_id")
    public Long orderId;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheColumn("order_date")
    public Long orderDate;

    @CacheColumn("order_amount")
    public BigDecimal orderAmount;

    @CacheColumn("currency_code")
    public String currencyCode;

    @CacheColumn("order_type")
    public String orderType;

    @CacheColumn("status")
    public String status;

    @CacheColumn("line_count")
    public Integer lineCount;

    @CacheColumn("priority_score")
    public Double priorityScore;

    @CacheRelation(
            targetEntity = "OrderLineEntity",
            mappedBy = "orderId",
            kind = CacheRelation.RelationKind.ONE_TO_MANY,
            batchLoadOnly = true
    )
    public List<OrderLineEntity> lines;

    public OrderEntity() {
    }

    @CacheProjectionDefinition("orderSummary")
    public static EntityProjection<OrderEntity, OrderReadModels.OrderSummary, Long> orderSummaryProjection() {
        return OrderReadModels.ORDER_SUMMARY_PROJECTION;
    }

    @CacheNamedQuery("customerTimeline")
    public static QuerySpec customerTimelineQuery(long customerId, int limit) {
        return QuerySpec.where(QueryFilter.eq("customer_id", customerId))
                .orderBy(QuerySort.desc("order_date"), QuerySort.desc("order_id"))
                .limitTo(limit);
    }

    @CacheNamedQuery("recentHighValueOrders")
    public static QuerySpec recentHighValueOrdersQuery(BigDecimal minimumAmount, int limit) {
        return QuerySpec.where(QueryFilter.gte("order_amount", minimumAmount))
                .orderBy(QuerySort.desc("priority_score"), QuerySort.desc("order_date"))
                .limitTo(limit);
    }

    @CacheNamedQuery("activeOrderWindow")
    public static QuerySpec activeOrderWindowQuery(long cutoffEpochSeconds, int limit) {
        return QuerySpec.anyOf(
                        QueryFilter.gte("order_date", cutoffEpochSeconds),
                        QueryFilter.in("status", List.<Object>of("NEW", "PAID", "PICKING", "OPEN", "PENDING"))
                )
                .orderBy(QuerySort.desc("order_date"), QuerySort.desc("order_id"))
                .limitTo(limit);
    }

    @CacheFetchPreset("linePreview")
    public static FetchPlan linePreviewFetchPlan(int lineLimit) {
        return FetchPlan.of("lines").withRelationLimit("lines", Math.max(1, lineLimit));
    }
}

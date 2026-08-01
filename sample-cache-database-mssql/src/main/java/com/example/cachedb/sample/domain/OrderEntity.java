package com.example.cachedb.sample.domain;

import com.example.cachedb.sample.readmodel.OrderSummary;
import com.example.cachedb.sample.readmodel.OrderSummaryProjection;
import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheFetchPreset;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheNamedQuery;
import com.reactor.cachedb.annotations.CacheProjectionDefinition;
import com.reactor.cachedb.annotations.CachePartitionedIndex;
import com.reactor.cachedb.annotations.CacheRelation;
import com.reactor.cachedb.annotations.CacheRoute;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QueryGroup;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;

import java.math.BigDecimal;
import java.util.List;

@CacheEntity(
        table = "sample_orders",
        redisNamespace = "sample-orders"
)
@CachePartitionedIndex(partitionBy = "customer_id", sortBy = "order_date")
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
            target = OrderLineEntity.class,
            mappedBy = "orderId",
            kind = CacheRelation.RelationKind.ONE_TO_MANY,
            batchLoadOnly = true,
            maxRowsPerParent = 50,
            parentBatchSize = 16,
            orderBy = "lineNumber ASC"
    )
    public List<OrderLineEntity> lines;

    public OrderEntity() {
    }

    @CacheProjectionDefinition("orderSummary")
    public static EntityProjection<OrderEntity, OrderSummary, Long> orderSummaryProjection() {
        return OrderSummaryProjection.PROJECTION;
    }

    @CacheNamedQuery("customerTimeline")
    @CacheRoute(value = "customer-order-timeline", projection = "orderSummary", pageSize = 100,
            hotWindow = 1_000, maxColdReadSize = 500, memoryBudgetBytes = 16_777_216)
    public static QuerySpec customerTimelineQuery(long customerId, int limit) {
        return QuerySpec.where(
                        QueryFilter.eq("customer_id", customerId),
                        QueryFilter.ne("status", "DELETED")
                )
                .orderBy(QuerySort.desc("order_date"), QuerySort.desc("order_id"))
                .limitTo(limit);
    }

    @CacheNamedQuery("recentHighValueOrders")
    @CacheRoute(value = "recent-high-value-orders", projection = "orderSummary", pageSize = 100,
            hotWindow = 5_000, maxColdReadSize = 500, memoryBudgetBytes = 33_554_432)
    public static QuerySpec recentHighValueOrdersQuery(BigDecimal minimumAmount, int limit) {
        return QuerySpec.where(
                        QueryFilter.gte("order_amount", minimumAmount),
                        QueryFilter.ne("status", "DELETED")
                )
                .orderBy(QuerySort.desc("priority_score"), QuerySort.desc("order_date"))
                .limitTo(limit);
    }

    @CacheNamedQuery("highlightedOrders")
    @CacheRoute(value = "dashboard-highlighted-orders", projection = "orderSummary", pageSize = 100,
            hotWindow = 2_000, maxColdReadSize = 100, memoryBudgetBytes = 16_777_216)
    public static QuerySpec highlightedOrdersQuery(double minimumPriorityScore, int limit) {
        return QuerySpec.where(
                        QueryFilter.gte("priority_score", minimumPriorityScore),
                        QueryFilter.ne("status", "DELETED")
                )
                .orderBy(QuerySort.desc("priority_score"), QuerySort.desc("order_date"))
                .limitTo(limit);
    }

    @CacheNamedQuery("activeOrderWindow")
    @CacheRoute(value = "active-order-window", projection = "orderSummary", pageSize = 100,
            hotWindow = 1_000, maxColdReadSize = 500, memoryBudgetBytes = 16_777_216)
    public static QuerySpec activeOrderWindowQuery(long cutoffEpochSeconds, int limit) {
        return QuerySpec.anyOf(
                        QueryFilter.gte("order_date", cutoffEpochSeconds),
                        QueryFilter.in("status", List.<Object>of("NEW", "PAID", "PICKING", "OPEN", "PENDING"))
                )
                .orderBy(QuerySort.desc("order_date"), QuerySort.desc("order_id"))
                .limitTo(limit);
    }

    @CacheNamedQuery("customerOrderArchive")
    @CacheRoute(value = "customer-order-archive", pageSize = 100, hotWindow = 100,
            maxColdReadSize = 500, memoryBudgetBytes = 0, strict = true)
    public static QuerySpec customerOrderArchiveQuery(
            long customerId,
            long beforeOrderDate,
            long beforeOrderId,
            int limit
    ) {
        return QuerySpec.where(
                        QueryFilter.eq("customer_id", customerId),
                        QueryFilter.ne("status", "DELETED"),
                        QueryGroup.or(
                                QueryFilter.lt("order_date", beforeOrderDate),
                                QueryGroup.and(
                                        QueryFilter.eq("order_date", beforeOrderDate),
                                        QueryFilter.lt("order_id", beforeOrderId)
                                )
                        )
                )
                .orderBy(QuerySort.desc("order_date"), QuerySort.desc("order_id"))
                .limitTo(limit);
    }

    @CacheFetchPreset("linePreview")
    public static FetchPlan linePreviewFetchPlan(int lineLimit) {
        return FetchPlan.of("lines").withRelationLimit("lines", Math.max(1, lineLimit));
    }
}

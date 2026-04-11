package com.reactor.cachedb.examples.demo.entity;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheNamedQuery;
import com.reactor.cachedb.annotations.CacheProjectionDefinition;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.examples.demo.DemoOrderReadModelPatterns;

import java.util.List;

@CacheEntity(table = "cachedb_demo_order_lines", redisNamespace = "demo-order-lines")
public class DemoOrderLineEntity {

    @CacheId(column = "id")
    public Long id;

    @CacheColumn("order_id")
    public Long orderId;

    @CacheColumn("product_id")
    public Long productId;

    @CacheColumn("line_number")
    public Integer lineNumber;

    @CacheColumn("sku")
    public String sku;

    @CacheColumn("quantity")
    public Integer quantity;

    @CacheColumn("unit_price")
    public Double unitPrice;

    @CacheColumn("line_total")
    public Double lineTotal;

    @CacheColumn("status")
    public String status;

    public DemoOrderLineEntity() {
    }

    @CacheProjectionDefinition("orderLinePreview")
    public static EntityProjection<DemoOrderLineEntity, DemoOrderReadModelPatterns.OrderLinePreviewReadModel, Long> orderLinePreviewProjection() {
        return DemoOrderReadModelPatterns.ORDER_LINE_PREVIEW_PROJECTION;
    }

    @CacheNamedQuery("previewLinesForOrders")
    public static QuerySpec previewLinesForOrdersQuery(List<Long> orderIds, int totalLimit) {
        List<Object> rawValues = orderIds.stream()
                .filter(id -> id != null)
                .map(id -> (Object) id)
                .toList();
        return QuerySpec.where(QueryFilter.in("order_id", rawValues))
                .orderBy(QuerySort.asc("order_id"), QuerySort.asc("line_number"))
                .limitTo(totalLimit);
    }
}

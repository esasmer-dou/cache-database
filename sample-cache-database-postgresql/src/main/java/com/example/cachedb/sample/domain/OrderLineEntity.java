package com.example.cachedb.sample.domain;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheNamedQuery;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;

import java.math.BigDecimal;
import java.util.List;

@CacheEntity(table = "sample_order_lines", redisNamespace = "sample-order-lines")
public class OrderLineEntity {

    @CacheId(column = "line_id")
    public Long lineId;

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
    public BigDecimal unitPrice;

    @CacheColumn("line_total")
    public BigDecimal lineTotal;

    @CacheColumn("status")
    public String status;

    public OrderLineEntity() {
    }

    @CacheNamedQuery("linesForOrders")
    public static QuerySpec linesForOrdersQuery(List<Long> orderIds, int totalLimit) {
        List<Object> ids = orderIds == null
                ? List.of()
                : orderIds.stream().filter(id -> id != null).map(id -> (Object) id).toList();
        return QuerySpec.where(QueryFilter.in("order_id", ids))
                .orderBy(QuerySort.asc("order_id"), QuerySort.asc("line_number"))
                .limitTo(totalLimit);
    }
}

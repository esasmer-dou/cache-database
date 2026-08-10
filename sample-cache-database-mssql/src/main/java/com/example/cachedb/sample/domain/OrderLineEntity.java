package com.example.cachedb.sample.domain;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CachePartitionedIndex;

import java.math.BigDecimal;

@CacheEntity(table = "sample_order_lines", redisNamespace = "sample-order-lines")
@CachePartitionedIndex(partitionBy = "order_id", sortBy = "line_number")
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
}

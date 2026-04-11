package com.reactor.cachedb.examples.entity;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;

@CacheEntity(table = "cachedb_example_orders", redisNamespace = "orders")
public class OrderEntity {

    @CacheId(column = "id")
    public Long id;

    @CacheColumn("user_id")
    public Long userId;

    @CacheColumn("total_amount")
    public Double totalAmount;

    @CacheColumn("status")
    public String status;

    public OrderEntity() {
    }
}

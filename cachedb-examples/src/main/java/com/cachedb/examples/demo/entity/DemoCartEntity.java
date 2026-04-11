package com.reactor.cachedb.examples.demo.entity;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;

@CacheEntity(table = "cachedb_demo_carts", redisNamespace = "demo-carts")
public class DemoCartEntity {

    @CacheId(column = "id")
    public Long id;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheColumn("product_id")
    public Long productId;

    @CacheColumn("quantity")
    public Integer quantity;

    @CacheColumn("status")
    public String status;

    public DemoCartEntity() {
    }
}

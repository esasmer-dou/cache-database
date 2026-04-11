package com.reactor.cachedb.prodtest.entity;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;

@CacheEntity(table = "cachedb_prodtest_carts", redisNamespace = "prodtest_carts")
public class EcomCartEntity {

    @CacheId(column = "id")
    public Long id;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheColumn("sku")
    public String sku;

    @CacheColumn("quantity")
    public Integer quantity;

    @CacheColumn("state")
    public String state;
}

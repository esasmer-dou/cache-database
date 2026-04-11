package com.reactor.cachedb.prodtest.entity;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;

@CacheEntity(table = "cachedb_prodtest_products", redisNamespace = "prodtest_products")
public class EcomProductEntity {

    @CacheId(column = "id")
    public Long id;

    @CacheColumn("sku")
    public String sku;

    @CacheColumn("category")
    public String category;

    @CacheColumn("availability_status")
    public String availabilityStatus;

    @CacheColumn("price")
    public Double price;
}

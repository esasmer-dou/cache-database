package com.reactor.cachedb.examples.demo.entity;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;

@CacheEntity(table = "cachedb_demo_products", redisNamespace = "demo-products")
public class DemoProductEntity {

    @CacheId(column = "id")
    public Long id;

    @CacheColumn("sku")
    public String sku;

    @CacheColumn("category")
    public String category;

    @CacheColumn("price")
    public Double price;

    @CacheColumn("stock")
    public Integer stock;

    public DemoProductEntity() {
    }
}

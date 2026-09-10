package com.example.cachedb.sample.domain;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;

import java.math.BigDecimal;

@CacheEntity(table = "sample_products", redisNamespace = "sample-products")
public class ProductEntity {

    @CacheId(column = "product_id")
    public Long productId;

    @CacheColumn("sku")
    public String sku;

    @CacheColumn("product_name")
    public String productName;

    @CacheColumn("category")
    public String category;

    @CacheColumn("active_status")
    public String activeStatus;

    @CacheColumn("unit_price")
    public BigDecimal unitPrice;

    @CacheColumn("stock_quantity")
    public Integer stockQuantity;

    @CacheColumn("reserved_quantity")
    public Integer reservedQuantity;

    @CacheColumn("stock_status")
    public String stockStatus;

    @CacheColumn("updated_at")
    public Long updatedAt;

    public ProductEntity() {
    }
}

package com.example.cachedb.sample.domain;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheNamedQuery;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;

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
    public Double unitPrice;

    @CacheColumn("stock_quantity")
    public Integer stockQuantity;

    public ProductEntity() {
    }

    @CacheNamedQuery("activeProductsByCategory")
    public static QuerySpec activeProductsByCategoryQuery(String category, int limit) {
        return QuerySpec.where(QueryFilter.eq("category", category))
                .and(QueryFilter.eq("active_status", "ACTIVE"))
                .orderBy(QuerySort.asc("sku"))
                .limitTo(limit);
    }
}

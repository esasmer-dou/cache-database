package com.example.cachedb.sample.domain;

import com.example.cachedb.sample.readmodel.ProductReadModels;
import com.example.cachedb.sample.readmodel.ProductAvailability;
import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheNamedQuery;
import com.reactor.cachedb.annotations.CacheProjectionDefinition;
import com.reactor.cachedb.annotations.CacheRoute;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;

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

    @CacheProjectionDefinition("productAvailability")
    public static EntityProjection<ProductEntity, ProductAvailability, Long> productAvailabilityProjection() {
        return ProductReadModels.PRODUCT_AVAILABILITY_PROJECTION;
    }

    @CacheNamedQuery("activeProductsByCategory")
    @CacheRoute(value = "active-products-by-category", projection = "productAvailability", pageSize = 100,
            hotWindow = 5_000, maxColdReadSize = 500, memoryBudgetBytes = 16_777_216)
    public static QuerySpec activeProductsByCategoryQuery(String category, int limit) {
        return QuerySpec.where(QueryFilter.eq("category", category))
                .and(QueryFilter.eq("active_status", "ACTIVE"))
                .orderBy(QuerySort.asc("sku"))
                .limitTo(limit);
    }

    @CacheNamedQuery("activeProducts")
    @CacheRoute(value = "active-products", projection = "productAvailability", pageSize = 100,
            hotWindow = 10_000, maxColdReadSize = 500, memoryBudgetBytes = 33_554_432)
    public static QuerySpec activeProductsQuery(int limit) {
        return QuerySpec.where(QueryFilter.eq("active_status", "ACTIVE"))
                .orderBy(QuerySort.asc("sku"))
                .limitTo(limit);
    }

    @CacheNamedQuery("lowStockProducts")
    @CacheRoute(value = "low-stock-products", projection = "productAvailability", pageSize = 100,
            hotWindow = 2_000, maxColdReadSize = 500, memoryBudgetBytes = 8_388_608)
    public static QuerySpec lowStockProductsQuery(int limit) {
        return QuerySpec.where(QueryFilter.eq("stock_status", "LOW_STOCK"))
                .and(QueryFilter.eq("active_status", "ACTIVE"))
                .orderBy(QuerySort.desc("updated_at"), QuerySort.asc("sku"))
                .limitTo(limit);
    }

    @CacheNamedQuery("inactiveProductArchive")
    @CacheRoute(value = "inactive-product-archive", pageSize = 100, hotWindow = 100,
            maxColdReadSize = 500, memoryBudgetBytes = 0, strict = true)
    public static QuerySpec inactiveProductArchiveQuery(int limit) {
        return QuerySpec.anyOf(
                        QueryFilter.ne("active_status", "ACTIVE"),
                        QueryFilter.eq("stock_status", "OUT_OF_STOCK")
                )
                .orderBy(QuerySort.desc("updated_at"), QuerySort.desc("product_id"))
                .limitTo(limit);
    }
}

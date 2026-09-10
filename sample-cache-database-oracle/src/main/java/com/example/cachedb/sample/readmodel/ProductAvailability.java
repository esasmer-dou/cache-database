package com.example.cachedb.sample.readmodel;

import com.example.cachedb.sample.domain.ProductEntity;
import com.reactor.cachedb.annotations.CacheProjectionRecord;

import java.math.BigDecimal;

@CacheProjectionRecord(
        source = ProductEntity.class,
        id = "productId",
        name = "product-availability",
        rankedBy = {"stock_status", "updated_at"},
        factoryMethod = "fromEntity",
        refresh = CacheProjectionRecord.Refresh.ASYNC
)
public record ProductAvailability(
        Long productId,
        String sku,
        String productName,
        String category,
        String activeStatus,
        String stockStatus,
        Integer availableQuantity,
        BigDecimal unitPrice,
        Long updatedAt
) {
    public static ProductAvailability fromEntity(ProductEntity product) {
        int stock = product.stockQuantity == null ? 0 : product.stockQuantity;
        int reserved = product.reservedQuantity == null ? 0 : product.reservedQuantity;
        return new ProductAvailability(
                product.productId,
                product.sku,
                product.productName,
                product.category,
                product.activeStatus,
                product.stockStatus,
                Math.max(0, stock - reserved),
                product.unitPrice,
                product.updatedAt
        );
    }
}

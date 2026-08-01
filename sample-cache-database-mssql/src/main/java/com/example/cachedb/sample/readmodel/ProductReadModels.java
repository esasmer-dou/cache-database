package com.example.cachedb.sample.readmodel;

import com.example.cachedb.sample.domain.ProductEntity;
import com.reactor.cachedb.core.projection.EntityProjection;

public final class ProductReadModels {

    public static final EntityProjection<ProductEntity, ProductAvailability, Long> PRODUCT_AVAILABILITY_PROJECTION =
            EntityProjection.<ProductEntity, ProductAvailability, Long>of(
                    "product-availability",
                    ProductAvailabilityProjectionSchema.SCHEMA,
                    ProductAvailability::productId,
                    ProductReadModels::fromEntity
            ).rankedBy("stock_status", "updated_at").asyncRefresh();

    private ProductReadModels() {
    }

    public static ProductAvailability fromEntity(ProductEntity product) {
        return new ProductAvailability(
                product.productId,
                product.sku,
                product.productName,
                product.category,
                product.activeStatus,
                product.stockStatus,
                availableQuantity(product),
                product.unitPrice,
                product.updatedAt
        );
    }

    private static int availableQuantity(ProductEntity product) {
        int stock = product.stockQuantity == null ? 0 : product.stockQuantity;
        int reserved = product.reservedQuantity == null ? 0 : product.reservedQuantity;
        return Math.max(0, stock - reserved);
    }
}

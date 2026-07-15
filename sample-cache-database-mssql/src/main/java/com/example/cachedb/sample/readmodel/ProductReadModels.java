package com.example.cachedb.sample.readmodel;

import com.example.cachedb.sample.domain.ProductEntity;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.projection.ProjectionSchema;

import java.math.BigDecimal;

public final class ProductReadModels {

    private static final ProjectionSchema<ProductAvailability> PRODUCT_AVAILABILITY_SCHEMA =
            ProjectionSchema.<ProductAvailability>builder()
                    .longColumn("product_id", ProductAvailability::productId)
                    .stringColumn("sku", ProductAvailability::sku)
                    .stringColumn("product_name", ProductAvailability::productName)
                    .stringColumn("category", ProductAvailability::category)
                    .stringColumn("active_status", ProductAvailability::activeStatus)
                    .stringColumn("stock_status", ProductAvailability::stockStatus)
                    .integerColumn("available_quantity", ProductAvailability::availableQuantity)
                    .decimalColumn("unit_price", ProductAvailability::unitPrice)
                    .longColumn("updated_at", ProductAvailability::updatedAt)
                    .decodeWith(row -> new ProductAvailability(
                            row.longValue("product_id"),
                            row.string("sku"),
                            row.string("product_name"),
                            row.string("category"),
                            row.string("active_status"),
                            row.string("stock_status"),
                            row.integer("available_quantity"),
                            row.decimal("unit_price"),
                            row.longValue("updated_at")
                    ))
                    .build();

    public static final EntityProjection<ProductEntity, ProductAvailability, Long> PRODUCT_AVAILABILITY_PROJECTION =
            EntityProjection.<ProductEntity, ProductAvailability, Long>of(
                    "product-availability",
                    PRODUCT_AVAILABILITY_SCHEMA,
                    ProductAvailability::productId,
                    product -> new ProductAvailability(
                            product.productId,
                            product.sku,
                            product.productName,
                            product.category,
                            product.activeStatus,
                            product.stockStatus,
                            availableQuantity(product),
                            product.unitPrice,
                            product.updatedAt
                    )
            ).rankedBy("stock_status", "updated_at").asyncRefresh();

    private ProductReadModels() {
    }

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
    }

    private static int availableQuantity(ProductEntity product) {
        int stock = product.stockQuantity == null ? 0 : product.stockQuantity;
        int reserved = product.reservedQuantity == null ? 0 : product.reservedQuantity;
        return Math.max(0, stock - reserved);
    }
}

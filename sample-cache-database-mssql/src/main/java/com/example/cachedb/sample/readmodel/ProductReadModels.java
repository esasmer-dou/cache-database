package com.example.cachedb.sample.readmodel;

import com.example.cachedb.sample.domain.ProductEntity;
import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.projection.ProjectionCodec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProductReadModels {

    public static final EntityProjection<ProductEntity, ProductAvailability, Long> PRODUCT_AVAILABILITY_PROJECTION =
            EntityProjection.of(
                    "product-availability",
                    new ProjectionCodec<>() {
                        @Override
                        public String toRedisValue(ProductAvailability projection) {
                            LinkedHashMap<String, String> values = new LinkedHashMap<>();
                            values.put("product_id", stringValue(projection.productId()));
                            values.put("sku", projection.sku());
                            values.put("product_name", projection.productName());
                            values.put("category", projection.category());
                            values.put("active_status", projection.activeStatus());
                            values.put("stock_status", projection.stockStatus());
                            values.put("available_quantity", stringValue(projection.availableQuantity()));
                            values.put("unit_price", stringValue(projection.unitPrice()));
                            values.put("updated_at", stringValue(projection.updatedAt()));
                            return LengthPrefixedPayloadCodec.encode(values);
                        }

                        @Override
                        public ProductAvailability fromRedisValue(String encoded) {
                            Map<String, String> values = LengthPrefixedPayloadCodec.decode(encoded);
                            return new ProductAvailability(
                                    longValue(values.get("product_id")),
                                    values.get("sku"),
                                    values.get("product_name"),
                                    values.get("category"),
                                    values.get("active_status"),
                                    values.get("stock_status"),
                                    integerValue(values.get("available_quantity")),
                                    doubleValue(values.get("unit_price")),
                                    longValue(values.get("updated_at"))
                            );
                        }
                    },
                    ProductAvailability::productId,
                    List.of(
                            "product_id",
                            "sku",
                            "product_name",
                            "category",
                            "active_status",
                            "stock_status",
                            "available_quantity",
                            "unit_price",
                            "updated_at"
                    ),
                    projection -> columns(
                            "product_id", projection.productId(),
                            "sku", projection.sku(),
                            "product_name", projection.productName(),
                            "category", projection.category(),
                            "active_status", projection.activeStatus(),
                            "stock_status", projection.stockStatus(),
                            "available_quantity", projection.availableQuantity(),
                            "unit_price", projection.unitPrice(),
                            "updated_at", projection.updatedAt()
                    ),
                    (ProductEntity product) -> new ProductAvailability(
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
            Double unitPrice,
            Long updatedAt
    ) {
    }

    private static int availableQuantity(ProductEntity product) {
        int stock = product.stockQuantity == null ? 0 : product.stockQuantity;
        int reserved = product.reservedQuantity == null ? 0 : product.reservedQuantity;
        return Math.max(0, stock - reserved);
    }

    private static LinkedHashMap<String, Object> columns(Object... values) {
        LinkedHashMap<String, Object> columns = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            columns.put(String.valueOf(values[index]), values[index + 1]);
        }
        return columns;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longValue(String value) {
        return value == null ? null : Long.valueOf(value);
    }

    private static Integer integerValue(String value) {
        return value == null ? null : Integer.valueOf(value);
    }

    private static Double doubleValue(String value) {
        return value == null ? null : Double.valueOf(value);
    }
}

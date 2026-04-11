package com.reactor.cachedb.prodtest.entity;

import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.projection.ProjectionCodec;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EcomOrderReadModels {

    private static final String FIELD_SEPARATOR = "\u001F";
    private static final String NULL_SENTINEL = "~";

    public static final EntityProjection<EcomOrderEntity, OrderSummaryReadModel, Long> ORDER_SUMMARY_PROJECTION =
            EntityProjection.of(
                    "order-summary",
                    new ProjectionCodec<>() {
                        @Override
                        public String toRedisValue(OrderSummaryReadModel projection) {
                            return String.join(
                                    FIELD_SEPARATOR,
                                    encodeString(projection.id() == null ? null : String.valueOf(projection.id())),
                                    encodeString(projection.customerId() == null ? null : String.valueOf(projection.customerId())),
                                    encodeString(projection.sku()),
                                    encodeString(projection.quantity() == null ? null : String.valueOf(projection.quantity())),
                                    encodeString(projection.totalAmount() == null ? null : String.valueOf(projection.totalAmount())),
                                    encodeString(projection.status())
                            );
                        }

                        @Override
                        public OrderSummaryReadModel fromRedisValue(String encoded) {
                            String[] parts = encoded.split(FIELD_SEPARATOR, -1);
                            return new OrderSummaryReadModel(
                                    parseLong(parts[0]),
                                    parseLong(parts[1]),
                                    decodeString(parts[2]),
                                    parseInteger(parts[3]),
                                    parseDouble(parts[4]),
                                    decodeString(parts[5])
                            );
                        }
                    },
                    OrderSummaryReadModel::id,
                    List.of("id", "customer_id", "sku", "quantity", "total_amount", "status"),
                    projection -> projectionColumns(
                            "id", projection.id(),
                            "customer_id", projection.customerId(),
                            "sku", projection.sku(),
                            "quantity", projection.quantity(),
                            "total_amount", projection.totalAmount(),
                            "status", projection.status()
                    ),
                    (EcomOrderEntity order) -> new OrderSummaryReadModel(
                            order.id,
                            order.customerId,
                            order.sku,
                            order.quantity,
                            order.totalAmount,
                            order.status
                    )
            ).asyncRefresh();

    private EcomOrderReadModels() {
    }

    public record OrderSummaryReadModel(
            Long id,
            Long customerId,
            String sku,
            Integer quantity,
            Double totalAmount,
            String status
    ) {
    }

    private static String encodeString(String value) {
        if (value == null) {
            return NULL_SENTINEL;
        }
        return Base64.getUrlEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeString(String encoded) {
        if (NULL_SENTINEL.equals(encoded)) {
            return null;
        }
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private static Long parseLong(String encoded) {
        String decoded = decodeString(encoded);
        return decoded == null ? null : Long.parseLong(decoded);
    }

    private static Integer parseInteger(String encoded) {
        String decoded = decodeString(encoded);
        return decoded == null ? null : Integer.parseInt(decoded);
    }

    private static Double parseDouble(String encoded) {
        String decoded = decodeString(encoded);
        return decoded == null ? null : Double.parseDouble(decoded);
    }

    private static Map<String, Object> projectionColumns(Object... values) {
        LinkedHashMap<String, Object> columns = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            columns.put(String.valueOf(values[index]), values[index + 1]);
        }
        return columns;
    }
}

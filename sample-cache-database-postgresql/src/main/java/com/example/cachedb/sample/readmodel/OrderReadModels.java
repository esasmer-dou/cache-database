package com.example.cachedb.sample.readmodel;

import com.example.cachedb.sample.domain.OrderEntity;
import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.projection.ProjectionCodec;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OrderReadModels {

    public static final EntityProjection<OrderEntity, OrderSummary, Long> ORDER_SUMMARY_PROJECTION =
            EntityProjection.of(
                    "order-summary",
                    new ProjectionCodec<>() {
                        @Override
                        public String toRedisValue(OrderSummary projection) {
                            LinkedHashMap<String, String> values = new LinkedHashMap<>();
                            values.put("order_id", stringValue(projection.orderId()));
                            values.put("customer_id", stringValue(projection.customerId()));
                            values.put("order_date", stringValue(projection.orderDate()));
                            values.put("order_amount", stringValue(projection.orderAmount()));
                            values.put("currency_code", projection.currencyCode());
                            values.put("order_type", projection.orderType());
                            values.put("status", projection.status());
                            values.put("line_count", stringValue(projection.lineCount()));
                            values.put("priority_score", stringValue(projection.priorityScore()));
                            return LengthPrefixedPayloadCodec.encode(values);
                        }

                        @Override
                        public OrderSummary fromRedisValue(String encoded) {
                            Map<String, String> values = LengthPrefixedPayloadCodec.decode(encoded);
                            return new OrderSummary(
                                    longValue(values.get("order_id")),
                                    longValue(values.get("customer_id")),
                                    longValue(values.get("order_date")),
                                    decimalValue(values.get("order_amount")),
                                    values.get("currency_code"),
                                    values.get("order_type"),
                                    values.get("status"),
                                    integerValue(values.get("line_count")),
                                    doubleValue(values.get("priority_score"))
                            );
                        }
                    },
                    OrderSummary::orderId,
                    List.of(
                            "order_id",
                            "customer_id",
                            "order_date",
                            "order_amount",
                            "currency_code",
                            "order_type",
                            "status",
                            "line_count",
                            "priority_score"
                    ),
                    projection -> columns(
                            "order_id", projection.orderId(),
                            "customer_id", projection.customerId(),
                            "order_date", projection.orderDate(),
                            "order_amount", projection.orderAmount(),
                            "currency_code", projection.currencyCode(),
                            "order_type", projection.orderType(),
                            "status", projection.status(),
                            "line_count", projection.lineCount(),
                            "priority_score", projection.priorityScore()
                    ),
                    (OrderEntity order) -> new OrderSummary(
                            order.orderId,
                            order.customerId,
                            order.orderDate,
                            order.orderAmount,
                            order.currencyCode,
                            order.orderType,
                            order.status,
                            order.lineCount,
                            order.priorityScore
                    )
            ).rankedBy("order_date", "priority_score").asyncRefresh();

    private OrderReadModels() {
    }

    public record OrderSummary(
            Long orderId,
            Long customerId,
            Long orderDate,
            BigDecimal orderAmount,
            String currencyCode,
            String orderType,
            String status,
            Integer lineCount,
            Double priorityScore
    ) {
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

    private static BigDecimal decimalValue(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}

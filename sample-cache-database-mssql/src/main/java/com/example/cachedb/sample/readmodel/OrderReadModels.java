package com.example.cachedb.sample.readmodel;

import com.example.cachedb.sample.domain.OrderEntity;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.projection.ProjectionSchema;

import java.math.BigDecimal;

public final class OrderReadModels {

    private static final ProjectionSchema<OrderSummary> ORDER_SUMMARY_SCHEMA =
            ProjectionSchema.<OrderSummary>builder()
                    .longColumn("order_id", OrderSummary::orderId)
                    .longColumn("customer_id", OrderSummary::customerId)
                    .longColumn("order_date", OrderSummary::orderDate)
                    .decimalColumn("order_amount", OrderSummary::orderAmount)
                    .stringColumn("currency_code", OrderSummary::currencyCode)
                    .stringColumn("order_type", OrderSummary::orderType)
                    .stringColumn("status", OrderSummary::status)
                    .integerColumn("line_count", OrderSummary::lineCount)
                    .doubleColumn("priority_score", OrderSummary::priorityScore)
                    .decodeWith(row -> new OrderSummary(
                            row.longValue("order_id"),
                            row.longValue("customer_id"),
                            row.longValue("order_date"),
                            row.decimal("order_amount"),
                            row.string("currency_code"),
                            row.string("order_type"),
                            row.string("status"),
                            row.integer("line_count"),
                            row.doubleValue("priority_score")
                    ))
                    .build();

    public static final EntityProjection<OrderEntity, OrderSummary, Long> ORDER_SUMMARY_PROJECTION =
            EntityProjection.<OrderEntity, OrderSummary, Long>of(
                    "order-summary",
                    ORDER_SUMMARY_SCHEMA,
                    OrderSummary::orderId,
                    order -> new OrderSummary(
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
}

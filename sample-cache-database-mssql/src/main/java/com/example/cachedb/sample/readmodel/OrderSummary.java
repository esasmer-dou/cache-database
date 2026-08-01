package com.example.cachedb.sample.readmodel;

import com.example.cachedb.sample.domain.OrderEntity;
import com.reactor.cachedb.annotations.CacheProjectionRecord;

import java.math.BigDecimal;

@CacheProjectionRecord(
        source = OrderEntity.class,
        id = "orderId",
        name = "order-summary",
        rankedBy = {"order_date", "priority_score"},
        refresh = CacheProjectionRecord.Refresh.ASYNC
)
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

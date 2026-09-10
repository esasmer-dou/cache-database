package com.example.cachedb.sample.service;

import com.example.cachedb.sample.domain.OrderEntity;
import com.example.cachedb.sample.domain.ProductEntity;

import java.math.BigDecimal;

public final class SampleDomainPolicies {

    private SampleDomainPolicies() {
    }

    public static double orderPriority(OrderEntity order) {
        double amountScore = order.orderAmount == null
                ? 0.0
                : order.orderAmount.divide(BigDecimal.TEN).doubleValue();
        double expressScore = "EXPRESS".equals(order.orderType) ? 25.0 : 0.0;
        double statusScore = "PAID".equals(order.status) ? 10.0 : 0.0;
        return amountScore + expressScore + statusScore;
    }

    public static double shipmentRisk(String status) {
        if ("EXCEPTION".equals(status)) {
            return 95.0;
        }
        if ("DELAYED".equals(status)) {
            return 80.0;
        }
        if ("OUT_FOR_DELIVERY".equals(status)) {
            return 40.0;
        }
        return 20.0;
    }

    public static String stockStatus(ProductEntity product) {
        int stock = product.stockQuantity == null ? 0 : product.stockQuantity;
        int reserved = product.reservedQuantity == null ? 0 : product.reservedQuantity;
        int available = Math.max(0, stock - reserved);
        if (available == 0) {
            return "OUT_OF_STOCK";
        }
        return available <= 10 ? "LOW_STOCK" : "IN_STOCK";
    }
}

package com.example.cachedb.sample.readmodel;

import com.reactor.cachedb.annotations.CacheProjectionRecord;

import java.math.BigDecimal;

@CacheProjectionRecord
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

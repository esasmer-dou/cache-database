package com.example.cachedb.sample.readmodel;

import com.example.cachedb.sample.domain.TenantStock;
import com.reactor.cachedb.annotations.CacheProjectionRecord;

@CacheProjectionRecord(source = TenantStock.class, id = "stockId", name = "tenant-stock-card")
public record TenantStockCard(Long stockId, String warehouse, String status, Integer availableUnits) {}

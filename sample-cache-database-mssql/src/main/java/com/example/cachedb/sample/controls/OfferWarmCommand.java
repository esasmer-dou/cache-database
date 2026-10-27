package com.example.cachedb.sample.controls;

import com.reactor.cachedb.annotations.CacheJsonRecord;

@CacheJsonRecord
public record OfferWarmCommand(String supplier, int maxRows) {
    public OfferWarmCommand {
        if (supplier == null || supplier.isBlank() || supplier.length() > 32 || maxRows < 1 || maxRows > 100)
            throw new IllegalArgumentException("A supplier and maxRows between 1 and 100 are required");
    }
}

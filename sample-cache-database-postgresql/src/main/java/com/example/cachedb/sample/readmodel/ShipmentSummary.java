package com.example.cachedb.sample.readmodel;

import com.example.cachedb.sample.domain.ShipmentEntity;
import com.reactor.cachedb.annotations.CacheProjectionRecord;

@CacheProjectionRecord(
        source = ShipmentEntity.class,
        id = "shipmentId",
        name = "shipment-summary",
        rankedBy = {"risk_score", "updated_at"},
        refresh = CacheProjectionRecord.Refresh.ASYNC
)
public record ShipmentSummary(
        Long shipmentId,
        Long customerId,
        String trackingNumber,
        String carrierCode,
        String shipmentStatus,
        String currentCity,
        Long promisedAt,
        Long updatedAt,
        Double riskScore
) {
}

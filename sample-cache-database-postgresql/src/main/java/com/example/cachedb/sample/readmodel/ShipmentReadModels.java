package com.example.cachedb.sample.readmodel;

import com.example.cachedb.sample.domain.ShipmentEntity;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.projection.ProjectionSchema;

public final class ShipmentReadModels {

    private static final ProjectionSchema<ShipmentSummary> SHIPMENT_SUMMARY_SCHEMA =
            ProjectionSchema.<ShipmentSummary>builder()
                    .longColumn("shipment_id", ShipmentSummary::shipmentId)
                    .longColumn("customer_id", ShipmentSummary::customerId)
                    .stringColumn("tracking_number", ShipmentSummary::trackingNumber)
                    .stringColumn("carrier_code", ShipmentSummary::carrierCode)
                    .stringColumn("shipment_status", ShipmentSummary::shipmentStatus)
                    .stringColumn("current_city", ShipmentSummary::currentCity)
                    .longColumn("promised_at", ShipmentSummary::promisedAt)
                    .longColumn("updated_at", ShipmentSummary::updatedAt)
                    .doubleColumn("risk_score", ShipmentSummary::riskScore)
                    .decodeWith(row -> new ShipmentSummary(
                            row.longValue("shipment_id"),
                            row.longValue("customer_id"),
                            row.string("tracking_number"),
                            row.string("carrier_code"),
                            row.string("shipment_status"),
                            row.string("current_city"),
                            row.longValue("promised_at"),
                            row.longValue("updated_at"),
                            row.doubleValue("risk_score")
                    ))
                    .build();

    public static final EntityProjection<ShipmentEntity, ShipmentSummary, Long> SHIPMENT_SUMMARY_PROJECTION =
            EntityProjection.<ShipmentEntity, ShipmentSummary, Long>of(
                    "shipment-summary",
                    SHIPMENT_SUMMARY_SCHEMA,
                    ShipmentSummary::shipmentId,
                    shipment -> new ShipmentSummary(
                            shipment.shipmentId,
                            shipment.customerId,
                            shipment.trackingNumber,
                            shipment.carrierCode,
                            shipment.shipmentStatus,
                            shipment.currentCity,
                            shipment.promisedAt,
                            shipment.updatedAt,
                            shipment.riskScore
                    )
            ).rankedBy("risk_score", "updated_at").asyncRefresh();

    private ShipmentReadModels() {
    }

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
}

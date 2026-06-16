package com.example.cachedb.sample.readmodel;

import com.example.cachedb.sample.domain.ShipmentEntity;
import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.projection.ProjectionCodec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ShipmentReadModels {

    public static final EntityProjection<ShipmentEntity, ShipmentSummary, Long> SHIPMENT_SUMMARY_PROJECTION =
            EntityProjection.of(
                    "shipment-summary",
                    new ProjectionCodec<>() {
                        @Override
                        public String toRedisValue(ShipmentSummary projection) {
                            LinkedHashMap<String, String> values = new LinkedHashMap<>();
                            values.put("shipment_id", stringValue(projection.shipmentId()));
                            values.put("customer_id", stringValue(projection.customerId()));
                            values.put("tracking_number", projection.trackingNumber());
                            values.put("carrier_code", projection.carrierCode());
                            values.put("shipment_status", projection.shipmentStatus());
                            values.put("current_city", projection.currentCity());
                            values.put("promised_at", stringValue(projection.promisedAt()));
                            values.put("updated_at", stringValue(projection.updatedAt()));
                            values.put("risk_score", stringValue(projection.riskScore()));
                            return LengthPrefixedPayloadCodec.encode(values);
                        }

                        @Override
                        public ShipmentSummary fromRedisValue(String encoded) {
                            Map<String, String> values = LengthPrefixedPayloadCodec.decode(encoded);
                            return new ShipmentSummary(
                                    longValue(values.get("shipment_id")),
                                    longValue(values.get("customer_id")),
                                    values.get("tracking_number"),
                                    values.get("carrier_code"),
                                    values.get("shipment_status"),
                                    values.get("current_city"),
                                    longValue(values.get("promised_at")),
                                    longValue(values.get("updated_at")),
                                    doubleValue(values.get("risk_score"))
                            );
                        }
                    },
                    ShipmentSummary::shipmentId,
                    List.of(
                            "shipment_id",
                            "customer_id",
                            "tracking_number",
                            "carrier_code",
                            "shipment_status",
                            "current_city",
                            "promised_at",
                            "updated_at",
                            "risk_score"
                    ),
                    projection -> columns(
                            "shipment_id", projection.shipmentId(),
                            "customer_id", projection.customerId(),
                            "tracking_number", projection.trackingNumber(),
                            "carrier_code", projection.carrierCode(),
                            "shipment_status", projection.shipmentStatus(),
                            "current_city", projection.currentCity(),
                            "promised_at", projection.promisedAt(),
                            "updated_at", projection.updatedAt(),
                            "risk_score", projection.riskScore()
                    ),
                    (ShipmentEntity shipment) -> new ShipmentSummary(
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

    private static Double doubleValue(String value) {
        return value == null ? null : Double.valueOf(value);
    }
}

package com.example.cachedb.sample.domain;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheRelation;

import java.util.List;

@CacheEntity(
        table = "sample_shipments",
        redisNamespace = "sample-shipments"
)
public class ShipmentEntity {

    @CacheId(column = "shipment_id")
    public Long shipmentId;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheColumn("tracking_number")
    public String trackingNumber;

    @CacheColumn("carrier_code")
    public String carrierCode;

    @CacheColumn("shipment_status")
    public String shipmentStatus;

    @CacheColumn("current_city")
    public String currentCity;

    @CacheColumn("promised_at")
    public Long promisedAt;

    @CacheColumn("updated_at")
    public Long updatedAt;

    @CacheColumn("risk_score")
    public Double riskScore;

    @CacheRelation(
            target = ShipmentEventEntity.class,
            mappedBy = "shipmentId",
            kind = CacheRelation.RelationKind.ONE_TO_MANY,
            batchLoadOnly = true,
            maxRowsPerParent = 20,
            parentBatchSize = 16,
            orderBy = {"eventTime DESC", "eventId DESC"}
    )
    public List<ShipmentEventEntity> events;

    public ShipmentEntity() {
    }
}

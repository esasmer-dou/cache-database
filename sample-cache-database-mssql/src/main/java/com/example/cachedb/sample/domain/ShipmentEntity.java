package com.example.cachedb.sample.domain;

import com.example.cachedb.sample.readmodel.ShipmentReadModels;
import com.example.cachedb.sample.relation.ShipmentEventsRelationBatchLoader;
import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheFetchPreset;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheNamedQuery;
import com.reactor.cachedb.annotations.CacheProjectionDefinition;
import com.reactor.cachedb.annotations.CacheRelation;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;

import java.util.List;

@CacheEntity(
        table = "sample_shipments",
        redisNamespace = "sample-shipments",
        relationLoader = ShipmentEventsRelationBatchLoader.class
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
            targetEntity = "ShipmentEventEntity",
            mappedBy = "shipmentId",
            kind = CacheRelation.RelationKind.ONE_TO_MANY,
            batchLoadOnly = true
    )
    public List<ShipmentEventEntity> events;

    public ShipmentEntity() {
    }

    @CacheProjectionDefinition("shipmentSummary")
    public static EntityProjection<ShipmentEntity, ShipmentReadModels.ShipmentSummary, Long> shipmentSummaryProjection() {
        return ShipmentReadModels.SHIPMENT_SUMMARY_PROJECTION;
    }

    @CacheNamedQuery("activeShipments")
    public static QuerySpec activeShipmentsQuery(int limit) {
        return QuerySpec.where(QueryFilter.in("shipment_status", List.<Object>of(
                        "IN_TRANSIT",
                        "OUT_FOR_DELIVERY",
                        "DELAYED",
                        "EXCEPTION"
                )))
                .orderBy(QuerySort.desc("risk_score"), QuerySort.desc("updated_at"))
                .limitTo(limit);
    }

    @CacheNamedQuery("customerShipments")
    public static QuerySpec customerShipmentsQuery(long customerId, int limit) {
        return QuerySpec.where(QueryFilter.eq("customer_id", customerId))
                .orderBy(QuerySort.desc("updated_at"), QuerySort.desc("shipment_id"))
                .limitTo(limit);
    }

    @CacheNamedQuery("shipmentExceptions")
    public static QuerySpec shipmentExceptionsQuery(int limit) {
        return QuerySpec.where(QueryFilter.in("shipment_status", List.<Object>of("DELAYED", "EXCEPTION")))
                .orderBy(QuerySort.desc("risk_score"), QuerySort.desc("updated_at"))
                .limitTo(limit);
    }

    @CacheFetchPreset("eventPreview")
    public static FetchPlan eventPreviewFetchPlan(int eventLimit) {
        return FetchPlan.of("events").withRelationLimit("events", Math.max(1, eventLimit));
    }
}

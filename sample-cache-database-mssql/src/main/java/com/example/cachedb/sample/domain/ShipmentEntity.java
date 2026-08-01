package com.example.cachedb.sample.domain;

import com.example.cachedb.sample.readmodel.ShipmentSummary;
import com.example.cachedb.sample.readmodel.ShipmentSummaryProjection;
import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheFetchPreset;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheNamedQuery;
import com.reactor.cachedb.annotations.CacheProjectionDefinition;
import com.reactor.cachedb.annotations.CacheRelation;
import com.reactor.cachedb.annotations.CacheRoute;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;

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

    @CacheProjectionDefinition("shipmentSummary")
    public static EntityProjection<ShipmentEntity, ShipmentSummary, Long> shipmentSummaryProjection() {
        return ShipmentSummaryProjection.PROJECTION;
    }

    @CacheNamedQuery("activeShipments")
    @CacheRoute(value = "active-shipments", projection = "shipmentSummary", pageSize = 100,
            hotWindow = 10_000, maxColdReadSize = 500, memoryBudgetBytes = 33_554_432)
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
    @CacheRoute(value = "customer-shipments", projection = "shipmentSummary", pageSize = 100,
            hotWindow = 1_000, maxColdReadSize = 500, memoryBudgetBytes = 16_777_216)
    public static QuerySpec customerShipmentsQuery(long customerId, int limit) {
        return QuerySpec.where(QueryFilter.eq("customer_id", customerId))
                .orderBy(QuerySort.desc("updated_at"), QuerySort.desc("shipment_id"))
                .limitTo(limit);
    }

    @CacheNamedQuery("shipmentExceptions")
    @CacheRoute(value = "shipment-exceptions", projection = "shipmentSummary", pageSize = 100,
            hotWindow = 2_000, maxColdReadSize = 500, memoryBudgetBytes = 8_388_608)
    public static QuerySpec shipmentExceptionsQuery(int limit) {
        return QuerySpec.where(QueryFilter.in("shipment_status", List.<Object>of("DELAYED", "EXCEPTION")))
                .orderBy(QuerySort.desc("risk_score"), QuerySort.desc("updated_at"))
                .limitTo(limit);
    }

    @CacheNamedQuery("deliveredShipmentArchive")
    @CacheRoute(value = "delivered-shipment-archive", pageSize = 100, hotWindow = 100,
            maxColdReadSize = 500, memoryBudgetBytes = 0, strict = true)
    public static QuerySpec deliveredShipmentArchiveQuery(long customerId, int limit) {
        return QuerySpec.where(QueryFilter.eq("customer_id", customerId))
                .and(QueryFilter.eq("shipment_status", "DELIVERED"))
                .orderBy(QuerySort.desc("updated_at"), QuerySort.desc("shipment_id"))
                .limitTo(limit);
    }

    @CacheFetchPreset("eventPreview")
    public static FetchPlan eventPreviewFetchPlan(int eventLimit) {
        return FetchPlan.of("events").withRelationLimit("events", Math.max(1, eventLimit));
    }
}

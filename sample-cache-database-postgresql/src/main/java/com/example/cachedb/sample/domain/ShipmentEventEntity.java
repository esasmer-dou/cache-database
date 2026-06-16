package com.example.cachedb.sample.domain;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CacheNamedQuery;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;

import java.util.List;

@CacheEntity(table = "sample_shipment_events", redisNamespace = "sample-shipment-events")
public class ShipmentEventEntity {

    @CacheId(column = "event_id")
    public Long eventId;

    @CacheColumn("shipment_id")
    public Long shipmentId;

    @CacheColumn("event_type")
    public String eventType;

    @CacheColumn("event_city")
    public String eventCity;

    @CacheColumn("event_time")
    public Long eventTime;

    @CacheColumn("severity")
    public String severity;

    @CacheColumn("description")
    public String description;

    public ShipmentEventEntity() {
    }

    @CacheNamedQuery("eventsForShipment")
    public static QuerySpec eventsForShipmentQuery(long shipmentId, int limit) {
        return QuerySpec.where(QueryFilter.eq("shipment_id", shipmentId))
                .orderBy(QuerySort.desc("event_time"), QuerySort.desc("event_id"))
                .limitTo(limit);
    }

    @CacheNamedQuery("eventsForShipments")
    public static QuerySpec eventsForShipmentsQuery(List<Long> shipmentIds, int totalLimit) {
        List<Object> ids = shipmentIds == null
                ? List.of()
                : shipmentIds.stream().filter(id -> id != null).map(id -> (Object) id).toList();
        return QuerySpec.where(QueryFilter.in("shipment_id", ids))
                .orderBy(QuerySort.asc("shipment_id"), QuerySort.desc("event_time"))
                .limitTo(totalLimit);
    }
}

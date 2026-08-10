package com.example.cachedb.sample.domain;

import com.reactor.cachedb.annotations.CacheColumn;
import com.reactor.cachedb.annotations.CacheEntity;
import com.reactor.cachedb.annotations.CacheId;
import com.reactor.cachedb.annotations.CachePartitionedIndex;

@CacheEntity(table = "sample_shipment_events", redisNamespace = "sample-shipment-events")
@CachePartitionedIndex(partitionBy = "shipment_id", sortBy = "event_time")
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
}

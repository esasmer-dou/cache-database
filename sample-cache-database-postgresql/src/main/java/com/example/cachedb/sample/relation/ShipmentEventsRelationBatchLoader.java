package com.example.cachedb.sample.relation;

import com.example.cachedb.sample.domain.ShipmentEntity;
import com.example.cachedb.sample.domain.ShipmentEventEntity;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.relation.RelationBatchContext;
import com.reactor.cachedb.core.relation.RelationBatchLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ShipmentEventsRelationBatchLoader implements RelationBatchLoader<ShipmentEntity> {

    private static final String RELATION_NAME = "events";
    private static final int MAX_ROWS_PER_BATCH = 100;
    private static final int MAX_PARENT_IDS_PER_BATCH = 5;

    private final EntityRepository<ShipmentEventEntity, Long> eventRepository;

    public ShipmentEventsRelationBatchLoader(EntityRepository<ShipmentEventEntity, Long> eventRepository) {
        this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository");
    }

    @Override
    public void preload(List<ShipmentEntity> entities, RelationBatchContext context) {
        if (entities.isEmpty() || !context.fetchPlan().includes(RELATION_NAME)) {
            return;
        }
        int relationLimit = Math.max(1, Math.min(context.relationLimit(RELATION_NAME), 20));
        LinkedHashMap<Long, ShipmentEntity> shipmentsById = new LinkedHashMap<>();
        LinkedHashMap<Long, List<ShipmentEventEntity>> eventsByShipmentId = new LinkedHashMap<>();
        for (ShipmentEntity shipment : entities) {
            if (shipment == null || shipment.shipmentId == null) {
                continue;
            }
            shipmentsById.put(shipment.shipmentId, shipment);
            eventsByShipmentId.put(shipment.shipmentId, new ArrayList<>());
        }
        int parentBatchSize = Math.max(
                1,
                Math.min(MAX_PARENT_IDS_PER_BATCH, MAX_ROWS_PER_BATCH / relationLimit)
        );
        List<Long> shipmentIds = new ArrayList<>(shipmentsById.keySet());
        for (int start = 0; start < shipmentIds.size(); start += parentBatchSize) {
            List<Long> chunk = shipmentIds.subList(start, Math.min(shipmentIds.size(), start + parentBatchSize));
            List<Object> rawIds = new ArrayList<>(chunk);
            List<ShipmentEventEntity> events = eventRepository.query(
                    QuerySpec.where(QueryFilter.in("shipment_id", rawIds))
                            .orderBy(QuerySort.asc("shipment_id"), QuerySort.desc("event_time"), QuerySort.desc("event_id"))
                            .limitTo(chunk.size() * relationLimit)
            );
            for (ShipmentEventEntity event : events) {
                if (event != null && event.shipmentId != null) {
                    List<ShipmentEventEntity> bucket = eventsByShipmentId.get(event.shipmentId);
                    if (bucket != null && bucket.size() < relationLimit) {
                        bucket.add(event);
                    }
                }
            }
        }
        for (Map.Entry<Long, ShipmentEntity> entry : shipmentsById.entrySet()) {
            entry.getValue().events = List.copyOf(eventsByShipmentId.getOrDefault(entry.getKey(), List.of()));
        }
    }
}

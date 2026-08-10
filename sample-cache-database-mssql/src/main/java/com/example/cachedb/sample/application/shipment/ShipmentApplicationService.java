package com.example.cachedb.sample.application.shipment;

import com.example.cachedb.sample.application.SampleHotLookups;
import com.example.cachedb.sample.domain.ShipmentEntity;
import com.example.cachedb.sample.domain.ShipmentEventEntity;
import com.example.cachedb.sample.readmodel.ShipmentSummary;
import com.example.cachedb.sample.repository.ShipmentEventRepository;
import com.example.cachedb.sample.repository.ShipmentRepository;
import com.example.cachedb.sample.service.DurableReferenceGuard;
import com.example.cachedb.sample.service.SampleDomainPolicies;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.repository.WindowRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public final class ShipmentApplicationService {

    private final ShipmentRepository shipments;
    private final ShipmentEventRepository shipmentEvents;
    private final DurableReferenceGuard references;
    private final Clock clock;

    public ShipmentApplicationService(
            ShipmentRepository shipments,
            ShipmentEventRepository shipmentEvents,
            DurableReferenceGuard references,
            Clock clock
    ) {
        this.shipments = shipments;
        this.shipmentEvents = shipmentEvents;
        this.references = references;
        this.clock = clock;
    }

    public List<ShipmentSummary> active(int limit) {
        return shipments.active(limit).completeItems();
    }

    public List<ShipmentSummary> exceptions(int limit) {
        return shipments.exceptions(limit).completeItems();
    }

    public List<ShipmentSummary> forCustomer(long customerId, int limit) {
        return shipments.forCustomer(customerId, WindowRequest.first(limit)).completeItems();
    }

    public ShipmentEntity detail(long shipmentId, int eventPreview) {
        return SampleHotLookups.require("Shipment", shipmentId, shipments.detail(shipmentId, eventPreview));
    }

    public List<ShipmentEventEntity> events(long shipmentId, int limit) {
        return shipmentEvents.forShipment(shipmentId, WindowRequest.first(limit)).completeItems();
    }

    public List<ShipmentSummary> deliveredArchive(long customerId, int limit) {
        return shipments.deliveredArchive(customerId, WindowRequest.first(limit)).items();
    }

    public WriteReceipt<ShipmentEntity, Long> create(CreateShipment command) {
        DurableReferenceGuard.DurableReference customer = references.requireCustomer(command.customerId());
        long now = Instant.now(clock).getEpochSecond();
        ShipmentEntity entity = new ShipmentEntity();
        entity.shipmentId = command.shipmentId();
        entity.customerId = command.customerId();
        entity.trackingNumber = defaultText(command.trackingNumber(), "TRK-" + command.shipmentId());
        entity.carrierCode = defaultText(command.carrierCode(), "DHL");
        entity.shipmentStatus = defaultText(command.shipmentStatus(), "IN_TRANSIT");
        entity.currentCity = defaultText(command.currentCity(), "Istanbul");
        entity.promisedAt = command.promisedAt() == null ? now + 86_400L : command.promisedAt();
        entity.updatedAt = now;
        entity.riskScore = SampleDomainPolicies.shipmentRisk(entity.shipmentStatus);
        return customer.save(shipments, entity);
    }

    public WriteReceipt<ShipmentEventEntity, Long> addEvent(long shipmentId, CreateShipmentEvent command) {
        DurableReferenceGuard.DurableReference shipment = references.requireShipment(shipmentId);
        ShipmentEventEntity entity = new ShipmentEventEntity();
        entity.eventId = command.eventId();
        entity.shipmentId = shipmentId;
        entity.eventType = defaultText(command.eventType(), "HUB_SCAN");
        entity.eventCity = defaultText(command.eventCity(), "Istanbul");
        entity.eventTime = command.eventTime() == null ? Instant.now(clock).getEpochSecond() : command.eventTime();
        entity.severity = defaultText(command.severity(), "INFO");
        entity.description = defaultText(command.description(), "Manual shipment event");
        return shipment.save(shipmentEvents, entity);
    }

    public WriteReceipt<ShipmentEntity, Long> updateStatus(long shipmentId, UpdateShipmentStatus command) {
        return shipments.updateHot(shipmentId, entity -> {
            entity.shipmentStatus = command.shipmentStatus();
            entity.currentCity = command.currentCity() == null ? entity.currentCity : command.currentCity();
            entity.updatedAt = Instant.now(clock).getEpochSecond();
            entity.riskScore = SampleDomainPolicies.shipmentRisk(entity.shipmentStatus);
            return entity;
        });
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record CreateShipment(
            Long shipmentId,
            Long customerId,
            String trackingNumber,
            String carrierCode,
            String shipmentStatus,
            String currentCity,
            Long promisedAt
    ) {
    }

    public record CreateShipmentEvent(
            Long eventId,
            String eventType,
            String eventCity,
            Long eventTime,
            String severity,
            String description
    ) {
    }

    public record UpdateShipmentStatus(String shipmentStatus, String currentCity) {
    }
}

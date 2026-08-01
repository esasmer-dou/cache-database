package com.example.cachedb.sample.application.shipment;

import com.example.cachedb.sample.application.SampleEntityNotFoundException;
import com.example.cachedb.sample.domain.GeneratedCacheModule;
import com.example.cachedb.sample.domain.ShipmentEntity;
import com.example.cachedb.sample.domain.ShipmentEventEntity;
import com.example.cachedb.sample.readmodel.ShipmentSummary;
import com.example.cachedb.sample.readmodel.ShipmentSummaryProjection;
import com.example.cachedb.sample.service.DurableReferenceGuard;
import com.example.cachedb.sample.service.SampleDomainPolicies;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.page.VersionedEntity;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public final class ShipmentApplicationService {

    private final GeneratedCacheModule.Scope domain;
    private final DurableReferenceGuard references;
    private final Clock clock;

    public ShipmentApplicationService(
            GeneratedCacheModule.Scope domain,
            DurableReferenceGuard references,
            Clock clock
    ) {
        this.domain = domain;
        this.references = references;
        this.clock = clock;
    }

    public List<ShipmentSummary> active(int limit) {
        return domain.shipments().queries().activeShipmentsProjection(limit);
    }

    public List<ShipmentSummary> exceptions(int limit) {
        return domain.shipments().queries().shipmentExceptionsProjection(limit);
    }

    public List<ShipmentSummary> forCustomer(long customerId, int limit) {
        return domain.shipments().queries().customerShipmentsProjection(customerId, limit);
    }

    public ShipmentEntity detail(long shipmentId, int eventPreview) {
        return domain.shipments().fetches().eventPreview(eventPreview).findById(shipmentId)
                .orElseThrow(() -> new SampleEntityNotFoundException("Shipment", shipmentId));
    }

    public List<ShipmentEventEntity> events(long shipmentId, int limit) {
        return domain.shipmentEvents().queries().eventsForShipment(shipmentId, limit);
    }

    public List<ShipmentSummary> deliveredArchive(long customerId, int limit) {
        return domain.shipments().queries().deliveredShipmentArchiveSource(customerId, limit).stream()
                .map(ShipmentSummaryProjection::fromEntity)
                .toList();
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
        return customer.save(domain.shipments().repository(), entity);
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
        return shipment.save(domain.shipmentEvents().repository(), entity);
    }

    public WriteReceipt<ShipmentEntity, Long> updateStatus(long shipmentId, UpdateShipmentStatus command) {
        VersionedEntity<ShipmentEntity> current = domain.shipments().findVersionedById(shipmentId)
                .orElseThrow(() -> new SampleEntityNotFoundException("Shipment", shipmentId));
        ShipmentEntity entity = current.entity();
        entity.shipmentStatus = command.shipmentStatus();
        entity.currentCity = command.currentCity() == null ? entity.currentCity : command.currentCity();
        entity.updatedAt = Instant.now(clock).getEpochSecond();
        entity.riskScore = SampleDomainPolicies.shipmentRisk(entity.shipmentStatus);
        return domain.shipments().save(entity, current.version());
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

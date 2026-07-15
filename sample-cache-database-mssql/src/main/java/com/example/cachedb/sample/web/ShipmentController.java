package com.example.cachedb.sample.web;

import com.example.cachedb.sample.domain.GeneratedCacheModule;
import com.example.cachedb.sample.domain.ShipmentEntity;
import com.example.cachedb.sample.domain.ShipmentEventEntity;
import com.example.cachedb.sample.readmodel.ShipmentReadModels;
import com.example.cachedb.sample.service.DurableReferenceGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {

    private static final String ARCHIVE_SQL = """
            SELECT shipment_id, customer_id, tracking_number, carrier_code, shipment_status,
                   current_city, promised_at, updated_at, risk_score
            FROM sample_shipments
            WHERE customer_id = ? AND shipment_status = 'DELIVERED'
            ORDER BY updated_at DESC, shipment_id DESC
            OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY
            """;

    private final GeneratedCacheModule.Scope domain;
    private final JdbcTemplate jdbcTemplate;
    private final DurableReferenceGuard durableReferenceGuard;

    public ShipmentController(
            GeneratedCacheModule.Scope domain,
            JdbcTemplate jdbcTemplate,
            DurableReferenceGuard durableReferenceGuard
    ) {
        this.domain = domain;
        this.jdbcTemplate = jdbcTemplate;
        this.durableReferenceGuard = durableReferenceGuard;
    }

    @GetMapping("/active")
    public List<ShipmentReadModels.ShipmentSummary> active(@RequestParam(defaultValue = "50") int limit) {
        int safeLimit = ApiLimits.requireInRange("limit", limit, 1, 1_000);
        return domain.shipments().projections().shipmentSummary().query(
                domain.shipments().queries().activeShipmentsQuery(safeLimit)
        );
    }

    @GetMapping("/exceptions")
    public List<ShipmentReadModels.ShipmentSummary> exceptions(@RequestParam(defaultValue = "25") int limit) {
        int safeLimit = ApiLimits.requireInRange("limit", limit, 1, 1_000);
        return domain.shipments().projections().shipmentSummary().query(
                domain.shipments().queries().shipmentExceptionsQuery(safeLimit)
        );
    }

    @GetMapping("/customer/{customerId}")
    public List<ShipmentReadModels.ShipmentSummary> customerShipments(
            @PathVariable long customerId,
            @RequestParam(defaultValue = "25") int limit
    ) {
        int safeLimit = ApiLimits.requireInRange("limit", limit, 1, 1_000);
        return domain.shipments().projections().shipmentSummary().query(
                domain.shipments().queries().customerShipmentsQuery(customerId, safeLimit)
        );
    }

    @GetMapping("/{shipmentId}")
    public ShipmentEntity detail(
            @PathVariable long shipmentId,
            @RequestParam(defaultValue = "5") int eventPreview
    ) {
        return domain.shipments().fetches()
                .eventPreview(ApiLimits.requireInRange("eventPreview", eventPreview, 1, 20))
                .findById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment not found in active set: " + shipmentId));
    }

    @GetMapping("/{shipmentId}/events")
    public List<ShipmentEventEntity> events(
            @PathVariable long shipmentId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return domain.shipmentEvents().queries()
                .eventsForShipment(shipmentId, ApiLimits.requireInRange("limit", limit, 1, 100));
    }

    @GetMapping("/archive")
    public List<ShipmentReadModels.ShipmentSummary> deliveredArchive(
            @RequestParam long customerId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return jdbcTemplate.query(
                ARCHIVE_SQL,
                (resultSet, rowNumber) -> new ShipmentReadModels.ShipmentSummary(
                        resultSet.getLong("shipment_id"),
                        resultSet.getLong("customer_id"),
                        resultSet.getString("tracking_number"),
                        resultSet.getString("carrier_code"),
                        resultSet.getString("shipment_status"),
                        resultSet.getString("current_city"),
                        resultSet.getLong("promised_at"),
                        resultSet.getLong("updated_at"),
                        resultSet.getDouble("risk_score")
                ),
                customerId,
                ApiLimits.requireInRange("limit", limit, 1, 500)
        );
    }

    @PostMapping
    public ResponseEntity<WriteAccepted<ShipmentEntity>> create(
            @Valid @RequestBody CreateShipmentRequest request
    ) {
        durableReferenceGuard.requireCustomer(request.customerId());
        long now = Instant.now().getEpochSecond();
        ShipmentEntity shipment = new ShipmentEntity();
        shipment.shipmentId = request.shipmentId();
        shipment.customerId = request.customerId();
        shipment.trackingNumber = request.trackingNumber() == null ? "TRK-" + request.shipmentId() : request.trackingNumber();
        shipment.carrierCode = request.carrierCode() == null ? "DHL" : request.carrierCode();
        shipment.shipmentStatus = request.shipmentStatus() == null ? "IN_TRANSIT" : request.shipmentStatus();
        shipment.currentCity = request.currentCity() == null ? "Istanbul" : request.currentCity();
        shipment.promisedAt = request.promisedAt() == null ? now + 86_400L : request.promisedAt();
        shipment.updatedAt = now;
        shipment.riskScore = riskScore(shipment.shipmentStatus);
        ShipmentEntity saved = domain.shipments().save(shipment);
        return ResponseEntity.accepted().body(WriteAccepted.of("CREATE", "ShipmentEntity", saved.shipmentId, saved));
    }

    @PostMapping("/{shipmentId}/events")
    public ResponseEntity<WriteAccepted<ShipmentEventEntity>> addEvent(
            @PathVariable long shipmentId,
            @Valid @RequestBody CreateShipmentEventRequest request
    ) {
        durableReferenceGuard.requireShipment(shipmentId);
        long now = Instant.now().getEpochSecond();
        ShipmentEventEntity event = new ShipmentEventEntity();
        event.eventId = request.eventId();
        event.shipmentId = shipmentId;
        event.eventType = request.eventType() == null ? "HUB_SCAN" : request.eventType();
        event.eventCity = request.eventCity() == null ? "Istanbul" : request.eventCity();
        event.eventTime = request.eventTime() == null ? now : request.eventTime();
        event.severity = request.severity() == null ? "INFO" : request.severity();
        event.description = request.description() == null ? "Manual shipment event" : request.description();
        ShipmentEventEntity saved = domain.shipmentEvents().save(event);
        return ResponseEntity.accepted().body(WriteAccepted.of("CREATE", "ShipmentEventEntity", saved.eventId, saved));
    }

    @PatchMapping("/{shipmentId}/status")
    public ResponseEntity<WriteAccepted<ShipmentEntity>> updateStatus(
            @PathVariable long shipmentId,
            @Valid @RequestBody UpdateShipmentStatusRequest request
    ) {
        ShipmentEntity shipment = domain.shipments().findById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment not found in active set: " + shipmentId));
        shipment.shipmentStatus = request.shipmentStatus();
        shipment.currentCity = request.currentCity() == null ? shipment.currentCity : request.currentCity();
        shipment.updatedAt = Instant.now().getEpochSecond();
        shipment.riskScore = riskScore(shipment.shipmentStatus);
        ShipmentEntity saved = domain.shipments().save(shipment);
        return ResponseEntity.accepted().body(WriteAccepted.of("UPDATE", "ShipmentEntity", saved.shipmentId, saved));
    }

    private double riskScore(String status) {
        if ("EXCEPTION".equals(status)) {
            return 95.0;
        }
        if ("DELAYED".equals(status)) {
            return 80.0;
        }
        if ("OUT_FOR_DELIVERY".equals(status)) {
            return 40.0;
        }
        return 20.0;
    }

    public record CreateShipmentRequest(
            @NotNull @Positive Long shipmentId,
            @NotNull @Positive Long customerId,
            @Size(max = 64) String trackingNumber,
            @Size(max = 32) String carrierCode,
            @Size(max = 32) String shipmentStatus,
            @Size(max = 128) String currentCity,
            Long promisedAt
    ) {
    }

    public record CreateShipmentEventRequest(
            @NotNull @Positive Long eventId,
            @Size(max = 32) String eventType,
            @Size(max = 128) String eventCity,
            Long eventTime,
            @Size(max = 32) String severity,
            @Size(max = 2_000) String description
    ) {
    }

    public record UpdateShipmentStatusRequest(
            @NotBlank @Size(max = 32) String shipmentStatus,
            @Size(max = 128) String currentCity
    ) {
    }
}

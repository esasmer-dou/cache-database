package com.example.cachedb.sample.web;

import com.example.cachedb.sample.domain.ShipmentEntity;
import com.example.cachedb.sample.domain.ShipmentEntityCacheBinding;
import com.example.cachedb.sample.domain.ShipmentEventEntity;
import com.example.cachedb.sample.domain.ShipmentEventEntityCacheBinding;
import com.example.cachedb.sample.readmodel.ShipmentReadModels;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.api.ProjectionRepository;
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

    private final EntityRepository<ShipmentEntity, Long> shipmentRepository;
    private final EntityRepository<ShipmentEventEntity, Long> shipmentEventRepository;
    private final ProjectionRepository<ShipmentReadModels.ShipmentSummary, Long> shipmentSummaryRepository;
    private final JdbcTemplate jdbcTemplate;

    public ShipmentController(
            EntityRepository<ShipmentEntity, Long> shipmentRepository,
            EntityRepository<ShipmentEventEntity, Long> shipmentEventRepository,
            ProjectionRepository<ShipmentReadModels.ShipmentSummary, Long> shipmentSummaryRepository,
            JdbcTemplate jdbcTemplate
    ) {
        this.shipmentRepository = shipmentRepository;
        this.shipmentEventRepository = shipmentEventRepository;
        this.shipmentSummaryRepository = shipmentSummaryRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/active")
    public List<ShipmentReadModels.ShipmentSummary> active(@RequestParam(defaultValue = "50") int limit) {
        return ShipmentEntityCacheBinding.activeShipments(shipmentSummaryRepository, clamp(limit, 1, 2_000));
    }

    @GetMapping("/exceptions")
    public List<ShipmentReadModels.ShipmentSummary> exceptions(@RequestParam(defaultValue = "25") int limit) {
        return ShipmentEntityCacheBinding.shipmentExceptions(shipmentSummaryRepository, clamp(limit, 1, 2_000));
    }

    @GetMapping("/customer/{customerId}")
    public List<ShipmentReadModels.ShipmentSummary> customerShipments(
            @PathVariable long customerId,
            @RequestParam(defaultValue = "25") int limit
    ) {
        return ShipmentEntityCacheBinding.customerShipments(
                shipmentSummaryRepository,
                customerId,
                clamp(limit, 1, 2_000)
        );
    }

    @GetMapping("/{shipmentId}")
    public ShipmentEntity detail(
            @PathVariable long shipmentId,
            @RequestParam(defaultValue = "5") int eventPreview
    ) {
        return ShipmentEntityCacheBinding
                .eventPreviewRepository(shipmentRepository, clamp(eventPreview, 1, 20))
                .findById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment not found in active set: " + shipmentId));
    }

    @GetMapping("/{shipmentId}/events")
    public List<ShipmentEventEntity> events(
            @PathVariable long shipmentId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ShipmentEventEntityCacheBinding.eventsForShipment(shipmentEventRepository, shipmentId, clamp(limit, 1, 100));
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
                clamp(limit, 1, 500)
        );
    }

    @PostMapping
    public ShipmentEntity create(@RequestBody CreateShipmentRequest request) {
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
        return shipmentRepository.save(shipment);
    }

    @PostMapping("/{shipmentId}/events")
    public ShipmentEventEntity addEvent(@PathVariable long shipmentId, @RequestBody CreateShipmentEventRequest request) {
        long now = Instant.now().getEpochSecond();
        ShipmentEventEntity event = new ShipmentEventEntity();
        event.eventId = request.eventId();
        event.shipmentId = shipmentId;
        event.eventType = request.eventType() == null ? "HUB_SCAN" : request.eventType();
        event.eventCity = request.eventCity() == null ? "Istanbul" : request.eventCity();
        event.eventTime = request.eventTime() == null ? now : request.eventTime();
        event.severity = request.severity() == null ? "INFO" : request.severity();
        event.description = request.description() == null ? "Manual shipment event" : request.description();
        return shipmentEventRepository.save(event);
    }

    @PatchMapping("/{shipmentId}/status")
    public ShipmentEntity updateStatus(@PathVariable long shipmentId, @RequestBody UpdateShipmentStatusRequest request) {
        ShipmentEntity shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment not found in active set: " + shipmentId));
        shipment.shipmentStatus = request.shipmentStatus();
        shipment.currentCity = request.currentCity() == null ? shipment.currentCity : request.currentCity();
        shipment.updatedAt = Instant.now().getEpochSecond();
        shipment.riskScore = riskScore(shipment.shipmentStatus);
        return shipmentRepository.save(shipment);
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

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    public record CreateShipmentRequest(
            Long shipmentId,
            Long customerId,
            String trackingNumber,
            String carrierCode,
            String shipmentStatus,
            String currentCity,
            Long promisedAt
    ) {
    }

    public record CreateShipmentEventRequest(
            Long eventId,
            String eventType,
            String eventCity,
            Long eventTime,
            String severity,
            String description
    ) {
    }

    public record UpdateShipmentStatusRequest(String shipmentStatus, String currentCity) {
    }
}

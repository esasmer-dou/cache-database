package com.example.cachedb.sample.web;

import com.example.cachedb.sample.application.shipment.ShipmentApplicationService;
import com.example.cachedb.sample.domain.ShipmentEntity;
import com.example.cachedb.sample.domain.ShipmentEventEntity;
import com.example.cachedb.sample.readmodel.ShipmentSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {

    private final ShipmentApplicationService shipments;

    public ShipmentController(ShipmentApplicationService shipments) {
        this.shipments = shipments;
    }

    @GetMapping("/active")
    public List<ShipmentSummary> active(@RequestParam(defaultValue = "50") int limit) {
        return shipments.active(ApiLimits.requireInRange("limit", limit, 1, 1_000));
    }

    @GetMapping("/exceptions")
    public List<ShipmentSummary> exceptions(@RequestParam(defaultValue = "25") int limit) {
        return shipments.exceptions(ApiLimits.requireInRange("limit", limit, 1, 1_000));
    }

    @GetMapping("/customer/{customerId}")
    public List<ShipmentSummary> customerShipments(
            @PathVariable long customerId,
            @RequestParam(defaultValue = "25") int limit
    ) {
        return shipments.forCustomer(customerId, ApiLimits.requireInRange("limit", limit, 1, 1_000));
    }

    @GetMapping("/{shipmentId}")
    public ShipmentEntity detail(
            @PathVariable long shipmentId,
            @RequestParam(defaultValue = "5") int eventPreview
    ) {
        return shipments.detail(
                shipmentId,
                ApiLimits.requireInRange("eventPreview", eventPreview, 1, 20)
        );
    }

    @GetMapping("/{shipmentId}/events")
    public List<ShipmentEventEntity> events(
            @PathVariable long shipmentId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return shipments.events(shipmentId, ApiLimits.requireInRange("limit", limit, 1, 100));
    }

    @GetMapping("/archive")
    public List<ShipmentSummary> deliveredArchive(
            @RequestParam long customerId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return shipments.deliveredArchive(customerId, ApiLimits.requireInRange("limit", limit, 1, 500));
    }

    @PostMapping
    public ResponseEntity<WriteAccepted<ShipmentEntity>> create(
            @Valid @RequestBody CreateShipmentRequest request
    ) {
        var receipt = shipments.create(new ShipmentApplicationService.CreateShipment(
                request.shipmentId(), request.customerId(), request.trackingNumber(),
                request.carrierCode(), request.shipmentStatus(), request.currentCity(), request.promisedAt()
        ));
        return ResponseEntity.accepted().body(WriteAccepted.from("CREATE", "ShipmentEntity", receipt));
    }

    @PostMapping("/{shipmentId}/events")
    public ResponseEntity<WriteAccepted<ShipmentEventEntity>> addEvent(
            @PathVariable long shipmentId,
            @Valid @RequestBody CreateShipmentEventRequest request
    ) {
        var receipt = shipments.addEvent(shipmentId, new ShipmentApplicationService.CreateShipmentEvent(
                request.eventId(), request.eventType(), request.eventCity(),
                request.eventTime(), request.severity(), request.description()
        ));
        return ResponseEntity.accepted().body(WriteAccepted.from("CREATE", "ShipmentEventEntity", receipt));
    }

    @PatchMapping("/{shipmentId}/status")
    public ResponseEntity<WriteAccepted<ShipmentEntity>> updateStatus(
            @PathVariable long shipmentId,
            @Valid @RequestBody UpdateShipmentStatusRequest request
    ) {
        var receipt = shipments.updateStatus(shipmentId, new ShipmentApplicationService.UpdateShipmentStatus(
                request.shipmentStatus(), request.currentCity()
        ));
        return ResponseEntity.accepted().body(WriteAccepted.from("UPDATE", "ShipmentEntity", receipt));
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

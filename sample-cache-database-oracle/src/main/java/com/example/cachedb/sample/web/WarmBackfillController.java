package com.example.cachedb.sample.web;

import com.example.cachedb.sample.application.warm.SampleWarmCommand;
import com.example.cachedb.sample.application.warm.WarmBackfillApplicationService;
import com.reactor.cachedb.spring.boot.CacheDistributedJobSnapshot;
import com.reactor.cachedb.spring.boot.CacheScheduledWarmSnapshot;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/warm")
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
public class WarmBackfillController {

    private final WarmBackfillApplicationService warm;

    public WarmBackfillController(WarmBackfillApplicationService warm) {
        this.warm = warm;
    }

    @PostMapping("/customers/active")
    public ResponseEntity<CacheDistributedJobSnapshot> warmActiveCustomers(
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.submit(SampleWarmCommand.activeCustomers(limit, dryRun)));
    }

    @PostMapping("/orders/customer/{customerId}")
    public ResponseEntity<CacheDistributedJobSnapshot> warmCustomerOrders(
            @PathVariable @Positive long customerId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1_000) int limit,
            @RequestParam(defaultValue = "true") boolean projectionOnly,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.submit(
                SampleWarmCommand.customerOrders(customerId, limit, projectionOnly, dryRun)
        ));
    }

    @PostMapping("/orders/{orderId}/lines")
    public ResponseEntity<CacheDistributedJobSnapshot> warmOrderLines(
            @PathVariable @Positive long orderId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1_000) int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.submit(SampleWarmCommand.orderLines(orderId, limit, dryRun)));
    }

    @PostMapping("/orders/high-value")
    public ResponseEntity<CacheDistributedJobSnapshot> warmRecentHighValueOrders(
            @RequestParam(defaultValue = "100.00") @DecimalMin("0.0") BigDecimal minimumAmount,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.submit(
                SampleWarmCommand.recentHighValueOrders(minimumAmount, limit, dryRun)
        ));
    }

    @PostMapping("/orders/highlighted")
    public ResponseEntity<CacheDistributedJobSnapshot> warmHighlightedOrders(
            @RequestParam(defaultValue = "60") @DecimalMin("0.0") double minimumPriorityScore,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.submit(
                SampleWarmCommand.highlightedOrders(minimumPriorityScore, limit, dryRun)
        ));
    }

    @PostMapping("/products/active")
    public ResponseEntity<CacheDistributedJobSnapshot> warmActiveProducts(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1_000) int limit,
            @RequestParam(defaultValue = "true") boolean projectionOnly,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.submit(
                SampleWarmCommand.activeProducts(category, limit, projectionOnly, dryRun)
        ));
    }

    @PostMapping("/products/low-stock")
    public ResponseEntity<CacheDistributedJobSnapshot> warmLowStockProducts(
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.submit(SampleWarmCommand.simple(
                SampleWarmCommand.Route.LOW_STOCK_PRODUCTS, limit, true, dryRun
        )));
    }

    @PostMapping("/tickets/open")
    public ResponseEntity<CacheDistributedJobSnapshot> warmOpenTickets(
            @RequestParam(defaultValue = "50") @Min(1) @Max(50) int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.submit(SampleWarmCommand.simple(
                SampleWarmCommand.Route.OPEN_TICKETS, limit, false, dryRun
        )));
    }

    @PostMapping("/shipments/active")
    public ResponseEntity<CacheDistributedJobSnapshot> warmActiveShipments(
            @RequestParam(defaultValue = "100") @Min(1) @Max(1_000) int limit,
            @RequestParam(defaultValue = "true") boolean projectionOnly,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.submit(SampleWarmCommand.simple(
                SampleWarmCommand.Route.ACTIVE_SHIPMENTS, limit, projectionOnly, dryRun
        )));
    }

    @PostMapping("/shipments/customer/{customerId}")
    public ResponseEntity<CacheDistributedJobSnapshot> warmCustomerShipments(
            @PathVariable @Positive long customerId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1_000) int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.submit(
                SampleWarmCommand.customerShipments(customerId, limit, dryRun)
        ));
    }

    @PostMapping("/shipments/exceptions")
    public ResponseEntity<CacheDistributedJobSnapshot> warmShipmentExceptions(
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.submit(SampleWarmCommand.simple(
                SampleWarmCommand.Route.SHIPMENT_EXCEPTIONS, limit, true, dryRun
        )));
    }

    @PostMapping("/shipments/{shipmentId}/events")
    public ResponseEntity<CacheDistributedJobSnapshot> warmShipmentEvents(
            @PathVariable @Positive long shipmentId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1_000) int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.submit(
                SampleWarmCommand.shipmentEvents(shipmentId, limit, dryRun)
        ));
    }

    @PostMapping("/reports/live")
    public ResponseEntity<CacheDistributedJobSnapshot> warmLiveReports(
            @RequestParam(defaultValue = "50") @Min(1) @Max(50) int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.submit(SampleWarmCommand.simple(
                SampleWarmCommand.Route.LIVE_REPORTS, limit, false, dryRun
        )));
    }

    @PostMapping("/reports/type/{reportType}")
    public ResponseEntity<CacheDistributedJobSnapshot> warmReportsByType(
            @PathVariable @NotBlank String reportType,
            @RequestParam(defaultValue = "50") @Min(1) @Max(50) int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.submit(
                SampleWarmCommand.reportsByType(reportType, limit, dryRun)
        ));
    }

    @PostMapping("/audit/security")
    public ResponseEntity<CacheDistributedJobSnapshot> warmSecurityAudit(
            @RequestParam(defaultValue = "50") @Min(1) @Max(50) int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.submit(SampleWarmCommand.simple(
                SampleWarmCommand.Route.SECURITY_AUDIT, limit, false, dryRun
        )));
    }

    @GetMapping("/jobs/{jobId}")
    public CacheDistributedJobSnapshot job(@PathVariable @NotBlank String jobId) {
        return warm.job(jobId);
    }

    @GetMapping("/schedules")
    public List<CacheScheduledWarmSnapshot> schedules() {
        return warm.schedules();
    }

    private ResponseEntity<CacheDistributedJobSnapshot> accepted(CacheDistributedJobSnapshot job) {
        return ResponseEntity.accepted()
                .location(URI.create("/api/warm/jobs/" + job.jobId()))
                .body(job);
    }
}

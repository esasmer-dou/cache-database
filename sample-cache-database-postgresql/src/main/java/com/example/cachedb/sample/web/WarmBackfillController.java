package com.example.cachedb.sample.web;

import com.example.cachedb.sample.application.warm.WarmBackfillApplicationService;
import com.reactor.cachedb.spring.boot.CacheDistributedJobSnapshot;
import com.reactor.cachedb.spring.boot.CacheScheduledWarmSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

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
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.activeCustomers(limit(limit, 100), dryRun));
    }

    @PostMapping("/orders/customer/{customerId}")
    public ResponseEntity<CacheDistributedJobSnapshot> warmCustomerOrders(
            @PathVariable long customerId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "true") boolean projectionOnly,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.customerOrders(customerId, limit(limit, 1_000), projectionOnly, dryRun));
    }

    @PostMapping("/orders/{orderId}/lines")
    public ResponseEntity<CacheDistributedJobSnapshot> warmOrderLines(
            @PathVariable long orderId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.orderLines(orderId, limit(limit, 1_000), dryRun));
    }

    @PostMapping("/orders/high-value")
    public ResponseEntity<CacheDistributedJobSnapshot> warmRecentHighValueOrders(
            @RequestParam(defaultValue = "100.00") BigDecimal minimumAmount,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        if (minimumAmount.signum() < 0) {
            throw new IllegalArgumentException("minimumAmount must not be negative");
        }
        return accepted(warm.recentHighValueOrders(minimumAmount, limit(limit, 100), dryRun));
    }

    @PostMapping("/orders/highlighted")
    public ResponseEntity<CacheDistributedJobSnapshot> warmHighlightedOrders(
            @RequestParam(defaultValue = "60") double minimumPriorityScore,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        if (!Double.isFinite(minimumPriorityScore)) {
            throw new IllegalArgumentException("minimumPriorityScore must be finite");
        }
        return accepted(warm.highlightedOrders(minimumPriorityScore, limit(limit, 100), dryRun));
    }

    @PostMapping("/products/active")
    public ResponseEntity<CacheDistributedJobSnapshot> warmActiveProducts(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "true") boolean projectionOnly,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.activeProducts(category, limit(limit, 1_000), projectionOnly, dryRun));
    }

    @PostMapping("/products/low-stock")
    public ResponseEntity<CacheDistributedJobSnapshot> warmLowStockProducts(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.lowStockProducts(limit(limit, 100), dryRun));
    }

    @PostMapping("/tickets/open")
    public ResponseEntity<CacheDistributedJobSnapshot> warmOpenTickets(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.openTickets(limit(limit, 50), dryRun));
    }

    @PostMapping("/shipments/active")
    public ResponseEntity<CacheDistributedJobSnapshot> warmActiveShipments(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "true") boolean projectionOnly,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.activeShipments(limit(limit, 1_000), projectionOnly, dryRun));
    }

    @PostMapping("/shipments/customer/{customerId}")
    public ResponseEntity<CacheDistributedJobSnapshot> warmCustomerShipments(
            @PathVariable long customerId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.customerShipments(customerId, limit(limit, 1_000), dryRun));
    }

    @PostMapping("/shipments/exceptions")
    public ResponseEntity<CacheDistributedJobSnapshot> warmShipmentExceptions(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.shipmentExceptions(limit(limit, 100), dryRun));
    }

    @PostMapping("/shipments/{shipmentId}/events")
    public ResponseEntity<CacheDistributedJobSnapshot> warmShipmentEvents(
            @PathVariable long shipmentId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.shipmentEvents(shipmentId, limit(limit, 1_000), dryRun));
    }

    @PostMapping("/reports/live")
    public ResponseEntity<CacheDistributedJobSnapshot> warmLiveReports(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.liveReports(limit(limit, 50), dryRun));
    }

    @PostMapping("/reports/type/{reportType}")
    public ResponseEntity<CacheDistributedJobSnapshot> warmReportsByType(
            @PathVariable String reportType,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        if (reportType == null || reportType.isBlank()) {
            throw new IllegalArgumentException("reportType must not be blank");
        }
        return accepted(warm.reportsByType(reportType.trim(), limit(limit, 50), dryRun));
    }

    @PostMapping("/audit/security")
    public ResponseEntity<CacheDistributedJobSnapshot> warmSecurityAudit(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.securityAudit(limit(limit, 50), dryRun));
    }

    @GetMapping("/jobs/{jobId}")
    public CacheDistributedJobSnapshot job(@PathVariable String jobId) {
        return warm.job(jobId);
    }

    @GetMapping("/schedules")
    public List<CacheScheduledWarmSnapshot> schedules() {
        return warm.schedules();
    }

    private int limit(int value, int maximum) {
        return ApiLimits.requireInRange("limit", value, 1, maximum);
    }

    private ResponseEntity<CacheDistributedJobSnapshot> accepted(CacheDistributedJobSnapshot job) {
        return ResponseEntity.accepted().body(job);
    }
}

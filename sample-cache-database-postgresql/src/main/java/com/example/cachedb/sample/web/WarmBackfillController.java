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

import java.util.List;

@RestController
@RequestMapping("/api/warm")
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
public class WarmBackfillController {

    private final WarmBackfillApplicationService warm;

    public WarmBackfillController(WarmBackfillApplicationService warm) {
        this.warm = warm;
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

    @PostMapping("/products/active")
    public ResponseEntity<CacheDistributedJobSnapshot> warmActiveProducts(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "true") boolean projectionOnly,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.activeProducts(category, limit(limit, 1_000), projectionOnly, dryRun));
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

    @PostMapping("/reports/live")
    public ResponseEntity<CacheDistributedJobSnapshot> warmLiveReports(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return accepted(warm.liveReports(limit(limit, 50), dryRun));
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

package com.example.cachedb.sample.web;

import com.example.cachedb.sample.service.SampleWarmBackfillService;
import com.example.cachedb.sample.service.WarmJobService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/warm")
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
public class WarmBackfillController {

    private final SampleWarmBackfillService warmBackfillService;
    private final WarmJobService warmJobService;

    public WarmBackfillController(
            SampleWarmBackfillService warmBackfillService,
            WarmJobService warmJobService
    ) {
        this.warmBackfillService = warmBackfillService;
        this.warmJobService = warmJobService;
    }

    @PostMapping("/orders/customer/{customerId}")
    public ResponseEntity<WarmJobService.WarmJob> warmCustomerOrders(
            @PathVariable long customerId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "true") boolean projectionOnly,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        int safeLimit = ApiLimits.requireInRange("limit", limit, 1, 1_000);
        return accepted("customer-orders", () ->
                warmBackfillService.warmCustomerOrders(customerId, safeLimit, projectionOnly, dryRun));
    }

    @PostMapping("/products/active")
    public ResponseEntity<WarmJobService.WarmJob> warmActiveProducts(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "true") boolean projectionOnly,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        int safeLimit = ApiLimits.requireInRange("limit", limit, 1, 1_000);
        return accepted("active-products", () ->
                warmBackfillService.warmActiveProducts(category, safeLimit, projectionOnly, dryRun));
    }

    @PostMapping("/tickets/open")
    public ResponseEntity<WarmJobService.WarmJob> warmOpenTickets(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        int safeLimit = ApiLimits.requireInRange("limit", limit, 1, 50);
        return accepted("open-tickets", () -> warmBackfillService.warmOpenTickets(safeLimit, dryRun));
    }

    @PostMapping("/shipments/active")
    public ResponseEntity<WarmJobService.WarmJob> warmActiveShipments(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "true") boolean projectionOnly,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        int safeLimit = ApiLimits.requireInRange("limit", limit, 1, 1_000);
        return accepted("active-shipments", () ->
                warmBackfillService.warmActiveShipments(safeLimit, projectionOnly, dryRun));
    }

    @PostMapping("/reports/live")
    public ResponseEntity<WarmJobService.WarmJob> warmLiveReports(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        int safeLimit = ApiLimits.requireInRange("limit", limit, 1, 50);
        return accepted("live-report-jobs", () -> warmBackfillService.warmLiveReportJobs(safeLimit, dryRun));
    }

    @PostMapping("/audit/security")
    public ResponseEntity<WarmJobService.WarmJob> warmSecurityAudit(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        int safeLimit = ApiLimits.requireInRange("limit", limit, 1, 50);
        return accepted("security-audit", () -> warmBackfillService.warmSecurityAudit(safeLimit, dryRun));
    }

    @GetMapping("/jobs/{jobId}")
    public WarmJobService.WarmJob job(@PathVariable String jobId) {
        return warmJobService.find(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Warm job not found: " + jobId));
    }

    private ResponseEntity<WarmJobService.WarmJob> accepted(
            String route,
            java.util.function.Supplier<SampleWarmBackfillService.WarmResult> operation
    ) {
        return ResponseEntity.accepted().body(warmJobService.submit(route, operation));
    }
}

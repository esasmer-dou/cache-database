package com.example.cachedb.sample.service;

import com.example.cachedb.sample.domain.GeneratedCacheModule;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheWarmPlan;
import com.reactor.cachedb.starter.CacheWarmResult;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SampleWarmBackfillService {

    private final CacheDatabase cacheDatabase;
    private final GeneratedCacheModule.Scope domain;

    public SampleWarmBackfillService(CacheDatabase cacheDatabase, GeneratedCacheModule.Scope domain) {
        this.cacheDatabase = cacheDatabase;
        this.domain = domain;
    }

    public WarmResult warmCustomerOrders(long customerId, int limit, boolean projectionOnly, boolean dryRun) {
        return execute(
                "customer-orders",
                "customerId=" + customerId,
                domain.orders().queries().customerTimelineWarmPlan(customerId, limit),
                projectionOnly,
                dryRun
        );
    }

    public WarmResult warmActiveProducts(String category, int limit, boolean projectionOnly, boolean dryRun) {
        String normalizedCategory = category == null ? "" : category.trim();
        CacheWarmPlan plan = normalizedCategory.isEmpty()
                ? domain.products().queries().activeProductsWarmPlan(limit)
                : domain.products().queries().activeProductsByCategoryWarmPlan(normalizedCategory, limit);
        return execute(
                "active-products",
                normalizedCategory.isEmpty() ? "all-categories" : "category=" + normalizedCategory,
                plan,
                projectionOnly,
                dryRun
        );
    }

    public WarmResult warmOpenTickets(int limit, boolean dryRun) {
        return execute(
                "open-tickets",
                "status=OPEN",
                domain.supportTickets().queries().openTicketsWarmPlan(limit),
                false,
                dryRun
        );
    }

    public WarmResult warmActiveShipments(int limit, boolean projectionOnly, boolean dryRun) {
        return execute(
                "active-shipments",
                "operational-statuses",
                domain.shipments().queries().activeShipmentsWarmPlan(limit),
                projectionOnly,
                dryRun
        );
    }

    public WarmResult warmLiveReportJobs(int limit, boolean dryRun) {
        return execute(
                "live-report-jobs",
                "status=QUEUED|RUNNING|FAILED",
                domain.reportJobs().queries().liveReportJobsWarmPlan(limit),
                false,
                dryRun
        );
    }

    public WarmResult warmSecurityAudit(int limit, boolean dryRun) {
        return execute(
                "security-audit",
                "severity=WARN|ERROR|SECURITY",
                domain.auditEvents().queries().securityAuditEventsWarmPlan(limit),
                false,
                dryRun
        );
    }

    private WarmResult execute(
            String route,
            String scope,
            CacheWarmPlan plan,
            boolean projectionOnly,
            boolean dryRun
    ) {
        CacheWarmResult result = dryRun
                ? cacheDatabase.dryRun(plan)
                : projectionOnly
                ? cacheDatabase.warmProjections(plan)
                : cacheDatabase.warm(plan);
        return new WarmResult(
                route,
                scope,
                plan.maxRows(),
                result.loadedRows(),
                result.submittedRows(),
                result.durationMillis(),
                projectionOnly,
                dryRun,
                dryRun ? "cache-warm-dry-run" : projectionOnly ? "cache-warm-projections" : "cache-warm-plan",
                result.notes()
        );
    }

    public record WarmResult(
            String route,
            String scope,
            int requestedWindow,
            int rowsReadFromSql,
            int rowsSubmittedToRedis,
            long durationMillis,
            boolean projectionOnly,
            boolean dryRun,
            String source,
            List<String> notes
    ) {
    }
}

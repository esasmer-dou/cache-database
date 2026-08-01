package com.example.cachedb.sample.application.warm;

import com.example.cachedb.sample.application.SampleEntityNotFoundException;
import com.example.cachedb.sample.service.SampleWarmJobHandler;
import com.reactor.cachedb.spring.boot.CacheDistributedJobExecutor;
import com.reactor.cachedb.spring.boot.CacheDistributedJobSnapshot;
import com.reactor.cachedb.spring.boot.CacheScheduledWarmRegistry;
import com.reactor.cachedb.spring.boot.CacheScheduledWarmSnapshot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
public final class WarmBackfillApplicationService {

    private final CacheDistributedJobExecutor jobs;
    private final ObjectProvider<CacheScheduledWarmRegistry> schedules;

    public WarmBackfillApplicationService(
            CacheDistributedJobExecutor jobs,
            ObjectProvider<CacheScheduledWarmRegistry> schedules
    ) {
        this.jobs = jobs;
        this.schedules = schedules;
    }

    public CacheDistributedJobSnapshot customerOrders(
            long customerId,
            int limit,
            boolean projectionOnly,
            boolean dryRun
    ) {
        return submit(SampleWarmJobHandler.Arguments.customerOrders(customerId, limit, projectionOnly, dryRun));
    }

    public CacheDistributedJobSnapshot activeProducts(
            String category,
            int limit,
            boolean projectionOnly,
            boolean dryRun
    ) {
        return submit(SampleWarmJobHandler.Arguments.activeProducts(category, limit, projectionOnly, dryRun));
    }

    public CacheDistributedJobSnapshot openTickets(int limit, boolean dryRun) {
        return route(SampleWarmJobHandler.Route.OPEN_TICKETS, limit, false, dryRun);
    }

    public CacheDistributedJobSnapshot activeShipments(int limit, boolean projectionOnly, boolean dryRun) {
        return route(SampleWarmJobHandler.Route.ACTIVE_SHIPMENTS, limit, projectionOnly, dryRun);
    }

    public CacheDistributedJobSnapshot liveReports(int limit, boolean dryRun) {
        return route(SampleWarmJobHandler.Route.LIVE_REPORTS, limit, false, dryRun);
    }

    public CacheDistributedJobSnapshot securityAudit(int limit, boolean dryRun) {
        return route(SampleWarmJobHandler.Route.SECURITY_AUDIT, limit, false, dryRun);
    }

    public CacheDistributedJobSnapshot job(String jobId) {
        return jobs.find(jobId)
                .orElseThrow(() -> new SampleEntityNotFoundException("Warm job", jobId));
    }

    public List<CacheScheduledWarmSnapshot> schedules() {
        CacheScheduledWarmRegistry registry = schedules.getIfAvailable();
        return registry == null ? List.of() : registry.snapshots();
    }

    private CacheDistributedJobSnapshot route(
            SampleWarmJobHandler.Route route,
            int limit,
            boolean projectionOnly,
            boolean dryRun
    ) {
        return submit(SampleWarmJobHandler.Arguments.route(route, limit, projectionOnly, dryRun));
    }

    private CacheDistributedJobSnapshot submit(SampleWarmJobHandler.Arguments arguments) {
        return jobs.submit(SampleWarmJobHandler.ROUTE, arguments);
    }
}

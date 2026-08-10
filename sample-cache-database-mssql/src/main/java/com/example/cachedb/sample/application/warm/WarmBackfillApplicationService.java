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

import java.math.BigDecimal;
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

    public CacheDistributedJobSnapshot activeCustomers(int limit, boolean dryRun) {
        return submit(SampleWarmJobHandler.Arguments.activeCustomers(limit, dryRun));
    }

    public CacheDistributedJobSnapshot customerOrders(
            long customerId,
            int limit,
            boolean projectionOnly,
            boolean dryRun
    ) {
        return submit(SampleWarmJobHandler.Arguments.customerOrders(customerId, limit, projectionOnly, dryRun));
    }

    public CacheDistributedJobSnapshot orderLines(long orderId, int limit, boolean dryRun) {
        return submit(SampleWarmJobHandler.Arguments.orderLines(orderId, limit, dryRun));
    }

    public CacheDistributedJobSnapshot recentHighValueOrders(
            BigDecimal minimumAmount,
            int limit,
            boolean dryRun
    ) {
        return submit(SampleWarmJobHandler.Arguments.recentHighValueOrders(minimumAmount, limit, dryRun));
    }

    public CacheDistributedJobSnapshot highlightedOrders(
            double minimumPriorityScore,
            int limit,
            boolean dryRun
    ) {
        return submit(SampleWarmJobHandler.Arguments.highlightedOrders(minimumPriorityScore, limit, dryRun));
    }

    public CacheDistributedJobSnapshot activeProducts(
            String category,
            int limit,
            boolean projectionOnly,
            boolean dryRun
    ) {
        return submit(SampleWarmJobHandler.Arguments.activeProducts(category, limit, projectionOnly, dryRun));
    }

    public CacheDistributedJobSnapshot lowStockProducts(int limit, boolean dryRun) {
        return route(SampleWarmJobHandler.Route.LOW_STOCK_PRODUCTS, limit, true, dryRun);
    }

    public CacheDistributedJobSnapshot openTickets(int limit, boolean dryRun) {
        return route(SampleWarmJobHandler.Route.OPEN_TICKETS, limit, false, dryRun);
    }

    public CacheDistributedJobSnapshot activeShipments(int limit, boolean projectionOnly, boolean dryRun) {
        return route(SampleWarmJobHandler.Route.ACTIVE_SHIPMENTS, limit, projectionOnly, dryRun);
    }

    public CacheDistributedJobSnapshot customerShipments(long customerId, int limit, boolean dryRun) {
        return submit(SampleWarmJobHandler.Arguments.customerShipments(customerId, limit, dryRun));
    }

    public CacheDistributedJobSnapshot shipmentExceptions(int limit, boolean dryRun) {
        return route(SampleWarmJobHandler.Route.SHIPMENT_EXCEPTIONS, limit, true, dryRun);
    }

    public CacheDistributedJobSnapshot shipmentEvents(long shipmentId, int limit, boolean dryRun) {
        return submit(SampleWarmJobHandler.Arguments.shipmentEvents(shipmentId, limit, dryRun));
    }

    public CacheDistributedJobSnapshot liveReports(int limit, boolean dryRun) {
        return route(SampleWarmJobHandler.Route.LIVE_REPORTS, limit, false, dryRun);
    }

    public CacheDistributedJobSnapshot reportsByType(String reportType, int limit, boolean dryRun) {
        return submit(SampleWarmJobHandler.Arguments.reportsByType(reportType, limit, dryRun));
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

package com.example.cachedb.sample.service;

import com.example.cachedb.sample.repository.AuditEventRepository;
import com.example.cachedb.sample.repository.CustomerRepository;
import com.example.cachedb.sample.repository.OrderLineRepository;
import com.example.cachedb.sample.repository.OrderRepository;
import com.example.cachedb.sample.repository.ProductRepository;
import com.example.cachedb.sample.repository.ReportJobRepository;
import com.example.cachedb.sample.repository.ShipmentRepository;
import com.example.cachedb.sample.repository.ShipmentEventRepository;
import com.example.cachedb.sample.repository.SupportTicketRepository;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheWarmPlan;
import com.reactor.cachedb.starter.CacheWarmResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class SampleWarmBackfillService {

    private final CacheDatabase cacheDatabase;
    private final CustomerRepository customers;
    private final OrderRepository orders;
    private final OrderLineRepository orderLines;
    private final ProductRepository products;
    private final SupportTicketRepository tickets;
    private final ShipmentRepository shipments;
    private final ShipmentEventRepository shipmentEvents;
    private final ReportJobRepository reportJobs;
    private final AuditEventRepository auditEvents;

    public SampleWarmBackfillService(
            CacheDatabase cacheDatabase,
            CustomerRepository customers,
            OrderRepository orders,
            OrderLineRepository orderLines,
            ProductRepository products,
            SupportTicketRepository tickets,
            ShipmentRepository shipments,
            ShipmentEventRepository shipmentEvents,
            ReportJobRepository reportJobs,
            AuditEventRepository auditEvents
    ) {
        this.cacheDatabase = cacheDatabase;
        this.customers = customers;
        this.orders = orders;
        this.orderLines = orderLines;
        this.products = products;
        this.tickets = tickets;
        this.shipments = shipments;
        this.shipmentEvents = shipmentEvents;
        this.reportJobs = reportJobs;
        this.auditEvents = auditEvents;
    }

    public WarmResult warmActiveCustomers(int limit, boolean dryRun) {
        return execute(
                "active-customers",
                "status=ACTIVE",
                customers.warmActive(limit),
                false,
                dryRun
        );
    }

    public WarmResult warmCustomerOrders(long customerId, int limit, boolean projectionOnly, boolean dryRun) {
        return execute(
                "customer-orders",
                "customerId=" + customerId,
                projectionOnly
                        ? orders.warmCustomerTimelineProjection(customerId, limit)
                        : orders.warmCustomerTimelineEntities(customerId, limit),
                projectionOnly,
                dryRun
        );
    }

    public WarmResult warmOrderLines(long orderId, int limit, boolean dryRun) {
        return execute(
                "order-lines",
                "orderId=" + orderId,
                orderLines.warmForOrder(orderId, limit),
                false,
                dryRun
        );
    }

    public WarmResult warmRecentHighValueOrders(BigDecimal minimumAmount, int limit, boolean dryRun) {
        return execute(
                "recent-high-value-orders",
                "minimumAmount=" + minimumAmount,
                orders.warmRecentHighValue(minimumAmount, limit),
                true,
                dryRun
        );
    }

    public WarmResult warmHighlightedOrders(double minimumPriorityScore, int limit, boolean dryRun) {
        return execute(
                "dashboard-highlighted-orders",
                "minimumPriorityScore=" + minimumPriorityScore,
                orders.warmHighlighted(minimumPriorityScore, limit),
                true,
                dryRun
        );
    }

    public WarmResult warmActiveProducts(String category, int limit, boolean projectionOnly, boolean dryRun) {
        String normalizedCategory = category == null ? "" : category.trim();
        CacheWarmPlan plan = normalizedCategory.isEmpty()
                ? projectionOnly ? products.warmActiveProjection(limit) : products.warmActiveEntities(limit)
                : projectionOnly
                ? products.warmCategoryProjection(normalizedCategory, limit)
                : products.warmCategoryEntities(normalizedCategory, limit);
        return execute(
                "active-products",
                normalizedCategory.isEmpty() ? "all-categories" : "category=" + normalizedCategory,
                plan,
                projectionOnly,
                dryRun
        );
    }

    public WarmResult warmLowStockProducts(int limit, boolean dryRun) {
        return execute(
                "low-stock-products",
                "status=LOW_STOCK",
                products.warmLowStock(limit),
                true,
                dryRun
        );
    }

    public WarmResult warmOpenTickets(int limit, boolean dryRun) {
        return execute(
                "open-tickets",
                "status=OPEN",
                tickets.warmOpen(limit),
                false,
                dryRun
        );
    }

    public WarmResult warmActiveShipments(int limit, boolean projectionOnly, boolean dryRun) {
        return execute(
                "active-shipments",
                "operational-statuses",
                projectionOnly ? shipments.warmActiveProjection(limit) : shipments.warmActiveEntities(limit),
                projectionOnly,
                dryRun
        );
    }

    public WarmResult warmCustomerShipments(long customerId, int limit, boolean dryRun) {
        return execute(
                "customer-shipments",
                "customerId=" + customerId,
                shipments.warmForCustomer(customerId, limit),
                true,
                dryRun
        );
    }

    public WarmResult warmShipmentExceptions(int limit, boolean dryRun) {
        return execute(
                "shipment-exceptions",
                "status=DELAYED|EXCEPTION",
                shipments.warmExceptions(limit),
                true,
                dryRun
        );
    }

    public WarmResult warmShipmentEvents(long shipmentId, int limit, boolean dryRun) {
        return execute(
                "shipment-events",
                "shipmentId=" + shipmentId,
                shipmentEvents.warmForShipment(shipmentId, limit),
                false,
                dryRun
        );
    }

    public WarmResult warmLiveReportJobs(int limit, boolean dryRun) {
        return execute(
                "live-report-jobs",
                "status=QUEUED|RUNNING|FAILED",
                reportJobs.warmLive(limit),
                false,
                dryRun
        );
    }

    public WarmResult warmReportJobsByType(String reportType, int limit, boolean dryRun) {
        return execute(
                "report-jobs-by-type",
                "reportType=" + reportType,
                reportJobs.warmByType(reportType, limit),
                false,
                dryRun
        );
    }

    public WarmResult warmSecurityAudit(int limit, boolean dryRun) {
        return execute(
                "security-audit",
                "severity=WARN|ERROR|SECURITY",
                auditEvents.warmSecurity(limit),
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

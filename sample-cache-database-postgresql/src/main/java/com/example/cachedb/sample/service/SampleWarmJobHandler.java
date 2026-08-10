package com.example.cachedb.sample.service;

import com.reactor.cachedb.spring.boot.CacheDistributedJobContext;
import com.reactor.cachedb.spring.boot.CacheDistributedJobHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
public final class SampleWarmJobHandler implements CacheDistributedJobHandler<SampleWarmJobHandler.Arguments> {

    public static final String ROUTE = "sample.route.warm";

    private final SampleWarmBackfillService warmService;

    public SampleWarmJobHandler(SampleWarmBackfillService warmService) {
        this.warmService = warmService;
    }

    @Override
    public String route() {
        return ROUTE;
    }

    @Override
    public Class<Arguments> argumentType() {
        return Arguments.class;
    }

    @Override
    public Object execute(Arguments arguments, CacheDistributedJobContext context) {
        context.checkpoint(Map.of(
                "phase", "WARMING",
                "route", arguments.route().name(),
                "attempt", context.attempt()
        ));
        SampleWarmBackfillService.WarmResult result = switch (arguments.route()) {
            case ACTIVE_CUSTOMERS -> warmService.warmActiveCustomers(arguments.limit(), arguments.dryRun());
            case CUSTOMER_ORDERS -> warmService.warmCustomerOrders(
                    arguments.customerId(), arguments.limit(), arguments.projectionOnly(), arguments.dryRun());
            case ORDER_LINES -> warmService.warmOrderLines(
                    arguments.orderId(), arguments.limit(), arguments.dryRun());
            case RECENT_HIGH_VALUE_ORDERS -> warmService.warmRecentHighValueOrders(
                    arguments.minimumAmount(), arguments.limit(), arguments.dryRun());
            case HIGHLIGHTED_ORDERS -> warmService.warmHighlightedOrders(
                    arguments.minimumPriorityScore(), arguments.limit(), arguments.dryRun());
            case ACTIVE_PRODUCTS -> warmService.warmActiveProducts(
                    arguments.category(), arguments.limit(), arguments.projectionOnly(), arguments.dryRun());
            case LOW_STOCK_PRODUCTS -> warmService.warmLowStockProducts(arguments.limit(), arguments.dryRun());
            case OPEN_TICKETS -> warmService.warmOpenTickets(arguments.limit(), arguments.dryRun());
            case ACTIVE_SHIPMENTS -> warmService.warmActiveShipments(
                    arguments.limit(), arguments.projectionOnly(), arguments.dryRun());
            case CUSTOMER_SHIPMENTS -> warmService.warmCustomerShipments(
                    arguments.customerId(), arguments.limit(), arguments.dryRun());
            case SHIPMENT_EXCEPTIONS -> warmService.warmShipmentExceptions(arguments.limit(), arguments.dryRun());
            case SHIPMENT_EVENTS -> warmService.warmShipmentEvents(
                    arguments.shipmentId(), arguments.limit(), arguments.dryRun());
            case LIVE_REPORTS -> warmService.warmLiveReportJobs(arguments.limit(), arguments.dryRun());
            case REPORTS_BY_TYPE -> warmService.warmReportJobsByType(
                    arguments.reportType(), arguments.limit(), arguments.dryRun());
            case SECURITY_AUDIT -> warmService.warmSecurityAudit(arguments.limit(), arguments.dryRun());
        };
        context.checkpoint(Map.of(
                "phase", "COMPLETED",
                "route", arguments.route().name(),
                "attempt", context.attempt()
        ));
        return result;
    }

    public enum Route {
        ACTIVE_CUSTOMERS,
        CUSTOMER_ORDERS,
        ORDER_LINES,
        RECENT_HIGH_VALUE_ORDERS,
        HIGHLIGHTED_ORDERS,
        ACTIVE_PRODUCTS,
        LOW_STOCK_PRODUCTS,
        OPEN_TICKETS,
        ACTIVE_SHIPMENTS,
        CUSTOMER_SHIPMENTS,
        SHIPMENT_EXCEPTIONS,
        SHIPMENT_EVENTS,
        LIVE_REPORTS,
        REPORTS_BY_TYPE,
        SECURITY_AUDIT
    }

    public record Arguments(
            Route route,
            long customerId,
            long orderId,
            long shipmentId,
            String category,
            String reportType,
            BigDecimal minimumAmount,
            double minimumPriorityScore,
            int limit,
            boolean projectionOnly,
            boolean dryRun
    ) {
        public static Arguments activeCustomers(int limit, boolean dryRun) {
            return base(Route.ACTIVE_CUSTOMERS, limit, false, dryRun);
        }

        public static Arguments customerOrders(long customerId, int limit, boolean projectionOnly, boolean dryRun) {
            return new Arguments(Route.CUSTOMER_ORDERS, customerId, 0L, 0L, null, null, null, 0.0,
                    limit, projectionOnly, dryRun);
        }

        public static Arguments orderLines(long orderId, int limit, boolean dryRun) {
            return new Arguments(Route.ORDER_LINES, 0L, orderId, 0L, null, null, null, 0.0,
                    limit, false, dryRun);
        }

        public static Arguments recentHighValueOrders(BigDecimal minimumAmount, int limit, boolean dryRun) {
            return new Arguments(Route.RECENT_HIGH_VALUE_ORDERS, 0L, 0L, 0L, null, null,
                    minimumAmount, 0.0, limit, true, dryRun);
        }

        public static Arguments highlightedOrders(double minimumPriorityScore, int limit, boolean dryRun) {
            return new Arguments(Route.HIGHLIGHTED_ORDERS, 0L, 0L, 0L, null, null,
                    null, minimumPriorityScore, limit, true, dryRun);
        }

        public static Arguments activeProducts(String category, int limit, boolean projectionOnly, boolean dryRun) {
            return new Arguments(Route.ACTIVE_PRODUCTS, 0L, 0L, 0L, category, null, null, 0.0,
                    limit, projectionOnly, dryRun);
        }

        public static Arguments customerShipments(long customerId, int limit, boolean dryRun) {
            return new Arguments(Route.CUSTOMER_SHIPMENTS, customerId, 0L, 0L, null, null, null, 0.0,
                    limit, true, dryRun);
        }

        public static Arguments shipmentEvents(long shipmentId, int limit, boolean dryRun) {
            return new Arguments(Route.SHIPMENT_EVENTS, 0L, 0L, shipmentId, null, null, null, 0.0,
                    limit, false, dryRun);
        }

        public static Arguments reportsByType(String reportType, int limit, boolean dryRun) {
            return new Arguments(Route.REPORTS_BY_TYPE, 0L, 0L, 0L, null, reportType, null, 0.0,
                    limit, false, dryRun);
        }

        public static Arguments route(Route route, int limit, boolean projectionOnly, boolean dryRun) {
            return base(route, limit, projectionOnly, dryRun);
        }

        private static Arguments base(Route route, int limit, boolean projectionOnly, boolean dryRun) {
            return new Arguments(route, 0L, 0L, 0L, null, null, null, 0.0,
                    limit, projectionOnly, dryRun);
        }
    }
}

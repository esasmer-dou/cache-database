package com.example.cachedb.sample.service;

import com.reactor.cachedb.spring.boot.CacheDistributedJobContext;
import com.reactor.cachedb.spring.boot.CacheDistributedJobHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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
            case CUSTOMER_ORDERS -> warmService.warmCustomerOrders(
                    arguments.customerId(), arguments.limit(), arguments.projectionOnly(), arguments.dryRun());
            case ACTIVE_PRODUCTS -> warmService.warmActiveProducts(
                    arguments.category(), arguments.limit(), arguments.projectionOnly(), arguments.dryRun());
            case OPEN_TICKETS -> warmService.warmOpenTickets(arguments.limit(), arguments.dryRun());
            case ACTIVE_SHIPMENTS -> warmService.warmActiveShipments(
                    arguments.limit(), arguments.projectionOnly(), arguments.dryRun());
            case LIVE_REPORTS -> warmService.warmLiveReportJobs(arguments.limit(), arguments.dryRun());
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
        CUSTOMER_ORDERS,
        ACTIVE_PRODUCTS,
        OPEN_TICKETS,
        ACTIVE_SHIPMENTS,
        LIVE_REPORTS,
        SECURITY_AUDIT
    }

    public record Arguments(
            Route route,
            long customerId,
            String category,
            int limit,
            boolean projectionOnly,
            boolean dryRun
    ) {
        public static Arguments customerOrders(long customerId, int limit, boolean projectionOnly, boolean dryRun) {
            return new Arguments(Route.CUSTOMER_ORDERS, customerId, null, limit, projectionOnly, dryRun);
        }

        public static Arguments activeProducts(String category, int limit, boolean projectionOnly, boolean dryRun) {
            return new Arguments(Route.ACTIVE_PRODUCTS, 0L, category, limit, projectionOnly, dryRun);
        }

        public static Arguments route(Route route, int limit, boolean projectionOnly, boolean dryRun) {
            return new Arguments(route, 0L, null, limit, projectionOnly, dryRun);
        }
    }
}

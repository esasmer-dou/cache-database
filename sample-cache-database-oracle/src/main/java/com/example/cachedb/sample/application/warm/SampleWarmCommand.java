package com.example.cachedb.sample.application.warm;

import java.math.BigDecimal;

/** One validated, JSON-serializable command for the sample's bounded warm routes. */
public record SampleWarmCommand(
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
    public SampleWarmCommand {
        if (route == null) {
            throw new IllegalArgumentException("route must not be null");
        }
        if (limit <= 0 || limit > route.maxRows()) {
            throw new IllegalArgumentException("limit must be between 1 and "
                    + route.maxRows() + " for " + route);
        }
        category = normalize(category);
        reportType = normalize(reportType);
        switch (route) {
            case CUSTOMER_ORDERS, CUSTOMER_SHIPMENTS -> requirePositive(customerId, "customerId");
            case ORDER_LINES -> requirePositive(orderId, "orderId");
            case SHIPMENT_EVENTS -> requirePositive(shipmentId, "shipmentId");
            case RECENT_HIGH_VALUE_ORDERS -> {
                if (minimumAmount == null || minimumAmount.signum() < 0) {
                    throw new IllegalArgumentException("minimumAmount must not be negative");
                }
            }
            case HIGHLIGHTED_ORDERS -> {
                if (!Double.isFinite(minimumPriorityScore) || minimumPriorityScore < 0.0d) {
                    throw new IllegalArgumentException("minimumPriorityScore must be finite and non-negative");
                }
            }
            case REPORTS_BY_TYPE -> {
                if (reportType.isEmpty()) {
                    throw new IllegalArgumentException("reportType must not be blank");
                }
            }
            default -> {
            }
        }
    }

    public static SampleWarmCommand activeCustomers(int limit, boolean dryRun) {
        return simple(Route.ACTIVE_CUSTOMERS, limit, false, dryRun);
    }

    public static SampleWarmCommand customerOrders(
            long customerId,
            int limit,
            boolean projectionOnly,
            boolean dryRun
    ) {
        return new SampleWarmCommand(Route.CUSTOMER_ORDERS, customerId, 0L, 0L,
                "", "", null, 0.0, limit, projectionOnly, dryRun);
    }

    public static SampleWarmCommand orderLines(long orderId, int limit, boolean dryRun) {
        return new SampleWarmCommand(Route.ORDER_LINES, 0L, orderId, 0L,
                "", "", null, 0.0, limit, false, dryRun);
    }

    public static SampleWarmCommand recentHighValueOrders(
            BigDecimal minimumAmount,
            int limit,
            boolean dryRun
    ) {
        return new SampleWarmCommand(Route.RECENT_HIGH_VALUE_ORDERS, 0L, 0L, 0L,
                "", "", minimumAmount, 0.0, limit, true, dryRun);
    }

    public static SampleWarmCommand highlightedOrders(
            double minimumPriorityScore,
            int limit,
            boolean dryRun
    ) {
        return new SampleWarmCommand(Route.HIGHLIGHTED_ORDERS, 0L, 0L, 0L,
                "", "", null, minimumPriorityScore, limit, true, dryRun);
    }

    public static SampleWarmCommand activeProducts(
            String category,
            int limit,
            boolean projectionOnly,
            boolean dryRun
    ) {
        return new SampleWarmCommand(Route.ACTIVE_PRODUCTS, 0L, 0L, 0L,
                category, "", null, 0.0, limit, projectionOnly, dryRun);
    }

    public static SampleWarmCommand customerShipments(long customerId, int limit, boolean dryRun) {
        return new SampleWarmCommand(Route.CUSTOMER_SHIPMENTS, customerId, 0L, 0L,
                "", "", null, 0.0, limit, true, dryRun);
    }

    public static SampleWarmCommand shipmentEvents(long shipmentId, int limit, boolean dryRun) {
        return new SampleWarmCommand(Route.SHIPMENT_EVENTS, 0L, 0L, shipmentId,
                "", "", null, 0.0, limit, false, dryRun);
    }

    public static SampleWarmCommand reportsByType(String reportType, int limit, boolean dryRun) {
        return new SampleWarmCommand(Route.REPORTS_BY_TYPE, 0L, 0L, 0L,
                "", reportType, null, 0.0, limit, false, dryRun);
    }

    public static SampleWarmCommand simple(
            Route route,
            int limit,
            boolean projectionOnly,
            boolean dryRun
    ) {
        return new SampleWarmCommand(route, 0L, 0L, 0L,
                "", "", null, 0.0, limit, projectionOnly, dryRun);
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public enum Route {
        ACTIVE_CUSTOMERS("active-customers", 100),
        CUSTOMER_ORDERS("customer-orders", 1_000),
        ORDER_LINES("order-lines", 1_000),
        RECENT_HIGH_VALUE_ORDERS("recent-high-value-orders", 100),
        HIGHLIGHTED_ORDERS("dashboard-highlighted-orders", 100),
        ACTIVE_PRODUCTS("active-products", 1_000),
        LOW_STOCK_PRODUCTS("low-stock-products", 100),
        OPEN_TICKETS("open-tickets", 50),
        ACTIVE_SHIPMENTS("active-shipments", 1_000),
        CUSTOMER_SHIPMENTS("customer-shipments", 1_000),
        SHIPMENT_EXCEPTIONS("shipment-exceptions", 100),
        SHIPMENT_EVENTS("shipment-events", 1_000),
        LIVE_REPORTS("live-report-jobs", 50),
        REPORTS_BY_TYPE("report-jobs-by-type", 50),
        SECURITY_AUDIT("security-audit", 50);

        private final String operation;
        private final int maxRows;

        Route(String operation, int maxRows) {
            this.operation = operation;
            this.maxRows = maxRows;
        }

        public String operation() {
            return operation;
        }

        public int maxRows() {
            return maxRows;
        }
    }
}

package com.example.cachedb.sample.service;

import com.example.cachedb.sample.application.warm.SampleWarmCommand;
import com.example.cachedb.sample.repository.SampleRepositories;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheWarmExecutionMode;
import com.reactor.cachedb.starter.CacheWarmPlan;
import com.reactor.cachedb.starter.CacheWarmSummary;
import com.reactor.cachedb.starter.CacheWarmTarget;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Converts one typed sample command into one generated repository warm plan.
 * The service never builds ad-hoc SQL and never hides a durable-source fallback.
 */
@Service
public final class SampleWarmBackfillService {

    private final CacheDatabase cacheDatabase;
    private final SampleRepositories repositories;

    public SampleWarmBackfillService(CacheDatabase cacheDatabase, SampleRepositories repositories) {
        this.cacheDatabase = cacheDatabase;
        this.repositories = repositories;
    }

    public CacheWarmSummary execute(SampleWarmCommand arguments) {
        SampleWarmCommand command = Objects.requireNonNull(arguments, "arguments");
        CacheWarmPlan plan = plan(command);
        return cacheDatabase.executeWarm(
                plan,
                command.dryRun() ? CacheWarmExecutionMode.DRY_RUN : CacheWarmExecutionMode.APPLY
        ).summary(command.route().operation());
    }

    private CacheWarmPlan plan(SampleWarmCommand command) {
        return switch (command.route()) {
            case ACTIVE_CUSTOMERS -> repositories.customers().warmActive(command.limit());
            case CUSTOMER_ORDERS -> repositories.orders().warmCustomerTimeline(
                    command.customerId(), command.limit(), target(command.projectionOnly()));
            case ORDER_LINES -> repositories.orderLines().warmForOrder(command.orderId(), command.limit());
            case RECENT_HIGH_VALUE_ORDERS -> repositories.orders().warmRecentHighValue(
                    command.minimumAmount(), command.limit());
            case HIGHLIGHTED_ORDERS -> repositories.orders().warmHighlighted(
                    command.minimumPriorityScore(), command.limit());
            case ACTIVE_PRODUCTS -> activeProducts(command);
            case LOW_STOCK_PRODUCTS -> repositories.products().warmLowStock(command.limit());
            case OPEN_TICKETS -> repositories.supportTickets().warmOpen(command.limit());
            case ACTIVE_SHIPMENTS -> repositories.shipments().warmActive(
                    command.limit(), target(command.projectionOnly()));
            case CUSTOMER_SHIPMENTS -> repositories.shipments().warmForCustomer(
                    command.customerId(), command.limit());
            case SHIPMENT_EXCEPTIONS -> repositories.shipments().warmExceptions(command.limit());
            case SHIPMENT_EVENTS -> repositories.shipmentEvents().warmForShipment(
                    command.shipmentId(), command.limit());
            case LIVE_REPORTS -> repositories.reportJobs().warmLive(command.limit());
            case REPORTS_BY_TYPE -> repositories.reportJobs().warmByType(
                    command.reportType(), command.limit());
            case SECURITY_AUDIT -> repositories.auditEvents().warmSecurity(command.limit());
        };
    }

    private CacheWarmPlan activeProducts(SampleWarmCommand command) {
        String category = command.category() == null ? "" : command.category().trim();
        CacheWarmTarget target = target(command.projectionOnly());
        return category.isEmpty()
                ? repositories.products().warmActive(command.limit(), target)
                : repositories.products().warmCategory(category, command.limit(), target);
    }

    private CacheWarmTarget target(boolean projectionOnly) {
        return projectionOnly
                ? CacheWarmTarget.PROJECTIONS_ONLY
                : CacheWarmTarget.ENTITY_AND_PROJECTIONS;
    }
}

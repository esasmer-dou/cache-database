package com.example.cachedb.sample.application.dashboard;

import com.example.cachedb.sample.domain.GeneratedCacheModule;
import com.example.cachedb.sample.domain.ReportJobEntity;
import com.example.cachedb.sample.domain.SupportTicketEntity;
import com.example.cachedb.sample.readmodel.OrderSummary;
import com.example.cachedb.sample.readmodel.ProductAvailability;
import com.example.cachedb.sample.readmodel.ShipmentSummary;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public final class DashboardQueryService {

    private final GeneratedCacheModule.Scope domain;

    public DashboardQueryService(GeneratedCacheModule.Scope domain) {
        this.domain = domain;
    }

    public CommerceDashboard commerce(int limit) {
        List<OrderSummary> orders = domain.orders().queries().highlightedOrdersProjection(60.0, limit);
        List<SupportTicketEntity> tickets = domain.supportTickets().queries().openTickets(limit);
        BigDecimal amount = orders.stream()
                .map(OrderSummary::orderAmount)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CommerceDashboard(orders.size(), amount, tickets.size(), orders, tickets);
    }

    public OperationsDashboard operations(int limit) {
        List<ProductAvailability> products = domain.products().queries().lowStockProductsProjection(limit);
        List<ShipmentSummary> shipments = domain.shipments().queries().shipmentExceptionsProjection(limit);
        List<ReportJobEntity> reports = domain.reportJobs().queries().liveReportJobs(limit);
        return new OperationsDashboard(
                products.size(), shipments.size(), reports.size(), products, shipments, reports
        );
    }

    public record CommerceDashboard(
            int highlightedOrders,
            BigDecimal highlightedAmount,
            int openTickets,
            List<OrderSummary> orders,
            List<SupportTicketEntity> tickets
    ) {
    }

    public record OperationsDashboard(
            int lowStockProducts,
            int shipmentExceptions,
            int liveReportJobs,
            List<ProductAvailability> products,
            List<ShipmentSummary> shipments,
            List<ReportJobEntity> reports
    ) {
    }
}

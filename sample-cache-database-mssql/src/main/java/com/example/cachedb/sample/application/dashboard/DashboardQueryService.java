package com.example.cachedb.sample.application.dashboard;

import com.example.cachedb.sample.domain.ReportJobEntity;
import com.example.cachedb.sample.domain.SupportTicketEntity;
import com.example.cachedb.sample.readmodel.OrderSummary;
import com.example.cachedb.sample.readmodel.ProductAvailability;
import com.example.cachedb.sample.readmodel.ShipmentSummary;
import com.example.cachedb.sample.repository.OrderRepository;
import com.example.cachedb.sample.repository.ProductRepository;
import com.example.cachedb.sample.repository.ReportJobRepository;
import com.example.cachedb.sample.repository.ShipmentRepository;
import com.example.cachedb.sample.repository.SupportTicketRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public final class DashboardQueryService {

    private final OrderRepository orders;
    private final ProductRepository products;
    private final ShipmentRepository shipments;
    private final SupportTicketRepository tickets;
    private final ReportJobRepository reports;

    public DashboardQueryService(
            OrderRepository orders,
            ProductRepository products,
            ShipmentRepository shipments,
            SupportTicketRepository tickets,
            ReportJobRepository reports
    ) {
        this.orders = orders;
        this.products = products;
        this.shipments = shipments;
        this.tickets = tickets;
        this.reports = reports;
    }

    public CommerceDashboard commerce(int limit) {
        List<OrderSummary> highlightedOrders = orders.highlighted(60.0, limit).completeItems();
        List<SupportTicketEntity> openTickets = tickets.open(limit).completeItems();
        BigDecimal amount = highlightedOrders.stream()
                .map(OrderSummary::orderAmount)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CommerceDashboard(
                highlightedOrders.size(), amount, openTickets.size(), highlightedOrders, openTickets
        );
    }

    public OperationsDashboard operations(int limit) {
        List<ProductAvailability> lowStockProducts = products.lowStock(limit).completeItems();
        List<ShipmentSummary> shipmentExceptions = shipments.exceptions(limit).completeItems();
        List<ReportJobEntity> liveReports = reports.live(limit).completeItems();
        return new OperationsDashboard(
                lowStockProducts.size(), shipmentExceptions.size(), liveReports.size(),
                lowStockProducts, shipmentExceptions, liveReports
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

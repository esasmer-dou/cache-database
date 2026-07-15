package com.example.cachedb.sample.web;

import com.example.cachedb.sample.domain.GeneratedCacheModule;
import com.example.cachedb.sample.domain.ReportJobEntity;
import com.example.cachedb.sample.domain.SupportTicketEntity;
import com.example.cachedb.sample.readmodel.OrderReadModels;
import com.example.cachedb.sample.readmodel.ProductReadModels;
import com.example.cachedb.sample.readmodel.ShipmentReadModels;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final GeneratedCacheModule.Scope domain;

    public DashboardController(GeneratedCacheModule.Scope domain) {
        this.domain = domain;
    }

    @GetMapping("/commerce")
    public DashboardResponse commerce(@RequestParam(defaultValue = "25") int limit) {
        int safeLimit = ApiLimits.requireInRange("limit", limit, 1, 100);
        List<OrderReadModels.OrderSummary> highValueOrders = domain.orders().projections().orderSummary().query(
                QueryFilter.gte("priority_score", 60.0),
                safeLimit,
                QuerySort.desc("priority_score"),
                QuerySort.desc("order_date")
        );
        List<SupportTicketEntity> openTickets = domain.supportTickets().queries().openTickets(safeLimit);
        double totalAmount = highValueOrders.stream()
                .map(OrderReadModels.OrderSummary::orderAmount)
                .filter(value -> value != null)
                .mapToDouble(java.math.BigDecimal::doubleValue)
                .sum();
        return new DashboardResponse(highValueOrders.size(), totalAmount, openTickets.size(), highValueOrders, openTickets);
    }

    @GetMapping("/operations")
    public OperationsDashboardResponse operations(@RequestParam(defaultValue = "25") int limit) {
        int safeLimit = ApiLimits.requireInRange("limit", limit, 1, 100);
        List<ProductReadModels.ProductAvailability> lowStockProducts = domain.products().projections().productAvailability().query(
                QueryFilter.eq("stock_status", "LOW_STOCK"),
                safeLimit,
                QuerySort.desc("updated_at")
        );
        List<ShipmentReadModels.ShipmentSummary> shipmentExceptions = domain.shipments().projections().shipmentSummary().query(
                domain.shipments().queries().shipmentExceptionsQuery(safeLimit)
        );
        List<ReportJobEntity> liveReportJobs = domain.reportJobs().queries().liveReportJobs(safeLimit);
        return new OperationsDashboardResponse(
                lowStockProducts.size(),
                shipmentExceptions.size(),
                liveReportJobs.size(),
                lowStockProducts,
                shipmentExceptions,
                liveReportJobs
        );
    }

    public record DashboardResponse(
            int highlightedOrders,
            double highlightedAmount,
            int openTickets,
            List<OrderReadModels.OrderSummary> orders,
            List<SupportTicketEntity> tickets
    ) {
    }

    public record OperationsDashboardResponse(
            int lowStockProducts,
            int shipmentExceptions,
            int liveReportJobs,
            List<ProductReadModels.ProductAvailability> products,
            List<ShipmentReadModels.ShipmentSummary> shipments,
            List<ReportJobEntity> reports
    ) {
    }
}

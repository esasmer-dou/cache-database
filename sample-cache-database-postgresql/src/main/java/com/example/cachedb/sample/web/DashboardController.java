package com.example.cachedb.sample.web;

import com.example.cachedb.sample.domain.ReportJobEntity;
import com.example.cachedb.sample.domain.ReportJobEntityCacheBinding;
import com.example.cachedb.sample.domain.ShipmentEntityCacheBinding;
import com.example.cachedb.sample.domain.SupportTicketEntity;
import com.example.cachedb.sample.domain.SupportTicketEntityCacheBinding;
import com.example.cachedb.sample.readmodel.OrderReadModels;
import com.example.cachedb.sample.readmodel.ProductReadModels;
import com.example.cachedb.sample.readmodel.ShipmentReadModels;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.api.ProjectionRepository;
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

    private final ProjectionRepository<OrderReadModels.OrderSummary, Long> orderSummaryRepository;
    private final ProjectionRepository<ProductReadModels.ProductAvailability, Long> productAvailabilityRepository;
    private final ProjectionRepository<ShipmentReadModels.ShipmentSummary, Long> shipmentSummaryRepository;
    private final EntityRepository<SupportTicketEntity, Long> ticketRepository;
    private final EntityRepository<ReportJobEntity, Long> reportJobRepository;

    public DashboardController(
            ProjectionRepository<OrderReadModels.OrderSummary, Long> orderSummaryRepository,
            ProjectionRepository<ProductReadModels.ProductAvailability, Long> productAvailabilityRepository,
            ProjectionRepository<ShipmentReadModels.ShipmentSummary, Long> shipmentSummaryRepository,
            EntityRepository<SupportTicketEntity, Long> ticketRepository,
            EntityRepository<ReportJobEntity, Long> reportJobRepository
    ) {
        this.orderSummaryRepository = orderSummaryRepository;
        this.productAvailabilityRepository = productAvailabilityRepository;
        this.shipmentSummaryRepository = shipmentSummaryRepository;
        this.ticketRepository = ticketRepository;
        this.reportJobRepository = reportJobRepository;
    }

    @GetMapping("/commerce")
    public DashboardResponse commerce(@RequestParam(defaultValue = "25") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<OrderReadModels.OrderSummary> highValueOrders = orderSummaryRepository.query(
                QueryFilter.gte("priority_score", 60.0),
                safeLimit,
                QuerySort.desc("priority_score"),
                QuerySort.desc("order_date")
        );
        List<SupportTicketEntity> openTickets = SupportTicketEntityCacheBinding.openTickets(ticketRepository, safeLimit);
        double totalAmount = highValueOrders.stream()
                .map(OrderReadModels.OrderSummary::orderAmount)
                .filter(value -> value != null)
                .mapToDouble(Double::doubleValue)
                .sum();
        return new DashboardResponse(highValueOrders.size(), totalAmount, openTickets.size(), highValueOrders, openTickets);
    }

    @GetMapping("/operations")
    public OperationsDashboardResponse operations(@RequestParam(defaultValue = "25") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<ProductReadModels.ProductAvailability> lowStockProducts = productAvailabilityRepository.query(
                QueryFilter.eq("stock_status", "LOW_STOCK"),
                safeLimit,
                QuerySort.desc("updated_at")
        );
        List<ShipmentReadModels.ShipmentSummary> shipmentExceptions = ShipmentEntityCacheBinding.shipmentExceptions(
                shipmentSummaryRepository,
                safeLimit
        );
        List<ReportJobEntity> liveReportJobs = ReportJobEntityCacheBinding.liveReportJobs(reportJobRepository, safeLimit);
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

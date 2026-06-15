package com.example.cachedb.sample.web;

import com.example.cachedb.sample.domain.SupportTicketEntity;
import com.example.cachedb.sample.domain.SupportTicketEntityCacheBinding;
import com.example.cachedb.sample.readmodel.OrderReadModels;
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
    private final EntityRepository<SupportTicketEntity, Long> ticketRepository;

    public DashboardController(
            ProjectionRepository<OrderReadModels.OrderSummary, Long> orderSummaryRepository,
            EntityRepository<SupportTicketEntity, Long> ticketRepository
    ) {
        this.orderSummaryRepository = orderSummaryRepository;
        this.ticketRepository = ticketRepository;
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

    public record DashboardResponse(
            int highlightedOrders,
            double highlightedAmount,
            int openTickets,
            List<OrderReadModels.OrderSummary> orders,
            List<SupportTicketEntity> tickets
    ) {
    }
}

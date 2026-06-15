package com.example.cachedb.sample.web;

import com.example.cachedb.sample.domain.OrderEntity;
import com.example.cachedb.sample.domain.OrderEntityCacheBinding;
import com.example.cachedb.sample.readmodel.OrderReadModels;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.api.ProjectionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final EntityRepository<OrderEntity, Long> orderRepository;
    private final ProjectionRepository<OrderReadModels.OrderSummary, Long> orderSummaryRepository;
    private final JdbcTemplate jdbcTemplate;

    public OrderController(
            EntityRepository<OrderEntity, Long> orderRepository,
            ProjectionRepository<OrderReadModels.OrderSummary, Long> orderSummaryRepository,
            JdbcTemplate jdbcTemplate
    ) {
        this.orderRepository = orderRepository;
        this.orderSummaryRepository = orderSummaryRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping
    public OrderEntity create(@RequestBody CreateOrderRequest request) {
        if (request.customerId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "customerId is required");
        }
        waitForDurableCustomer(request.customerId());
        OrderEntity entity = new OrderEntity();
        entity.orderId = request.orderId();
        entity.customerId = request.customerId();
        entity.orderDate = request.orderDate() == null ? Instant.now().getEpochSecond() : request.orderDate();
        entity.orderAmount = request.orderAmount();
        entity.currencyCode = request.currencyCode() == null ? "USD" : request.currencyCode();
        entity.orderType = request.orderType() == null ? "STANDARD" : request.orderType();
        entity.status = request.status() == null ? "NEW" : request.status();
        entity.lineCount = request.lineCount() == null ? 0 : request.lineCount();
        entity.priorityScore = priorityScore(entity);
        return orderRepository.save(entity);
    }

    @GetMapping("/{orderId}")
    public OrderEntity detail(
            @PathVariable long orderId,
            @RequestParam(defaultValue = "5") int linePreview
    ) {
        return OrderEntityCacheBinding
                .linePreviewRepository(orderRepository, clamp(linePreview, 1, 50))
                .findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
    }

    @GetMapping("/high-value")
    public List<OrderReadModels.OrderSummary> highValue(
            @RequestParam(defaultValue = "500") double minimumAmount,
            @RequestParam(defaultValue = "25") int limit
    ) {
        return OrderEntityCacheBinding.recentHighValueOrders(
                orderSummaryRepository,
                minimumAmount,
                clamp(limit, 1, 1_000)
        );
    }

    @PatchMapping("/{orderId}/status")
    public OrderEntity updateStatus(@PathVariable long orderId, @RequestBody UpdateStatusRequest request) {
        OrderEntity entity = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        entity.status = request.status();
        entity.priorityScore = priorityScore(entity);
        return orderRepository.save(entity);
    }

    @DeleteMapping("/{orderId}")
    public DeleteResponse delete(@PathVariable long orderId) {
        orderRepository.deleteById(orderId);
        return new DeleteResponse(orderId, "DELETE_ACCEPTED");
    }

    private double priorityScore(OrderEntity entity) {
        double amountScore = entity.orderAmount == null ? 0.0 : entity.orderAmount / 10.0;
        double expressScore = "EXPRESS".equals(entity.orderType) ? 25.0 : 0.0;
        double statusScore = "PAID".equals(entity.status) ? 10.0 : 0.0;
        return amountScore + expressScore + statusScore;
    }

    private void waitForDurableCustomer(long customerId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sample_customers WHERE customer_id = ?",
                    Long.class,
                    customerId
            );
            if (count != null && count > 0) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Customer is not durable in SQL yet. Retry after the write-behind worker flushes the parent row."
        );
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    public record CreateOrderRequest(
            Long orderId,
            Long customerId,
            Long orderDate,
            Double orderAmount,
            String currencyCode,
            String orderType,
            String status,
            Integer lineCount
    ) {
    }

    public record UpdateStatusRequest(String status) {
    }

    public record DeleteResponse(Long orderId, String status) {
    }
}

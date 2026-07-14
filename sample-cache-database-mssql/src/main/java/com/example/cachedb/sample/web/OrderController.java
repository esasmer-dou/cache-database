package com.example.cachedb.sample.web;

import com.example.cachedb.sample.domain.OrderEntity;
import com.example.cachedb.sample.domain.OrderEntityCacheBinding;
import com.example.cachedb.sample.domain.OrderLineEntity;
import com.example.cachedb.sample.readmodel.OrderReadModels;
import com.example.cachedb.sample.service.DurableReferenceGuard;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.api.ProjectionRepository;
import com.reactor.cachedb.core.query.QueryFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final String ARCHIVE_SQL = """
            SELECT order_id, customer_id, order_date, order_amount, currency_code, order_type, status, line_count, priority_score
            FROM sample_orders
            WHERE customer_id = ?
              AND (order_date < ? OR (order_date = ? AND order_id < ?))
            ORDER BY order_date DESC, order_id DESC
            OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY
            """;

    private final EntityRepository<OrderEntity, Long> orderRepository;
    private final EntityRepository<OrderLineEntity, Long> orderLineRepository;
    private final ProjectionRepository<OrderReadModels.OrderSummary, Long> orderSummaryRepository;
    private final JdbcTemplate jdbcTemplate;
    private final DurableReferenceGuard durableReferenceGuard;

    public OrderController(
            EntityRepository<OrderEntity, Long> orderRepository,
            EntityRepository<OrderLineEntity, Long> orderLineRepository,
            ProjectionRepository<OrderReadModels.OrderSummary, Long> orderSummaryRepository,
            JdbcTemplate jdbcTemplate,
            DurableReferenceGuard durableReferenceGuard
    ) {
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.orderSummaryRepository = orderSummaryRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.durableReferenceGuard = durableReferenceGuard;
    }

    @PostMapping
    public ResponseEntity<WriteAccepted<OrderEntity>> create(@Valid @RequestBody CreateOrderRequest request) {
        durableReferenceGuard.requireCustomer(request.customerId());
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
        OrderEntity saved = orderRepository.save(entity);
        return ResponseEntity.accepted().body(WriteAccepted.of("CREATE", "OrderEntity", saved.orderId, saved));
    }

    @GetMapping("/{orderId}")
    public OrderEntity detail(
            @PathVariable long orderId,
            @RequestParam(defaultValue = "5") int linePreview
    ) {
        return OrderEntityCacheBinding
                .linePreviewRepository(orderRepository, ApiLimits.requireInRange("linePreview", linePreview, 1, 50))
                .findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
    }

    @GetMapping("/high-value")
    public List<OrderReadModels.OrderSummary> highValue(
            @RequestParam(defaultValue = "500.00") BigDecimal minimumAmount,
            @RequestParam(defaultValue = "25") int limit
    ) {
        return OrderEntityCacheBinding.recentHighValueOrders(
                orderSummaryRepository,
                minimumAmount,
                ApiLimits.requireInRange("limit", limit, 1, 1_000)
        );
    }

    @GetMapping("/archive")
    public List<OrderReadModels.OrderSummary> archiveFromSql(
            @RequestParam long customerId,
            @RequestParam(required = false) Long beforeOrderDate,
            @RequestParam(required = false) Long beforeOrderId,
            @RequestParam(defaultValue = "100") int limit
    ) {
        long upperBound = beforeOrderDate == null ? Long.MAX_VALUE : beforeOrderDate;
        long upperId = beforeOrderId == null ? Long.MAX_VALUE : beforeOrderId;
        int safeLimit = ApiLimits.requireInRange("limit", limit, 1, 500);
        return jdbcTemplate.query(
                ARCHIVE_SQL,
                (resultSet, rowNumber) -> new OrderReadModels.OrderSummary(
                        resultSet.getLong("order_id"),
                        resultSet.getLong("customer_id"),
                        resultSet.getLong("order_date"),
                        resultSet.getBigDecimal("order_amount"),
                        resultSet.getString("currency_code"),
                        resultSet.getString("order_type"),
                        resultSet.getString("status"),
                        resultSet.getInt("line_count"),
                        resultSet.getDouble("priority_score")
                ),
                customerId,
                upperBound,
                upperBound,
                upperId,
                safeLimit
        );
    }

    @PatchMapping("/{orderId}/status")
    public ResponseEntity<WriteAccepted<OrderEntity>> updateStatus(
            @PathVariable long orderId,
            @Valid @RequestBody UpdateStatusRequest request
    ) {
        OrderEntity entity = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        entity.status = request.status();
        entity.priorityScore = priorityScore(entity);
        OrderEntity saved = orderRepository.save(entity);
        return ResponseEntity.accepted().body(WriteAccepted.of("UPDATE", "OrderEntity", saved.orderId, saved));
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<WriteAccepted<Void>> delete(@PathVariable long orderId) {
        durableReferenceGuard.requireOrder(orderId);
        if (durableReferenceGuard.orderHasDurableLines(orderId)
                || !orderLineRepository.query(QueryFilter.eq("order_id", orderId), 1).isEmpty()) {
            throw new SampleConflictException(
                    "Order " + orderId + " has order lines. This sample permits leaf-order deletion only; "
                            + "implement an explicit transactional cascade command for aggregate deletion."
            );
        }
        orderRepository.deleteById(orderId);
        return ResponseEntity.accepted().body(WriteAccepted.of("DELETE", "OrderEntity", orderId, null));
    }

    private double priorityScore(OrderEntity entity) {
        double amountScore = entity.orderAmount == null
                ? 0.0
                : entity.orderAmount.divide(BigDecimal.TEN).doubleValue();
        double expressScore = "EXPRESS".equals(entity.orderType) ? 25.0 : 0.0;
        double statusScore = "PAID".equals(entity.status) ? 10.0 : 0.0;
        return amountScore + expressScore + statusScore;
    }

    public record CreateOrderRequest(
            @NotNull @Positive Long orderId,
            @NotNull @Positive Long customerId,
            Long orderDate,
            @NotNull @DecimalMin("0.00") BigDecimal orderAmount,
            @Size(min = 3, max = 3) String currencyCode,
            @Size(max = 32) String orderType,
            @Size(max = 32) String status,
            @PositiveOrZero Integer lineCount
    ) {
    }

    public record UpdateStatusRequest(@NotBlank @Size(max = 32) String status) {
    }
}

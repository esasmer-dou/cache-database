package com.example.cachedb.sample.web;

import com.example.cachedb.sample.application.order.OrderApplicationService;
import com.example.cachedb.sample.domain.OrderEntity;
import com.example.cachedb.sample.readmodel.OrderSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
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
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderApplicationService orders;

    public OrderController(OrderApplicationService orders) {
        this.orders = orders;
    }

    @PostMapping
    public ResponseEntity<WriteAccepted<OrderEntity>> create(@Valid @RequestBody CreateOrderRequest request) {
        var receipt = orders.create(new OrderApplicationService.CreateOrder(
                request.orderId(),
                request.customerId(),
                request.orderDate(),
                request.orderAmount(),
                request.currencyCode(),
                request.orderType(),
                request.status(),
                request.lineCount()
        ));
        return ResponseEntity.accepted().body(WriteAccepted.from("CREATE", "OrderEntity", receipt));
    }

    @GetMapping("/{orderId}")
    public OrderEntity detail(
            @PathVariable long orderId,
            @RequestParam(defaultValue = "5") int linePreview
    ) {
        int safePreview = ApiLimits.requireInRange("linePreview", linePreview, 1, 50);
        return orders.detail(orderId, safePreview);
    }

    @GetMapping("/high-value")
    public List<OrderSummary> highValue(
            @RequestParam(defaultValue = "500.00") BigDecimal minimumAmount,
            @RequestParam(defaultValue = "25") int limit
    ) {
        return orders.highValue(minimumAmount, ApiLimits.requireInRange("limit", limit, 1, 1_000));
    }

    @GetMapping("/archive")
    public List<OrderSummary> archiveFromSql(
            @RequestParam long customerId,
            @RequestParam(required = false) Long beforeOrderDate,
            @RequestParam(required = false) Long beforeOrderId,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return orders.archive(
                customerId,
                beforeOrderDate == null ? Long.MAX_VALUE : beforeOrderDate,
                beforeOrderId == null ? Long.MAX_VALUE : beforeOrderId,
                ApiLimits.requireInRange("limit", limit, 1, 500)
        );
    }

    @PatchMapping("/{orderId}/status")
    public ResponseEntity<WriteAccepted<OrderEntity>> updateStatus(
            @PathVariable long orderId,
            @Valid @RequestBody UpdateStatusRequest request
    ) {
        return ResponseEntity.accepted().body(WriteAccepted.from(
                "UPDATE",
                "OrderEntity",
                orders.updateStatus(orderId, request.status())
        ));
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<WriteAccepted<OrderEntity>> deactivate(@PathVariable long orderId) {
        return ResponseEntity.accepted().body(WriteAccepted.from(
                "SOFT_DELETE",
                "OrderEntity",
                orders.deactivate(orderId)
        ));
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

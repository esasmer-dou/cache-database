package com.example.cachedb.sample.application.order;

import com.example.cachedb.sample.application.SampleHotLookups;
import com.example.cachedb.sample.domain.OrderEntity;
import com.example.cachedb.sample.readmodel.OrderSummary;
import com.example.cachedb.sample.repository.OrderRepository;
import com.example.cachedb.sample.service.DurableReferenceGuard;
import com.example.cachedb.sample.service.SampleDomainPolicies;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.repository.CursorPage;
import com.reactor.cachedb.core.repository.WindowRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public final class OrderApplicationService {

    private final OrderRepository orders;
    private final DurableReferenceGuard references;
    private final Clock clock;

    public OrderApplicationService(
            OrderRepository orders,
            DurableReferenceGuard references,
            Clock clock
    ) {
        this.orders = orders;
        this.references = references;
        this.clock = clock;
    }

    public WriteReceipt<OrderEntity, Long> create(CreateOrder command) {
        DurableReferenceGuard.DurableReference customer = references.requireCustomer(command.customerId());
        OrderEntity entity = new OrderEntity();
        entity.orderId = command.orderId();
        entity.customerId = command.customerId();
        entity.orderDate = command.orderDate() == null ? Instant.now(clock).getEpochSecond() : command.orderDate();
        entity.orderAmount = command.orderAmount();
        entity.currencyCode = defaultText(command.currencyCode(), "USD");
        entity.orderType = defaultText(command.orderType(), "STANDARD");
        entity.status = defaultText(command.status(), "NEW");
        entity.lineCount = command.lineCount() == null ? 0 : command.lineCount();
        entity.priorityScore = SampleDomainPolicies.orderPriority(entity);
        return customer.save(orders, entity);
    }

    public OrderEntity detail(long orderId, int linePreview) {
        return SampleHotLookups.require("Order", orderId, orders.detail(orderId, linePreview));
    }

    public CursorPage<OrderSummary> highValue(BigDecimal minimumAmount, int limit, String after) {
        return orders.recentHighValue(minimumAmount, WindowRequest.of(limit, after));
    }

    public CursorPage<OrderSummary> archive(
            long customerId,
            long beforeOrderDate,
            long beforeOrderId,
            int limit,
            String after
    ) {
        return orders.archive(
                customerId,
                beforeOrderDate,
                beforeOrderId,
                WindowRequest.of(limit, after)
        );
    }

    public WriteReceipt<OrderEntity, Long> updateStatus(long orderId, String status) {
        return orders.updateHot(orderId, entity -> {
            entity.status = status;
            entity.priorityScore = SampleDomainPolicies.orderPriority(entity);
            return entity;
        });
    }

    /**
     * Aggregate roots are not physically deleted through asynchronous
     * write-behind. A status transition is race-free and preserves children.
     */
    public WriteReceipt<OrderEntity, Long> deactivate(long orderId) {
        return updateStatus(orderId, "DELETED");
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record CreateOrder(
            Long orderId,
            Long customerId,
            Long orderDate,
            BigDecimal orderAmount,
            String currencyCode,
            String orderType,
            String status,
            Integer lineCount
    ) {
    }

}

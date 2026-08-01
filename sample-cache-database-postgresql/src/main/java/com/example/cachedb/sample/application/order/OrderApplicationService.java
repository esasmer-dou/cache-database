package com.example.cachedb.sample.application.order;

import com.example.cachedb.sample.application.SampleEntityNotFoundException;
import com.example.cachedb.sample.domain.GeneratedCacheModule;
import com.example.cachedb.sample.domain.OrderEntity;
import com.example.cachedb.sample.readmodel.OrderSummary;
import com.example.cachedb.sample.readmodel.OrderSummaryProjection;
import com.example.cachedb.sample.service.DurableReferenceGuard;
import com.example.cachedb.sample.service.SampleDomainPolicies;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.page.VersionedEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public final class OrderApplicationService {

    private final GeneratedCacheModule.Scope domain;
    private final DurableReferenceGuard references;
    private final Clock clock;

    public OrderApplicationService(
            GeneratedCacheModule.Scope domain,
            DurableReferenceGuard references,
            Clock clock
    ) {
        this.domain = domain;
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
        return customer.save(domain.orders().repository(), entity);
    }

    public OrderEntity detail(long orderId, int linePreview) {
        return domain.orders().fetches().linePreview(linePreview).findById(orderId)
                .orElseThrow(() -> new SampleEntityNotFoundException("Order", orderId));
    }

    public List<OrderSummary> highValue(BigDecimal minimumAmount, int limit) {
        return domain.orders().queries().recentHighValueOrdersProjection(minimumAmount, limit);
    }

    public List<OrderSummary> archive(long customerId, long beforeOrderDate, long beforeOrderId, int limit) {
        return domain.orders().queries()
                .customerOrderArchiveSource(customerId, beforeOrderDate, beforeOrderId, limit)
                .stream()
                .map(OrderSummaryProjection::fromEntity)
                .toList();
    }

    public WriteReceipt<OrderEntity, Long> updateStatus(long orderId, String status) {
        VersionedEntity<OrderEntity> current = requireCurrent(orderId);
        OrderEntity entity = current.entity();
        entity.status = status;
        entity.priorityScore = SampleDomainPolicies.orderPriority(entity);
        return domain.orders().save(entity, current.version());
    }

    /**
     * Aggregate roots are not physically deleted through asynchronous
     * write-behind. A status transition is race-free and preserves children.
     */
    public WriteReceipt<OrderEntity, Long> deactivate(long orderId) {
        return updateStatus(orderId, "DELETED");
    }

    private VersionedEntity<OrderEntity> requireCurrent(long orderId) {
        return domain.orders().findVersionedById(orderId)
                .orElseThrow(() -> new SampleEntityNotFoundException("Order", orderId));
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

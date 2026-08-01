package com.example.cachedb.sample.application.customer;

import com.example.cachedb.sample.application.SampleEntityNotFoundException;
import com.example.cachedb.sample.domain.CustomerEntity;
import com.example.cachedb.sample.domain.GeneratedCacheModule;
import com.example.cachedb.sample.readmodel.OrderSummary;
import com.reactor.cachedb.core.model.WriteReceipt;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public final class CustomerApplicationService {

    private final GeneratedCacheModule.Scope domain;
    private final Clock clock;

    public CustomerApplicationService(GeneratedCacheModule.Scope domain, Clock clock) {
        this.domain = domain;
        this.clock = clock;
    }

    public WriteReceipt<CustomerEntity, Long> create(CreateCustomer command) {
        long now = Instant.now(clock).getEpochSecond();
        CustomerEntity entity = new CustomerEntity();
        entity.customerId = command.customerId();
        entity.taxNumber = command.taxNumber();
        entity.customerType = command.customerType();
        entity.segment = command.segment();
        entity.status = defaultText(command.status(), "ACTIVE");
        entity.createdAt = now;
        entity.updatedAt = now;
        return domain.customers().saveWithReceipt(entity);
    }

    public CustomerEntity detail(long customerId, int orderPreview) {
        return domain.customers().fetches().ordersPreview(orderPreview).findById(customerId)
                .orElseThrow(() -> new SampleEntityNotFoundException("Customer", customerId));
    }

    public List<OrderSummary> orderTimeline(long customerId, int limit) {
        return domain.orders().queries().customerTimelineProjection(customerId, limit);
    }

    public List<CustomerEntity> active(int limit) {
        return domain.customers().queries().activeCustomers(limit);
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record CreateCustomer(
            Long customerId,
            String taxNumber,
            String customerType,
            String segment,
            String status
    ) {
    }
}

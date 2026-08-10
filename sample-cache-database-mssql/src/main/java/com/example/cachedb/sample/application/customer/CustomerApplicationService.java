package com.example.cachedb.sample.application.customer;

import com.example.cachedb.sample.application.SampleHotLookups;
import com.example.cachedb.sample.domain.CustomerEntity;
import com.example.cachedb.sample.readmodel.OrderSummary;
import com.example.cachedb.sample.repository.CustomerRepository;
import com.example.cachedb.sample.repository.OrderRepository;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.repository.WindowRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public final class CustomerApplicationService {

    private final CustomerRepository customers;
    private final OrderRepository orders;
    private final Clock clock;

    public CustomerApplicationService(CustomerRepository customers, OrderRepository orders, Clock clock) {
        this.customers = customers;
        this.orders = orders;
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
        return customers.save(entity);
    }

    public CustomerEntity detail(long customerId, int orderPreview) {
        return SampleHotLookups.require("Customer", customerId, customers.detail(customerId, orderPreview));
    }

    public List<OrderSummary> orderTimeline(long customerId, int limit) {
        return orders.customerTimeline(customerId, WindowRequest.first(limit)).completeItems();
    }

    public List<CustomerEntity> active(int limit) {
        return customers.active(limit).completeItems();
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

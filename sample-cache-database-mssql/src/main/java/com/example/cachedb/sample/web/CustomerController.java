package com.example.cachedb.sample.web;

import com.example.cachedb.sample.domain.CustomerEntity;
import com.example.cachedb.sample.domain.CustomerEntityCacheBinding;
import com.example.cachedb.sample.domain.OrderEntityCacheBinding;
import com.example.cachedb.sample.readmodel.OrderReadModels;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.api.ProjectionRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final EntityRepository<CustomerEntity, Long> customerRepository;
    private final ProjectionRepository<OrderReadModels.OrderSummary, Long> orderSummaryRepository;

    public CustomerController(
            EntityRepository<CustomerEntity, Long> customerRepository,
            ProjectionRepository<OrderReadModels.OrderSummary, Long> orderSummaryRepository
    ) {
        this.customerRepository = customerRepository;
        this.orderSummaryRepository = orderSummaryRepository;
    }

    @PostMapping
    public ResponseEntity<WriteAccepted<CustomerEntity>> create(@Valid @RequestBody CreateCustomerRequest request) {
        CustomerEntity entity = new CustomerEntity();
        entity.customerId = request.customerId();
        entity.taxNumber = request.taxNumber();
        entity.customerType = request.customerType();
        entity.segment = request.segment();
        entity.status = request.status() == null ? "ACTIVE" : request.status();
        entity.createdAt = Instant.now().getEpochSecond();
        entity.updatedAt = entity.createdAt;
        CustomerEntity saved = customerRepository.save(entity);
        return ResponseEntity.accepted().body(WriteAccepted.of("CREATE", "CustomerEntity", saved.customerId, saved));
    }

    @GetMapping("/{customerId}")
    public CustomerEntity detail(
            @PathVariable long customerId,
            @RequestParam(defaultValue = "5") int orderPreview
    ) {
        return CustomerEntityCacheBinding
                .ordersPreviewRepository(
                        customerRepository,
                        ApiLimits.requireInRange("orderPreview", orderPreview, 1, 25)
                )
                .findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + customerId));
    }

    @GetMapping("/{customerId}/orders")
    public List<OrderReadModels.OrderSummary> orderTimeline(
            @PathVariable long customerId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return OrderEntityCacheBinding.customerTimeline(
                orderSummaryRepository,
                customerId,
                ApiLimits.requireInRange("limit", limit, 1, 1_000)
        );
    }

    @GetMapping("/active")
    public List<CustomerEntity> active(@RequestParam(defaultValue = "25") int limit) {
        return CustomerEntityCacheBinding.activeCustomers(
                customerRepository,
                ApiLimits.requireInRange("limit", limit, 1, 100)
        );
    }

    public record CreateCustomerRequest(
            @NotNull @Positive Long customerId,
            @NotBlank @Size(max = 64) String taxNumber,
            @NotBlank @Size(max = 32) String customerType,
            @Size(max = 32) String segment,
            @Size(max = 32) String status
    ) {
    }
}

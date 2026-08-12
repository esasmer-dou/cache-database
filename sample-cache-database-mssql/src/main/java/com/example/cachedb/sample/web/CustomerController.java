package com.example.cachedb.sample.web;

import com.example.cachedb.sample.application.customer.CustomerApplicationService;
import com.example.cachedb.sample.domain.CustomerEntity;
import com.example.cachedb.sample.readmodel.OrderSummary;
import com.reactor.cachedb.core.repository.CursorPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
@Validated
public class CustomerController {

    private final CustomerApplicationService customers;

    public CustomerController(CustomerApplicationService customers) {
        this.customers = customers;
    }

    @PostMapping
    public ResponseEntity<WriteAccepted<CustomerEntity>> create(@Valid @RequestBody CreateCustomerRequest request) {
        var receipt = customers.create(new CustomerApplicationService.CreateCustomer(
                request.customerId(), request.taxNumber(), request.customerType(),
                request.segment(), request.status()
        ));
        return ResponseEntity.accepted().body(WriteAccepted.from("CREATE", "CustomerEntity", receipt));
    }

    @GetMapping("/{customerId}")
    public CustomerEntity detail(
            @PathVariable @Positive long customerId,
            @RequestParam(defaultValue = "5") @Min(1) @Max(25) int orderPreview
    ) {
        return customers.detail(customerId, orderPreview);
    }

    @GetMapping("/{customerId}/orders")
    public CursorPage<OrderSummary> orderTimeline(
            @PathVariable @Positive long customerId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(1_000) int limit,
            @RequestParam(required = false) @Size(max = 8_192) String after
    ) {
        return customers.orderTimeline(customerId, limit, after);
    }

    @GetMapping("/active")
    public List<CustomerEntity> active(@RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit) {
        return customers.active(limit);
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

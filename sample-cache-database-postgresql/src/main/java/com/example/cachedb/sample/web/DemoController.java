package com.example.cachedb.sample.web;

import com.example.cachedb.sample.application.demo.DemoDataApplicationService;
import com.reactor.cachedb.spring.boot.CacheDistributedJobSnapshot;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
@Validated
public class DemoController {

    private final DemoDataApplicationService demoData;

    public DemoController(DemoDataApplicationService demoData) {
        this.demoData = demoData;
    }

    @PostMapping("/seed")
    public ResponseEntity<CacheDistributedJobSnapshot> seed(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int customers,
            @RequestParam(defaultValue = "40") @Min(1) @Max(1_000) int ordersPerCustomer,
            @RequestParam(defaultValue = "4") @Min(1) @Max(100) int linesPerOrder
    ) {
        return ResponseEntity.accepted().body(demoData.seed(customers, ordersPerCustomer, linesPerOrder));
    }
}

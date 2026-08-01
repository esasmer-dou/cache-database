package com.example.cachedb.sample.web;

import com.example.cachedb.sample.application.demo.DemoDataApplicationService;
import com.reactor.cachedb.spring.boot.CacheDistributedJobSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
public class DemoController {

    private final DemoDataApplicationService demoData;

    public DemoController(DemoDataApplicationService demoData) {
        this.demoData = demoData;
    }

    @PostMapping("/seed")
    public ResponseEntity<CacheDistributedJobSnapshot> seed(
            @RequestParam(defaultValue = "20") int customers,
            @RequestParam(defaultValue = "40") int ordersPerCustomer,
            @RequestParam(defaultValue = "4") int linesPerOrder
    ) {
        return ResponseEntity.accepted().body(demoData.seed(
                ApiLimits.requireInRange("customers", customers, 1, 100),
                ApiLimits.requireInRange("ordersPerCustomer", ordersPerCustomer, 1, 1_000),
                ApiLimits.requireInRange("linesPerOrder", linesPerOrder, 1, 100)
        ));
    }
}

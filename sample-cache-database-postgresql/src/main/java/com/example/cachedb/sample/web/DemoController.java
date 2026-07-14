package com.example.cachedb.sample.web;

import com.example.cachedb.sample.service.SampleSeedService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
public class DemoController {

    private final SampleSeedService seedService;

    public DemoController(SampleSeedService seedService) {
        this.seedService = seedService;
    }

    @PostMapping("/seed")
    public SampleSeedService.SeedResult seed(
            @RequestParam(defaultValue = "20") int customers,
            @RequestParam(defaultValue = "40") int ordersPerCustomer,
            @RequestParam(defaultValue = "4") int linesPerOrder
    ) {
        return seedService.seed(customers, ordersPerCustomer, linesPerOrder);
    }
}

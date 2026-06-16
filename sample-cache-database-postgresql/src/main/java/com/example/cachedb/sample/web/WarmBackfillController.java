package com.example.cachedb.sample.web;

import com.example.cachedb.sample.service.SampleWarmBackfillService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/warm")
public class WarmBackfillController {

    private final SampleWarmBackfillService warmBackfillService;

    public WarmBackfillController(SampleWarmBackfillService warmBackfillService) {
        this.warmBackfillService = warmBackfillService;
    }

    @PostMapping("/orders/customer/{customerId}")
    public SampleWarmBackfillService.WarmResult warmCustomerOrders(
            @PathVariable long customerId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "false") boolean projectionOnly,
            @RequestParam(defaultValue = "false") boolean dryRun
    ) {
        return warmBackfillService.warmCustomerOrders(customerId, limit, projectionOnly, dryRun);
    }
}

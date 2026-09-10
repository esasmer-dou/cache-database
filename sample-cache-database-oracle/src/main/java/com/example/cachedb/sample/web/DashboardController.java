package com.example.cachedb.sample.web;

import com.example.cachedb.sample.application.dashboard.DashboardQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@Validated
public class DashboardController {

    private final DashboardQueryService dashboards;

    public DashboardController(DashboardQueryService dashboards) {
        this.dashboards = dashboards;
    }

    @GetMapping("/commerce")
    public DashboardQueryService.CommerceDashboard commerce(
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit
    ) {
        return dashboards.commerce(limit);
    }

    @GetMapping("/operations")
    public DashboardQueryService.OperationsDashboard operations(
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit
    ) {
        return dashboards.operations(limit);
    }
}

package com.example.cachedb.sample.web;

import com.example.cachedb.sample.application.dashboard.DashboardQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardQueryService dashboards;

    public DashboardController(DashboardQueryService dashboards) {
        this.dashboards = dashboards;
    }

    @GetMapping("/commerce")
    public DashboardQueryService.CommerceDashboard commerce(@RequestParam(defaultValue = "25") int limit) {
        return dashboards.commerce(ApiLimits.requireInRange("limit", limit, 1, 100));
    }

    @GetMapping("/operations")
    public DashboardQueryService.OperationsDashboard operations(@RequestParam(defaultValue = "25") int limit) {
        return dashboards.operations(ApiLimits.requireInRange("limit", limit, 1, 100));
    }
}

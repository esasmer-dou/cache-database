package com.example.cachedb.sample.config;

import com.example.cachedb.sample.domain.GeneratedCacheModule;
import com.reactor.cachedb.spring.boot.CacheScheduledWarm;
import com.reactor.cachedb.starter.CacheWarmPlan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class SampleScheduledWarmPlans {

    private final GeneratedCacheModule.Scope domain;
    private final int orderWarmMaxRows;

    public SampleScheduledWarmPlans(
            GeneratedCacheModule.Scope domain,
            @Value("${sample.scheduled-warm.orders.warm-max-rows:1000}") int orderWarmMaxRows
    ) {
        this.domain = domain;
        this.orderWarmMaxRows = Math.max(1, orderWarmMaxRows);
    }

    @CacheScheduledWarm(
            name = "sample-active-order-window",
            enabledString = "${sample.scheduled-warm.enabled:true}",
            fixedDelayString = "${sample.scheduled-warm.orders.fixed-delay:PT15M}",
            initialDelayString = "${sample.scheduled-warm.orders.initial-delay:PT30S}",
            lockAtMostForString = "${sample.scheduled-warm.orders.lock-at-most-for:PT2M}",
            lockWaitTimeoutString = "${sample.scheduled-warm.orders.lock-wait-timeout:PT20S}",
            minimumIntervalString = "${sample.scheduled-warm.orders.minimum-interval:PT15M}",
            reconcileHotSet = true,
            reconcileMaxRowsPerRunString = "${sample.scheduled-warm.orders.reconcile-max-rows:10000}",
            reconcileScanCountString = "${sample.scheduled-warm.orders.reconcile-scan-count:500}"
    )
    public CacheWarmPlan activeOrderWindow() {
        long cutoffEpochSeconds = Instant.now().minus(Duration.ofDays(90)).getEpochSecond();
        return domain.orders().warmPlan(
                "sample-active-order-window",
                domain.orders().queries().activeOrderWindowQuery(cutoffEpochSeconds, orderWarmMaxRows),
                orderWarmMaxRows
        );
    }
}

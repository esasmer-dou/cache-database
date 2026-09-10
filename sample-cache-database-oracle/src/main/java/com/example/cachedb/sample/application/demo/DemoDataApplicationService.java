package com.example.cachedb.sample.application.demo;

import com.example.cachedb.sample.service.SampleSeedJobHandler;
import com.reactor.cachedb.spring.boot.CacheDistributedJobExecutor;
import com.reactor.cachedb.spring.boot.CacheDistributedJobSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
public final class DemoDataApplicationService {

    private final CacheDistributedJobExecutor jobs;

    public DemoDataApplicationService(CacheDistributedJobExecutor jobs) {
        this.jobs = jobs;
    }

    public CacheDistributedJobSnapshot seed(int customers, int ordersPerCustomer, int linesPerOrder) {
        return jobs.submit(
                SampleSeedJobHandler.DEFINITION,
                new SampleSeedJobHandler.Arguments(customers, ordersPerCustomer, linesPerOrder)
        );
    }
}

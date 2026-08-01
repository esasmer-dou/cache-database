package com.example.cachedb.sample.service;

import com.reactor.cachedb.spring.boot.CacheDistributedJobContext;
import com.reactor.cachedb.spring.boot.CacheDistributedJobHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
public final class SampleSeedJobHandler implements CacheDistributedJobHandler<SampleSeedJobHandler.Arguments> {

    public static final String ROUTE = "sample.demo.seed";

    private final SampleSeedService seedService;

    public SampleSeedJobHandler(SampleSeedService seedService) {
        this.seedService = seedService;
    }

    @Override
    public String route() {
        return ROUTE;
    }

    @Override
    public Class<Arguments> argumentType() {
        return Arguments.class;
    }

    @Override
    public Object execute(Arguments arguments, CacheDistributedJobContext context) {
        context.checkpoint(Map.of("phase", "SEEDING", "attempt", context.attempt()));
        SampleSeedService.SeedResult result = seedService.seed(
                arguments.customers(),
                arguments.ordersPerCustomer(),
                arguments.linesPerOrder()
        );
        context.checkpoint(Map.of("phase", "COMPLETED", "attempt", context.attempt()));
        return result;
    }

    public record Arguments(int customers, int ordersPerCustomer, int linesPerOrder) {
    }
}

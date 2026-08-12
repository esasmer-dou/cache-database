package com.example.cachedb.sample.service;

import com.reactor.cachedb.spring.boot.CacheDistributedJobContext;
import com.reactor.cachedb.spring.boot.CacheDistributedJobDefinition;
import com.reactor.cachedb.spring.boot.CacheDistributedJobHandler;
import com.reactor.cachedb.spring.boot.CacheDistributedJobProgress;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
public final class SampleSeedJobHandler implements CacheDistributedJobHandler.Typed<SampleSeedJobHandler.Arguments> {

    public static final CacheDistributedJobDefinition<Arguments> DEFINITION =
            CacheDistributedJobDefinition.of("sample.demo.seed", Arguments.class);

    private final SampleSeedService seedService;

    public SampleSeedJobHandler(SampleSeedService seedService) {
        this.seedService = seedService;
    }

    @Override
    public CacheDistributedJobDefinition<Arguments> definition() {
        return DEFINITION;
    }

    @Override
    public Object execute(Arguments arguments, CacheDistributedJobContext context) {
        context.checkpoint(CacheDistributedJobProgress.phase("SEEDING", context.attempt()));
        SampleSeedService.SeedResult result = seedService.seed(
                arguments.customers(),
                arguments.ordersPerCustomer(),
                arguments.linesPerOrder()
        );
        context.checkpoint(CacheDistributedJobProgress.completed(context.attempt()));
        return result;
    }

    public record Arguments(int customers, int ordersPerCustomer, int linesPerOrder) {
    }
}

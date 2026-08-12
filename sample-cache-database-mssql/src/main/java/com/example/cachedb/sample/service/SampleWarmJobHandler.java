package com.example.cachedb.sample.service;

import com.example.cachedb.sample.application.warm.SampleWarmCommand;
import com.reactor.cachedb.spring.boot.CacheDistributedJobContext;
import com.reactor.cachedb.spring.boot.CacheDistributedJobDefinition;
import com.reactor.cachedb.spring.boot.CacheDistributedJobHandler;
import com.reactor.cachedb.spring.boot.CacheDistributedJobProgress;
import com.reactor.cachedb.starter.CacheWarmSummary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
public final class SampleWarmJobHandler implements CacheDistributedJobHandler.Typed<SampleWarmCommand> {

    public static final CacheDistributedJobDefinition<SampleWarmCommand> DEFINITION =
            CacheDistributedJobDefinition.of("sample.route.warm", SampleWarmCommand.class);

    private final SampleWarmBackfillService warmService;

    public SampleWarmJobHandler(SampleWarmBackfillService warmService) {
        this.warmService = warmService;
    }

    @Override
    public CacheDistributedJobDefinition<SampleWarmCommand> definition() {
        return DEFINITION;
    }

    @Override
    public Object execute(SampleWarmCommand command, CacheDistributedJobContext context) {
        checkpoint(context, command, "WARMING");
        CacheWarmSummary result = warmService.execute(command);
        checkpoint(context, command, "COMPLETED");
        return result;
    }

    private void checkpoint(
            CacheDistributedJobContext context,
            SampleWarmCommand command,
            String phase
    ) {
        context.checkpoint(CacheDistributedJobProgress.phase(phase, context.attempt())
                .withAttribute("route", command.route().name()));
    }
}

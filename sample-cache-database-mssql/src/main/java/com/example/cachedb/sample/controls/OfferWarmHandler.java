package com.example.cachedb.sample.controls;

import com.example.cachedb.sample.domain.SupplierOfferCacheBinding;
import com.reactor.cachedb.annotations.CacheJobHandler;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.spring.boot.CacheDistributedJobContext;
import com.reactor.cachedb.spring.boot.CacheDistributedJobDefinition;
import com.reactor.cachedb.starter.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
public final class OfferWarmHandler {
    public static final CacheDistributedJobDefinition<OfferWarmCommand> DEFINITION =
            CacheDistributedJobDefinition.of("sample.offers.warm", OfferWarmCommand.class);
    private final CacheDatabase database;
    public OfferWarmHandler(CacheDatabase database) { this.database = database; }
    @CacheJobHandler("sample.offers.warm")
    public CacheWarmResult execute(CacheDistributedJobContext context, OfferWarmCommand command) {
        return database.warm(CacheWarmPlan.builder(SupplierOfferCacheBinding.METADATA.entityName())
                .name("supplier-offer-window")
                .querySpec(QuerySpec.where(QueryFilter.eq("supplier", command.supplier()))
                        .orderBy(QuerySort.asc("offer_id")).limitTo(command.maxRows()).withQueryTimeoutSeconds(15))
                .maxRows(command.maxRows()).build());
    }
}

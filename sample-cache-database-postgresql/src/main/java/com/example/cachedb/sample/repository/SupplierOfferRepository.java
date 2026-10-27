package com.example.cachedb.sample.repository;

import com.example.cachedb.sample.domain.SupplierOffer;
import com.reactor.cachedb.annotations.*;
import com.reactor.cachedb.core.query.AggregateFunction;
import com.reactor.cachedb.core.repository.CacheDbRepository;
import java.math.BigDecimal;
import java.util.Map;

@CacheRepository(entity = SupplierOffer.class)
public interface SupplierOfferRepository extends CacheDbRepository<SupplierOffer, Long> {
    @CacheFacet(groupBy = "status")
    Map<String, Long> countsBySupplier(String supplier);

    @CacheFacet(groupBy = "status", function = AggregateFunction.SUM, field = "unit_price")
    Map<String, BigDecimal> priceTotalsBySupplier(String supplier);

    @CacheAggregate(function = AggregateFunction.SUM, field = "unit_price")
    BigDecimal sumPriceBySupplierAndStatus(String supplier, String status);

    @CachePatch
    com.reactor.cachedb.core.model.WriteReceipt<SupplierOffer, Long> replaceUnits(
            @CacheId Long offerId, @CacheColumn("available_units") Integer units);
}

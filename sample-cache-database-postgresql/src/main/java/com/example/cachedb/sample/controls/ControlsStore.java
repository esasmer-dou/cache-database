package com.example.cachedb.sample.controls;

import com.example.cachedb.sample.domain.*;
import com.example.cachedb.sample.readmodel.*;
import com.example.cachedb.sample.repository.SupplierOfferRepository;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.api.ProjectionRepository;
import com.reactor.cachedb.core.api.CacheWriteBatchReceipt;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.starter.CacheDatabase;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;

/** Small infrastructure adapter for the bulk/partition APIs; generated facets stay in the repository. */
@Service
public final class ControlsStore {
    private final CacheDatabase database;
    private final SupplierOfferRepository generatedOffers;
    private final EntityRepository<SupplierOffer, Long> offers;
    private final EntityRepository<TenantStock, Long> stock;
    private final ProjectionRepository<TenantStockCard, Long> cards;

    public ControlsStore(CacheDatabase database, SupplierOfferRepository generatedOffers) {
        this.database = database;
        this.generatedOffers = generatedOffers;
        offers = SupplierOfferCacheBinding.repository(database);
        stock = TenantStockCacheBinding.repository(database);
        // Bootstrap registers metadata only; request reads still require an authenticated tenant scope.
        try (var bootstrap = com.reactor.cachedb.core.tenant.CacheTenantContext.administrative()) {
            cards = stock.projected(TenantStockCardProjection.PROJECTION);
        }
    }

    public WriteReceipt<SupplierOffer, Long> saveOffer(SupplierOffer offer) { return generatedOffers.save(offer); }
    public SupplierOffer source(long id) {
        return generatedOffers.findSourceById(id).orElseThrow(() -> new NoSuchElementException("Offer is not in SQL"));
    }
    public WriteReceipt<SupplierOffer, Long> replaceUnits(long id, int units) { return generatedOffers.replaceUnits(id, units); }
    public SupplierOffer offer(long id) {
        return offers.findById(id).orElseThrow(() -> new NoSuchElementException("Offer is not in the active set"));
    }
    public List<SupplierOffer> bulk(List<Long> ids) { return offers.findAll(ids); }
    public List<SupplierOffer> search(String supplier, String status) {
        return offers.query(QuerySpec.where(QueryFilter.eq("supplier", supplier))
                .and(QueryFilter.eq("status", status)).orderBy(QuerySort.asc("offer_id")).limitTo(50));
    }
    public Dashboard dashboard(String supplier) {
        var stats = offers.aggregate(AggregateSpec.sum("unit_price", QueryFilter.eq("supplier", supplier)));
        return new Dashboard(generatedOffers.countsBySupplier(supplier), generatedOffers.priceTotalsBySupplier(supplier),
                generatedOffers.sumPriceBySupplierAndStatus(supplier, "ACTIVE"), stats.decimalAverage());
    }
    public record Dashboard(Map<String, Long> counts, Map<String, BigDecimal> priceTotals,
            BigDecimal activePriceTotal, BigDecimal decimalAverage) {}

    /** A real mixed PUT/PATCH/DELETE transaction; only the independent offer table opts into regrouping. */
    public CacheWriteBatchReceipt changes(SupplierOffer put, long patchId, int units,
            SupplierOffer secondPut, long secondPatchId, int secondUnits, long deleteId) {
        return database.inTransaction(tx -> {
            tx.put(put);
            tx.patch(SupplierOffer.class, patchId, Map.of("available_units", units));
            tx.put(secondPut);
            tx.patch(SupplierOffer.class, secondPatchId, Map.of("available_units", secondUnits));
            tx.delete(SupplierOffer.class, deleteId);
        });
    }
    public WriteReceipt<TenantStock, Long> saveStock(TenantStock row) { return stock.saveWithReceipt(row); }
    public WriteReceipt<TenantStock, Long> patchStock(long id, int units) {
        return stock.patchWithReceipt(id, Map.of("available_units", units));
    }
    public CacheWriteBatchReceipt stockBatch(List<TenantStock> rows) {
        return database.inTransaction(tx -> tx.putAll(rows));
    }
    public TenantStock stock(long id) {
        return stock.findById(id).orElseThrow(() -> new NoSuchElementException("Stock is not in the active set"));
    }
    public Map<String, List<TenantStock>> top(List<String> warehouses, int limit) {
        return stock.queryPartitions(PartitionedQuerySpec.top("warehouse", warehouses, limit,
                QuerySort.desc("available_units")));
    }
    public List<TenantStockCard> cards(int limit) {
        return cards.query(QuerySpec.allOf().orderBy(QuerySort.desc("available_units")).limitTo(limit));
    }
    public static String token(WriteReceipt<?, ?> receipt) {
        try (var causal = CacheCausalContext.withReceipt(receipt.redisNamespace(), receipt)) {
            return CacheCausalContext.encodeToken();
        }
    }
}

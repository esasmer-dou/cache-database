package com.example.cachedb.sample.cacheapi;

import com.example.cachedb.sample.domain.SupplierOffer;
import com.example.cachedb.sample.domain.TenantStock;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.repository.AsyncCacheDbRepository;
import com.reactor.cachedb.core.repository.HotLookup;
import com.reactor.cachedb.reactor.CacheReactorContext;
import com.reactor.cachedb.reactor.ReactiveEntityRepository;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class CacheApiOperations {
    private final AsyncCacheDbRepository<SupplierOffer, Long> offers;
    private final ReactiveEntityRepository<TenantStock, Long> stock;

    public CacheApiOperations(AsyncCacheDbRepository<SupplierOffer, Long> offers,
            ReactiveEntityRepository<TenantStock, Long> stock) {
        this.offers = java.util.Objects.requireNonNull(offers);
        this.stock = java.util.Objects.requireNonNull(stock);
    }
    public CompletableFuture<WriteReceipt<SupplierOffer, Long>> save(SupplierOffer offer) {
        return offers.saveAsync(offer);
    }
    public CompletableFuture<HotLookup<SupplierOffer>> hot(long id) { return offers.findHotByIdAsync(id); }
    public CompletableFuture<Optional<SupplierOffer>> source(long id) { return offers.findSourceByIdAsync(id); }

    public Mono<HotLookup<TenantStock>> stock(long id, String tenant, String token) {
        return stock.findHotById(id).contextWrite(context -> context
                .put(CacheReactorContext.TENANT, tenant).put(CacheReactorContext.CAUSAL_TOKEN, token));
    }
    public Flux<TenantStock> stocks(List<Long> ids, String tenant, String token) {
        if (ids == null || ids.isEmpty() || ids.size() > 32 || ids.stream().anyMatch(id -> id == null || id < 1))
            throw new IllegalArgumentException("Supply 1..32 positive stock IDs");
        return stock.findAll(List.copyOf(ids)).contextWrite(context -> context
                .put(CacheReactorContext.TENANT, tenant).put(CacheReactorContext.CAUSAL_TOKEN, token));
    }
}

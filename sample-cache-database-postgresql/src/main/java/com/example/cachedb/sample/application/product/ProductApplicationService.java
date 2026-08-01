package com.example.cachedb.sample.application.product;

import com.example.cachedb.sample.application.SampleEntityNotFoundException;
import com.example.cachedb.sample.domain.GeneratedCacheModule;
import com.example.cachedb.sample.domain.ProductEntity;
import com.example.cachedb.sample.readmodel.ProductAvailability;
import com.example.cachedb.sample.readmodel.ProductReadModels;
import com.example.cachedb.sample.service.SampleDomainPolicies;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.page.VersionedEntity;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public final class ProductApplicationService {

    private final GeneratedCacheModule.Scope domain;
    private final Clock clock;

    public ProductApplicationService(GeneratedCacheModule.Scope domain, Clock clock) {
        this.domain = domain;
        this.clock = clock;
    }

    public List<ProductAvailability> activeByCategory(String category, int limit) {
        return domain.products().queries().activeProductsByCategoryProjection(category, limit);
    }

    public List<ProductAvailability> lowStock(int limit) {
        return domain.products().queries().lowStockProductsProjection(limit);
    }

    public ProductEntity detail(long productId) {
        return domain.products().findById(productId)
                .orElseThrow(() -> new SampleEntityNotFoundException("Product", productId));
    }

    public List<ProductAvailability> inactiveArchive(int limit) {
        return domain.products().queries().inactiveProductArchiveSource(limit).stream()
                .map(ProductReadModels::fromEntity)
                .toList();
    }

    public WriteReceipt<ProductEntity, Long> updateStock(long productId, UpdateStock command) {
        VersionedEntity<ProductEntity> current = domain.products().findVersionedById(productId)
                .orElseThrow(() -> new SampleEntityNotFoundException("Product", productId));
        ProductEntity entity = current.entity();
        entity.stockQuantity = command.stockQuantity() == null ? entity.stockQuantity : command.stockQuantity();
        entity.reservedQuantity = command.reservedQuantity() == null ? entity.reservedQuantity : command.reservedQuantity();
        entity.stockStatus = command.stockStatus() == null
                ? SampleDomainPolicies.stockStatus(entity)
                : command.stockStatus();
        entity.updatedAt = Instant.now(clock).getEpochSecond();
        return domain.products().save(entity, current.version());
    }

    public record UpdateStock(Integer stockQuantity, Integer reservedQuantity, String stockStatus) {
    }
}

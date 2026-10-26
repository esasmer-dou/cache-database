package com.example.cachedb.sample.application.product;

import com.example.cachedb.sample.application.SampleHotLookups;
import com.example.cachedb.sample.domain.ProductEntity;
import com.example.cachedb.sample.readmodel.ProductAvailability;
import com.example.cachedb.sample.repository.ProductRepository;
import com.example.cachedb.sample.service.SampleDomainPolicies;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.repository.CursorPage;
import com.reactor.cachedb.core.repository.WindowRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public final class ProductApplicationService {

    private final ProductRepository products;
    private final Clock clock;

    public ProductApplicationService(ProductRepository products, Clock clock) {
        this.products = products;
        this.clock = clock;
    }

    public CursorPage<ProductAvailability> activeByCategory(String category, int limit, String after) {
        return products.activeByCategory(category, WindowRequest.of(limit, after));
    }

    public List<ProductAvailability> lowStock(int limit) {
        return products.lowStock(limit).completeItems();
    }

    public ProductEntity detail(long productId) {
        return SampleHotLookups.require("Product", productId, products.detail(productId));
    }

    public CursorPage<ProductAvailability> inactiveArchive(int limit, String after) {
        return products.inactiveArchive(WindowRequest.of(limit, after));
    }

    public WriteReceipt<ProductEntity, Long> updateStock(long productId, UpdateStock command) {
        return products.updateHot(productId, entity -> {
            entity.stockQuantity = command.stockQuantity() == null ? entity.stockQuantity : command.stockQuantity();
            entity.reservedQuantity = command.reservedQuantity() == null
                    ? entity.reservedQuantity
                    : command.reservedQuantity();
            entity.stockStatus = command.stockStatus() == null
                    ? SampleDomainPolicies.stockStatus(entity)
                    : command.stockStatus();
            entity.updatedAt = Instant.now(clock).getEpochSecond();
            return entity;
        });
    }

    public record UpdateStock(Integer stockQuantity, Integer reservedQuantity, String stockStatus) {
    }

    /** Absolute values, not an increment command; unrelated SQL columns stay unchanged. */
    public WriteReceipt<ProductEntity, Long> replaceStock(long productId, int stock, int reserved) {
        if (stock < 0 || reserved < 0 || reserved > stock)
            throw new IllegalArgumentException("Stock must be nonnegative and reserved must not exceed stock");
        int available = stock - reserved;
        String status = available == 0 ? "OUT_OF_STOCK" : available <= 10 ? "LOW_STOCK" : "IN_STOCK";
        return products.patchStockValues(productId, stock, reserved, status, Instant.now(clock).getEpochSecond());
    }
}

package com.example.cachedb.sample.web;

import com.example.cachedb.sample.application.product.ProductApplicationService;
import com.example.cachedb.sample.domain.ProductEntity;
import com.example.cachedb.sample.readmodel.ProductAvailability;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductApplicationService products;

    public ProductController(ProductApplicationService products) {
        this.products = products;
    }

    @GetMapping("/active")
    public List<ProductAvailability> activeByCategory(
            @RequestParam(defaultValue = "electronics") String category,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return products.activeByCategory(category, ApiLimits.requireInRange("limit", limit, 1, 1_000));
    }

    @GetMapping("/low-stock")
    public List<ProductAvailability> lowStock(@RequestParam(defaultValue = "25") int limit) {
        return products.lowStock(ApiLimits.requireInRange("limit", limit, 1, 1_000));
    }

    @GetMapping("/{productId}")
    public ProductEntity detail(@PathVariable long productId) {
        return products.detail(productId);
    }

    @GetMapping("/archive")
    public List<ProductAvailability> inactiveArchive(@RequestParam(defaultValue = "50") int limit) {
        return products.inactiveArchive(ApiLimits.requireInRange("limit", limit, 1, 500));
    }

    @PatchMapping("/{productId}/stock")
    public ResponseEntity<WriteAccepted<ProductEntity>> updateStock(
            @PathVariable long productId,
            @Valid @RequestBody UpdateStockRequest request
    ) {
        var receipt = products.updateStock(productId, new ProductApplicationService.UpdateStock(
                request.stockQuantity(), request.reservedQuantity(), request.stockStatus()
        ));
        return ResponseEntity.accepted().body(WriteAccepted.from("UPDATE", "ProductEntity", receipt));
    }

    public record UpdateStockRequest(
            @PositiveOrZero Integer stockQuantity,
            @PositiveOrZero Integer reservedQuantity,
            @Size(max = 32) String stockStatus
    ) {
    }
}

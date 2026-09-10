package com.example.cachedb.sample.web;

import com.example.cachedb.sample.application.product.ProductApplicationService;
import com.example.cachedb.sample.domain.ProductEntity;
import com.example.cachedb.sample.readmodel.ProductAvailability;
import com.reactor.cachedb.core.repository.CursorPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
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
@Validated
public class ProductController {

    private final ProductApplicationService products;

    public ProductController(ProductApplicationService products) {
        this.products = products;
    }

    @GetMapping("/active")
    public CursorPage<ProductAvailability> activeByCategory(
            @RequestParam(defaultValue = "electronics") @NotBlank @Size(max = 64) String category,
            @RequestParam(defaultValue = "20") @Min(1) @Max(1_000) int limit,
            @RequestParam(required = false) @Size(max = 8_192) String after
    ) {
        return products.activeByCategory(category, limit, after);
    }

    @GetMapping("/low-stock")
    public List<ProductAvailability> lowStock(
            @RequestParam(defaultValue = "25") @Min(1) @Max(1_000) int limit
    ) {
        return products.lowStock(limit);
    }

    @GetMapping("/{productId}")
    public ProductEntity detail(@PathVariable @Positive long productId) {
        return products.detail(productId);
    }

    @GetMapping("/archive")
    public CursorPage<ProductAvailability> inactiveArchive(
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
            @RequestParam(required = false) @Size(max = 8_192) String after
    ) {
        return products.inactiveArchive(limit, after);
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

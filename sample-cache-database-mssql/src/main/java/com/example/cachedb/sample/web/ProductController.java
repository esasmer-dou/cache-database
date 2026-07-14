package com.example.cachedb.sample.web;

import com.example.cachedb.sample.domain.ProductEntity;
import com.example.cachedb.sample.domain.ProductEntityCacheBinding;
import com.example.cachedb.sample.readmodel.ProductReadModels;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.api.ProjectionRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private static final String ARCHIVE_SQL = """
            SELECT product_id, sku, product_name, category, active_status, stock_status,
                   CASE WHEN stock_quantity > reserved_quantity THEN stock_quantity - reserved_quantity ELSE 0 END AS available_quantity,
                   unit_price, updated_at
            FROM sample_products
            WHERE active_status <> 'ACTIVE' OR stock_status = 'OUT_OF_STOCK'
            ORDER BY updated_at DESC, product_id DESC
            OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY
            """;

    private final EntityRepository<ProductEntity, Long> productRepository;
    private final ProjectionRepository<ProductReadModels.ProductAvailability, Long> productAvailabilityRepository;
    private final JdbcTemplate jdbcTemplate;

    public ProductController(
            EntityRepository<ProductEntity, Long> productRepository,
            ProjectionRepository<ProductReadModels.ProductAvailability, Long> productAvailabilityRepository,
            JdbcTemplate jdbcTemplate
    ) {
        this.productRepository = productRepository;
        this.productAvailabilityRepository = productAvailabilityRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/active")
    public List<ProductReadModels.ProductAvailability> activeByCategory(
            @RequestParam(defaultValue = "electronics") String category,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ProductEntityCacheBinding.activeProductsByCategory(
                productAvailabilityRepository,
                category,
                ApiLimits.requireInRange("limit", limit, 1, 1_000)
        );
    }

    @GetMapping("/low-stock")
    public List<ProductReadModels.ProductAvailability> lowStock(@RequestParam(defaultValue = "25") int limit) {
        return ProductEntityCacheBinding.lowStockProducts(
                productAvailabilityRepository,
                ApiLimits.requireInRange("limit", limit, 1, 1_000)
        );
    }

    @GetMapping("/{productId}")
    public ProductEntity detail(@PathVariable long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found in active set: " + productId));
    }

    @GetMapping("/archive")
    public List<ProductReadModels.ProductAvailability> inactiveArchive(@RequestParam(defaultValue = "50") int limit) {
        return jdbcTemplate.query(
                ARCHIVE_SQL,
                (resultSet, rowNumber) -> new ProductReadModels.ProductAvailability(
                        resultSet.getLong("product_id"),
                        resultSet.getString("sku"),
                        resultSet.getString("product_name"),
                        resultSet.getString("category"),
                        resultSet.getString("active_status"),
                        resultSet.getString("stock_status"),
                        resultSet.getInt("available_quantity"),
                        resultSet.getBigDecimal("unit_price"),
                        resultSet.getLong("updated_at")
                ),
                ApiLimits.requireInRange("limit", limit, 1, 500)
        );
    }

    @PatchMapping("/{productId}/stock")
    public ResponseEntity<WriteAccepted<ProductEntity>> updateStock(
            @PathVariable long productId,
            @Valid @RequestBody UpdateStockRequest request
    ) {
        ProductEntity product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found in active set: " + productId));
        product.stockQuantity = request.stockQuantity() == null ? product.stockQuantity : request.stockQuantity();
        product.reservedQuantity = request.reservedQuantity() == null ? product.reservedQuantity : request.reservedQuantity();
        product.stockStatus = request.stockStatus() == null ? stockStatus(product) : request.stockStatus();
        product.updatedAt = Instant.now().getEpochSecond();
        ProductEntity saved = productRepository.save(product);
        return ResponseEntity.accepted().body(WriteAccepted.of("UPDATE", "ProductEntity", saved.productId, saved));
    }

    private String stockStatus(ProductEntity product) {
        int stock = product.stockQuantity == null ? 0 : product.stockQuantity;
        int reserved = product.reservedQuantity == null ? 0 : product.reservedQuantity;
        int available = Math.max(0, stock - reserved);
        if (available == 0) {
            return "OUT_OF_STOCK";
        }
        return available <= 10 ? "LOW_STOCK" : "IN_STOCK";
    }

    public record UpdateStockRequest(
            @PositiveOrZero Integer stockQuantity,
            @PositiveOrZero Integer reservedQuantity,
            @Size(max = 32) String stockStatus
    ) {
    }
}

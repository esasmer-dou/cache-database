package com.example.cachedb.sample.web;

import com.example.cachedb.sample.domain.ProductEntity;
import com.example.cachedb.sample.domain.ProductEntityCacheBinding;
import com.reactor.cachedb.core.api.EntityRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final EntityRepository<ProductEntity, Long> productRepository;

    public ProductController(EntityRepository<ProductEntity, Long> productRepository) {
        this.productRepository = productRepository;
    }

    @GetMapping("/active")
    public List<ProductEntity> activeByCategory(
            @RequestParam(defaultValue = "electronics") String category,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ProductEntityCacheBinding.activeProductsByCategory(
                productRepository,
                category,
                Math.max(1, Math.min(limit, 100))
        );
    }
}

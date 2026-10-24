package com.example.cachedb.sample.catalog;

import com.example.cachedb.sample.domain.ProductEntity;
import com.example.cachedb.sample.domain.ProductEntityCacheBinding;
import com.reactor.cachedb.spring.boot.snapshot.*;
import com.reactor.cachedb.starter.CacheDatabase;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CatalogConfiguration {
    public static final String PLAN = "stock-catalog";

    @Bean
    SnapshotPlan<ProductEntity, CatalogCard> stockCatalog() {
        var source =
                SnapshotSource.entity("products", ProductEntityCacheBinding.SOURCE)
                        .where(SnapshotPredicate.eq("active_status", "ACTIVE"));
        return SnapshotPlan.streaming(
                        PLAN,
                        source,
                        row -> row.productId.toString(),
                        CatalogCard.class,
                        row -> List.of(CatalogCard.from(row)))
                .indexed(
                        SnapshotIndexSpec.of(
                                row -> row.category,
                                row -> Math.max(0, row.stockQuantity - row.reservedQuantity)))
                .scopedBy(source, "product_id", Long::valueOf)
                .withGeneratedJsonCodec()
                .withContentReuse();
    }

    @Bean
    TypedSnapshotRepository<Long, CatalogCard> stockCatalogRepository(
            SnapshotJobs jobs, SnapshotPlan<ProductEntity, CatalogCard> stockCatalog) {
        return stockCatalog.bind(jobs.repository(PLAN), Object::toString);
    }

    @Bean
    CatalogImportService catalogImports(CacheDatabase database) {
        return new CatalogImportService(database::writeBatch, database::awaitDurable);
    }
}

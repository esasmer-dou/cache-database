package com.example.cachedb.sample.cacheapi;

import com.example.cachedb.sample.domain.TenantStockCacheBinding;
import com.example.cachedb.sample.repository.SupplierOfferRepository;
import com.reactor.cachedb.reactor.ReactiveEntityRepositoryFactory;
import com.reactor.cachedb.spring.boot.cache.CacheDbCacheManager;
import com.reactor.cachedb.spring.boot.cache.CacheDbSpringCacheProperties;
import com.reactor.cachedb.starter.CacheDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableCaching
@ConditionalOnProperty(prefix = "sample.cache-api", name = "enabled", havingValue = "true")
public class CacheApiConfiguration {
    @Bean
    MethodCacheService methodCacheService(CacheDbCacheManager manager, CacheDbSpringCacheProperties properties) {
        var configs = properties.configurations();
        for (String name : java.util.List.of("quotes", "nulls")) {
            var config = configs.get(name);
            if (config == null || !config.tenantScoped() || manager.getCache(name) == null)
                throw new IllegalStateException("Cache API requires tenant-scoped quotes and nulls caches");
        }
        if (!configs.get("nulls").allowNullValues())
            throw new IllegalStateException("Cache API nulls cache requires allow-null-values=true");
        return new MethodCacheService();
    }

    @Bean
    CacheApiOperations cacheApiOperations(SupplierOfferRepository offers, CacheDatabase database,
            ReactiveEntityRepositoryFactory factory) {
        var stock = TenantStockCacheBinding.repository(database);
        return new CacheApiOperations(offers.asyncWithReceipt(), factory.from(stock.async()));
    }
}

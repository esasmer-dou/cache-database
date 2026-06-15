package com.example.cachedb.sample.service;

import com.example.cachedb.sample.domain.CustomerEntity;
import com.example.cachedb.sample.domain.CustomerEntityCacheBinding;
import com.example.cachedb.sample.domain.OrderEntity;
import com.example.cachedb.sample.domain.OrderEntityCacheBinding;
import com.example.cachedb.sample.domain.OrderLineEntity;
import com.example.cachedb.sample.domain.OrderLineEntityCacheBinding;
import com.example.cachedb.sample.domain.ProductEntity;
import com.example.cachedb.sample.domain.ProductEntityCacheBinding;
import com.example.cachedb.sample.domain.SupportTicketEntity;
import com.example.cachedb.sample.domain.SupportTicketEntityCacheBinding;
import com.example.cachedb.sample.readmodel.OrderReadModels;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.api.ProjectionRepository;
import com.reactor.cachedb.starter.CacheDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SampleRepositories {

    @Bean
    EntityRepository<CustomerEntity, Long> customerRepository(CacheDatabase cacheDatabase) {
        return CustomerEntityCacheBinding.repository(cacheDatabase);
    }

    @Bean
    EntityRepository<ProductEntity, Long> productRepository(CacheDatabase cacheDatabase) {
        return ProductEntityCacheBinding.repository(cacheDatabase);
    }

    @Bean
    EntityRepository<OrderEntity, Long> orderRepository(CacheDatabase cacheDatabase) {
        return OrderEntityCacheBinding.repository(cacheDatabase);
    }

    @Bean
    EntityRepository<OrderLineEntity, Long> orderLineRepository(CacheDatabase cacheDatabase) {
        return OrderLineEntityCacheBinding.repository(cacheDatabase);
    }

    @Bean
    EntityRepository<SupportTicketEntity, Long> supportTicketRepository(CacheDatabase cacheDatabase) {
        return SupportTicketEntityCacheBinding.repository(cacheDatabase);
    }

    @Bean
    ProjectionRepository<OrderReadModels.OrderSummary, Long> orderSummaryRepository(
            EntityRepository<OrderEntity, Long> orderRepository
    ) {
        return OrderEntityCacheBinding.orderSummary(orderRepository);
    }
}

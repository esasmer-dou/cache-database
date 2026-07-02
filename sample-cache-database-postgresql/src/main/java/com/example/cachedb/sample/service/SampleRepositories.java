package com.example.cachedb.sample.service;

import com.example.cachedb.sample.config.SampleCachePolicies;
import com.example.cachedb.sample.domain.AuditEventEntity;
import com.example.cachedb.sample.domain.AuditEventEntityCacheBinding;
import com.example.cachedb.sample.domain.CustomerEntity;
import com.example.cachedb.sample.domain.CustomerEntityCacheBinding;
import com.example.cachedb.sample.domain.OrderEntity;
import com.example.cachedb.sample.domain.OrderEntityCacheBinding;
import com.example.cachedb.sample.domain.OrderLineEntity;
import com.example.cachedb.sample.domain.OrderLineEntityCacheBinding;
import com.example.cachedb.sample.domain.ProductEntity;
import com.example.cachedb.sample.domain.ProductEntityCacheBinding;
import com.example.cachedb.sample.domain.ReportJobEntity;
import com.example.cachedb.sample.domain.ReportJobEntityCacheBinding;
import com.example.cachedb.sample.domain.ShipmentEntity;
import com.example.cachedb.sample.domain.ShipmentEntityCacheBinding;
import com.example.cachedb.sample.domain.ShipmentEventEntity;
import com.example.cachedb.sample.domain.ShipmentEventEntityCacheBinding;
import com.example.cachedb.sample.domain.SupportTicketEntity;
import com.example.cachedb.sample.domain.SupportTicketEntityCacheBinding;
import com.example.cachedb.sample.readmodel.OrderReadModels;
import com.example.cachedb.sample.readmodel.ProductReadModels;
import com.example.cachedb.sample.readmodel.ShipmentReadModels;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.api.ProjectionRepository;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.starter.CacheDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SampleRepositories {

    @Bean
    EntityRepository<CustomerEntity, Long> customerRepository(CacheDatabase cacheDatabase) {
        CachePolicy policy = SampleCachePolicies.commerceTimelinePolicy();
        CustomerEntityCacheBinding.registerJdbcBacked(cacheDatabase, policy);
        return CustomerEntityCacheBinding.repository(cacheDatabase, policy);
    }

    @Bean
    EntityRepository<ProductEntity, Long> productRepository(CacheDatabase cacheDatabase) {
        CachePolicy policy = SampleCachePolicies.catalogAvailabilityPolicy();
        ProductEntityCacheBinding.registerJdbcBacked(cacheDatabase, policy);
        return ProductEntityCacheBinding.repository(cacheDatabase, policy);
    }

    @Bean
    EntityRepository<OrderEntity, Long> orderRepository(CacheDatabase cacheDatabase) {
        CachePolicy policy = SampleCachePolicies.commerceTimelinePolicy();
        OrderEntityCacheBinding.registerJdbcBacked(cacheDatabase, policy);
        return OrderEntityCacheBinding.repository(cacheDatabase, policy);
    }

    @Bean
    EntityRepository<OrderLineEntity, Long> orderLineRepository(CacheDatabase cacheDatabase) {
        CachePolicy policy = SampleCachePolicies.commerceTimelinePolicy();
        OrderLineEntityCacheBinding.registerJdbcBacked(cacheDatabase, policy);
        return OrderLineEntityCacheBinding.repository(cacheDatabase, policy);
    }

    @Bean
    EntityRepository<SupportTicketEntity, Long> supportTicketRepository(CacheDatabase cacheDatabase) {
        CachePolicy policy = SampleCachePolicies.supportOperationsPolicy();
        SupportTicketEntityCacheBinding.registerJdbcBacked(cacheDatabase, policy);
        return SupportTicketEntityCacheBinding.repository(cacheDatabase, policy);
    }

    @Bean
    EntityRepository<ShipmentEntity, Long> shipmentRepository(CacheDatabase cacheDatabase) {
        CachePolicy policy = SampleCachePolicies.logisticsTrackingPolicy();
        ShipmentEntityCacheBinding.registerJdbcBacked(cacheDatabase, policy);
        return ShipmentEntityCacheBinding.repository(cacheDatabase, policy);
    }

    @Bean
    EntityRepository<ShipmentEventEntity, Long> shipmentEventRepository(CacheDatabase cacheDatabase) {
        CachePolicy policy = SampleCachePolicies.logisticsTrackingPolicy();
        ShipmentEventEntityCacheBinding.registerJdbcBacked(cacheDatabase, policy);
        return ShipmentEventEntityCacheBinding.repository(cacheDatabase, policy);
    }

    @Bean
    EntityRepository<ReportJobEntity, Long> reportJobRepository(CacheDatabase cacheDatabase) {
        CachePolicy policy = SampleCachePolicies.reportingLivePolicy();
        ReportJobEntityCacheBinding.registerJdbcBacked(cacheDatabase, policy);
        return ReportJobEntityCacheBinding.repository(cacheDatabase, policy);
    }

    @Bean
    EntityRepository<AuditEventEntity, Long> auditEventRepository(CacheDatabase cacheDatabase) {
        CachePolicy policy = SampleCachePolicies.auditArchivePolicy();
        AuditEventEntityCacheBinding.registerJdbcBacked(cacheDatabase, policy);
        return AuditEventEntityCacheBinding.repository(cacheDatabase, policy);
    }

    @Bean
    ProjectionRepository<OrderReadModels.OrderSummary, Long> orderSummaryRepository(
            EntityRepository<OrderEntity, Long> orderRepository
    ) {
        return OrderEntityCacheBinding.orderSummary(orderRepository);
    }

    @Bean
    ProjectionRepository<ProductReadModels.ProductAvailability, Long> productAvailabilityRepository(
            EntityRepository<ProductEntity, Long> productRepository
    ) {
        return ProductEntityCacheBinding.productAvailability(productRepository);
    }

    @Bean
    ProjectionRepository<ShipmentReadModels.ShipmentSummary, Long> shipmentSummaryRepository(
            EntityRepository<ShipmentEntity, Long> shipmentRepository
    ) {
        return ShipmentEntityCacheBinding.shipmentSummary(shipmentRepository);
    }
}

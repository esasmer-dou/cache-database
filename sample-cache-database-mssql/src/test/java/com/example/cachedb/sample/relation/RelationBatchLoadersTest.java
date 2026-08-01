package com.example.cachedb.sample.relation;

import com.example.cachedb.sample.domain.CustomerEntity;
import com.example.cachedb.sample.domain.CustomerEntityCacheBinding;
import com.example.cachedb.sample.domain.OrderEntity;
import com.example.cachedb.sample.domain.OrderEntityCacheBinding;
import com.example.cachedb.sample.domain.OrderLineEntity;
import com.example.cachedb.sample.domain.ShipmentEntity;
import com.example.cachedb.sample.domain.ShipmentEntityCacheBinding;
import com.example.cachedb.sample.domain.ShipmentEventEntity;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.RelationConfig;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.PartitionedQuerySpec;
import com.reactor.cachedb.core.relation.RelationBatchContext;
import com.reactor.cachedb.core.relation.RelationBatchLoader;
import com.reactor.cachedb.starter.CacheDatabase;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationBatchLoadersTest {

    @Test
    void customerOrdersAreLoadedInBoundedBatchesInsteadOfOneQueryPerCustomer() {
        EntityRepository<OrderEntity, Long> repository = repositoryReturningEmptyRows();
        List<CustomerEntity> customers = LongStream.rangeClosed(1, 8).mapToObj(id -> {
            CustomerEntity customer = new CustomerEntity();
            customer.customerId = id;
            return customer;
        }).toList();

        customerOrdersLoader(repository).preload(customers, context("orders", 25));

        verify(repository, times(1)).queryPartitions(any(PartitionedQuerySpec.class));
    }

    @Test
    void orderLinesAreLoadedInBoundedBatchesInsteadOfOneQueryPerOrder() {
        EntityRepository<OrderLineEntity, Long> repository = repositoryReturningEmptyRows();
        List<OrderEntity> orders = LongStream.rangeClosed(1, 5).mapToObj(id -> {
            OrderEntity order = new OrderEntity();
            order.orderId = id;
            return order;
        }).toList();

        orderLinesLoader(repository).preload(orders, context("lines", 50));

        verify(repository, times(1)).queryPartitions(any(PartitionedQuerySpec.class));
    }

    @Test
    void shipmentEventsAreLoadedInBoundedBatchesInsteadOfOneQueryPerShipment() {
        EntityRepository<ShipmentEventEntity, Long> repository = repositoryReturningEmptyRows();
        List<ShipmentEntity> shipments = LongStream.rangeClosed(1, 11).mapToObj(id -> {
            ShipmentEntity shipment = new ShipmentEntity();
            shipment.shipmentId = id;
            return shipment;
        }).toList();

        shipmentEventsLoader(repository).preload(shipments, context("events", 20));

        verify(repository, times(1)).queryPartitions(any(PartitionedQuerySpec.class));
    }

    @Test
    void eachCustomerReceivesItsOwnBoundedWindowWithoutGlobalLimitStarvation() {
        EntityRepository<OrderEntity, Long> repository = mock(EntityRepository.class);
        when(repository.queryPartitions(any(PartitionedQuerySpec.class))).thenAnswer(invocation -> {
            PartitionedQuerySpec<Long> spec = invocation.getArgument(0);
            Map<Long, List<OrderEntity>> rows = new LinkedHashMap<>();
            for (Long customerId : spec.partitionValues()) {
                rows.put(customerId, LongStream.rangeClosed(1, spec.limitPerPartition())
                        .mapToObj(sequence -> order(customerId, sequence))
                        .toList());
            }
            return rows;
        });
        List<CustomerEntity> customers = LongStream.rangeClosed(1, 16).mapToObj(id -> {
            CustomerEntity customer = new CustomerEntity();
            customer.customerId = id;
            return customer;
        }).toList();

        customerOrdersLoader(repository).preload(customers, context("orders", 25));

        assertEquals(16, customers.size());
        customers.forEach(customer -> assertEquals(25, customer.orders.size()));
        verify(repository, times(1)).queryPartitions(any(PartitionedQuerySpec.class));
    }

    @Test
    void parentSetsLargerThanTheConfiguredBatchAreChunked() {
        EntityRepository<OrderEntity, Long> repository = repositoryReturningEmptyRows();
        List<CustomerEntity> customers = LongStream.rangeClosed(1, 33).mapToObj(id -> {
            CustomerEntity customer = new CustomerEntity();
            customer.customerId = id;
            return customer;
        }).toList();

        customerOrdersLoader(repository).preload(customers, context("orders", 25));

        verify(repository, times(3)).queryPartitions(any(PartitionedQuerySpec.class));
    }

    private RelationBatchContext context(String relationName, int relationLimit) {
        return new RelationBatchContext(
                FetchPlan.of(relationName).withRelationLimit(relationName, relationLimit),
                RelationConfig.defaults()
        );
    }

    private RelationBatchLoader<CustomerEntity> customerOrdersLoader(EntityRepository<OrderEntity, Long> repository) {
        return CustomerEntityCacheBinding.relationLoader(databaseWith(repository), CachePolicy.defaults());
    }

    private RelationBatchLoader<OrderEntity> orderLinesLoader(EntityRepository<OrderLineEntity, Long> repository) {
        return OrderEntityCacheBinding.relationLoader(databaseWith(repository), CachePolicy.defaults());
    }

    private RelationBatchLoader<ShipmentEntity> shipmentEventsLoader(EntityRepository<ShipmentEventEntity, Long> repository) {
        return ShipmentEntityCacheBinding.relationLoader(databaseWith(repository), CachePolicy.defaults());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private CacheDatabase databaseWith(EntityRepository<?, ?> repository) {
        CacheDatabase database = mock(CacheDatabase.class);
        doReturn(repository).when(database).repository(any(), any());
        return database;
    }

    @SuppressWarnings("unchecked")
    private <T> EntityRepository<T, Long> repositoryReturningEmptyRows() {
        EntityRepository<T, Long> repository = mock(EntityRepository.class);
        when(repository.queryPartitions(any(PartitionedQuerySpec.class))).thenReturn(Map.of());
        return repository;
    }

    private OrderEntity order(long customerId, long sequence) {
        OrderEntity order = new OrderEntity();
        order.orderId = customerId * 10_000L + sequence;
        order.customerId = customerId;
        order.orderDate = sequence;
        return order;
    }
}

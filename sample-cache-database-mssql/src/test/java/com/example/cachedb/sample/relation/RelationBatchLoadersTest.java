package com.example.cachedb.sample.relation;

import com.example.cachedb.sample.domain.CustomerEntity;
import com.example.cachedb.sample.domain.OrderEntity;
import com.example.cachedb.sample.domain.OrderLineEntity;
import com.example.cachedb.sample.domain.ShipmentEntity;
import com.example.cachedb.sample.domain.ShipmentEventEntity;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.config.RelationConfig;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.relation.RelationBatchContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.LongStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

        new CustomerOrdersRelationBatchLoader(repository).preload(customers, context("orders", 25));

        verify(repository, times(2)).query(any(QuerySpec.class));
    }

    @Test
    void orderLinesAreLoadedInBoundedBatchesInsteadOfOneQueryPerOrder() {
        EntityRepository<OrderLineEntity, Long> repository = repositoryReturningEmptyRows();
        List<OrderEntity> orders = LongStream.rangeClosed(1, 5).mapToObj(id -> {
            OrderEntity order = new OrderEntity();
            order.orderId = id;
            return order;
        }).toList();

        new OrderLinesRelationBatchLoader(repository).preload(orders, context("lines", 50));

        verify(repository, times(3)).query(any(QuerySpec.class));
    }

    @Test
    void shipmentEventsAreLoadedInBoundedBatchesInsteadOfOneQueryPerShipment() {
        EntityRepository<ShipmentEventEntity, Long> repository = repositoryReturningEmptyRows();
        List<ShipmentEntity> shipments = LongStream.rangeClosed(1, 11).mapToObj(id -> {
            ShipmentEntity shipment = new ShipmentEntity();
            shipment.shipmentId = id;
            return shipment;
        }).toList();

        new ShipmentEventsRelationBatchLoader(repository).preload(shipments, context("events", 20));

        verify(repository, times(3)).query(any(QuerySpec.class));
    }

    private RelationBatchContext context(String relationName, int relationLimit) {
        return new RelationBatchContext(
                FetchPlan.of(relationName).withRelationLimit(relationName, relationLimit),
                RelationConfig.defaults()
        );
    }

    @SuppressWarnings("unchecked")
    private <T> EntityRepository<T, Long> repositoryReturningEmptyRows() {
        EntityRepository<T, Long> repository = mock(EntityRepository.class);
        when(repository.query(any(QuerySpec.class))).thenReturn(List.of());
        return repository;
    }
}

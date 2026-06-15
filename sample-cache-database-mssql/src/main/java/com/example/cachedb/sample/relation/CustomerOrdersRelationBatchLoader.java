package com.example.cachedb.sample.relation;

import com.example.cachedb.sample.domain.CustomerEntity;
import com.example.cachedb.sample.domain.OrderEntity;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.relation.RelationBatchContext;
import com.reactor.cachedb.core.relation.RelationBatchLoader;

import java.util.List;
import java.util.Objects;

public final class CustomerOrdersRelationBatchLoader implements RelationBatchLoader<CustomerEntity> {

    private static final String RELATION_NAME = "orders";

    private final EntityRepository<OrderEntity, Long> orderRepository;

    public CustomerOrdersRelationBatchLoader(EntityRepository<OrderEntity, Long> orderRepository) {
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository");
    }

    @Override
    public void preload(List<CustomerEntity> entities, RelationBatchContext context) {
        if (entities.isEmpty() || !context.fetchPlan().includes(RELATION_NAME)) {
            return;
        }
        int relationLimit = Math.max(1, Math.min(context.relationLimit(RELATION_NAME), 25));
        for (CustomerEntity customer : entities) {
            if (customer == null || customer.customerId == null) {
                continue;
            }
            customer.orders = orderRepository.query(
                    QueryFilter.eq("customer_id", customer.customerId),
                    relationLimit,
                    QuerySort.desc("order_date"),
                    QuerySort.desc("order_id")
            );
        }
    }
}

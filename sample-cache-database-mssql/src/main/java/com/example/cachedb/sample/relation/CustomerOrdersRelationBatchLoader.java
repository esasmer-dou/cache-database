package com.example.cachedb.sample.relation;

import com.example.cachedb.sample.domain.CustomerEntity;
import com.example.cachedb.sample.domain.OrderEntity;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.relation.RelationBatchContext;
import com.reactor.cachedb.core.relation.RelationBatchLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CustomerOrdersRelationBatchLoader implements RelationBatchLoader<CustomerEntity> {

    private static final String RELATION_NAME = "orders";
    private static final int MAX_ROWS_PER_BATCH = 100;
    private static final int MAX_PARENT_IDS_PER_BATCH = 4;

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
        LinkedHashMap<Long, CustomerEntity> customersById = new LinkedHashMap<>();
        LinkedHashMap<Long, List<OrderEntity>> ordersByCustomerId = new LinkedHashMap<>();
        for (CustomerEntity customer : entities) {
            if (customer == null || customer.customerId == null) {
                continue;
            }
            customersById.put(customer.customerId, customer);
            ordersByCustomerId.put(customer.customerId, new ArrayList<>());
        }
        int parentBatchSize = Math.max(
                1,
                Math.min(MAX_PARENT_IDS_PER_BATCH, MAX_ROWS_PER_BATCH / relationLimit)
        );
        List<Long> customerIds = new ArrayList<>(customersById.keySet());
        for (int start = 0; start < customerIds.size(); start += parentBatchSize) {
            List<Long> chunk = customerIds.subList(start, Math.min(customerIds.size(), start + parentBatchSize));
            List<Object> rawIds = new ArrayList<>(chunk);
            List<OrderEntity> orders = orderRepository.query(
                    QuerySpec.where(QueryFilter.in("customer_id", rawIds))
                            .orderBy(QuerySort.asc("customer_id"), QuerySort.desc("order_date"), QuerySort.desc("order_id"))
                            .limitTo(chunk.size() * relationLimit)
            );
            for (OrderEntity order : orders) {
                if (order == null || order.customerId == null) {
                    continue;
                }
                List<OrderEntity> bucket = ordersByCustomerId.get(order.customerId);
                if (bucket != null && bucket.size() < relationLimit) {
                    bucket.add(order);
                }
            }
        }
        for (Map.Entry<Long, CustomerEntity> entry : customersById.entrySet()) {
            entry.getValue().orders = List.copyOf(ordersByCustomerId.getOrDefault(entry.getKey(), List.of()));
        }
    }
}

package com.example.cachedb.sample.relation;

import com.example.cachedb.sample.domain.OrderEntity;
import com.example.cachedb.sample.domain.OrderLineEntity;
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

public final class OrderLinesRelationBatchLoader implements RelationBatchLoader<OrderEntity> {

    private static final String RELATION_NAME = "lines";
    private static final int MAX_ROWS_PER_BATCH = 100;
    private static final int MAX_PARENT_IDS_PER_BATCH = 2;

    private final EntityRepository<OrderLineEntity, Long> lineRepository;

    public OrderLinesRelationBatchLoader(EntityRepository<OrderLineEntity, Long> lineRepository) {
        this.lineRepository = Objects.requireNonNull(lineRepository, "lineRepository");
    }

    @Override
    public void preload(List<OrderEntity> entities, RelationBatchContext context) {
        if (entities.isEmpty() || !context.fetchPlan().includes(RELATION_NAME)) {
            return;
        }
        int relationLimit = Math.max(1, Math.min(context.relationLimit(RELATION_NAME), 50));
        LinkedHashMap<Long, OrderEntity> ordersById = new LinkedHashMap<>();
        LinkedHashMap<Long, List<OrderLineEntity>> linesByOrderId = new LinkedHashMap<>();
        for (OrderEntity order : entities) {
            if (order == null || order.orderId == null) {
                continue;
            }
            ordersById.put(order.orderId, order);
            linesByOrderId.put(order.orderId, new ArrayList<>());
        }
        List<Long> orderIds = new ArrayList<>(ordersById.keySet());
        int parentBatchSize = Math.max(
                1,
                Math.min(MAX_PARENT_IDS_PER_BATCH, MAX_ROWS_PER_BATCH / relationLimit)
        );
        for (int start = 0; start < orderIds.size(); start += parentBatchSize) {
            List<Long> chunk = orderIds.subList(start, Math.min(orderIds.size(), start + parentBatchSize));
            List<Object> rawIds = new ArrayList<>(chunk);
            List<OrderLineEntity> lines = lineRepository.query(
                    QuerySpec.where(QueryFilter.in("order_id", rawIds))
                            .orderBy(QuerySort.asc("order_id"), QuerySort.asc("line_number"))
                            .limitTo(chunk.size() * relationLimit)
            );
            groupLines(linesByOrderId, lines, relationLimit);
        }
        for (Map.Entry<Long, OrderEntity> entry : ordersById.entrySet()) {
            entry.getValue().lines = List.copyOf(linesByOrderId.getOrDefault(entry.getKey(), List.of()));
        }
    }

    private void groupLines(
            Map<Long, List<OrderLineEntity>> linesByOrderId,
            List<OrderLineEntity> lines,
            int relationLimit
    ) {
        for (OrderLineEntity line : lines) {
            if (line == null || line.orderId == null) {
                continue;
            }
            List<OrderLineEntity> bucket = linesByOrderId.get(line.orderId);
            if (bucket != null && bucket.size() < relationLimit) {
                bucket.add(line);
            }
        }
    }
}

package com.reactor.cachedb.examples.demo;

import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.relation.RelationBatchContext;
import com.reactor.cachedb.core.relation.RelationBatchLoader;
import com.reactor.cachedb.examples.demo.entity.DemoOrderEntity;
import com.reactor.cachedb.examples.demo.entity.DemoOrderLineEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DemoOrderLinesRelationBatchLoader implements RelationBatchLoader<DemoOrderEntity> {

    private static final String RELATION_NAME = "orderLines";
    private static final int ORDER_ID_CHUNK_SIZE = 120;
    private static final int LINE_PAGE_SIZE = 1000;

    private final EntityRepository<DemoOrderLineEntity, Long> orderLineRepository;

    public DemoOrderLinesRelationBatchLoader(EntityRepository<DemoOrderLineEntity, Long> orderLineRepository) {
        this.orderLineRepository = Objects.requireNonNull(orderLineRepository, "orderLineRepository");
    }

    @Override
    public void preload(List<DemoOrderEntity> entities, RelationBatchContext context) {
        FetchPlan fetchPlan = context.fetchPlan();
        if (entities.isEmpty() || !fetchPlan.includes(RELATION_NAME)) {
            return;
        }
        int relationLimit = context.relationLimit(RELATION_NAME);

        LinkedHashMap<Long, DemoOrderEntity> ordersById = new LinkedHashMap<>();
        LinkedHashMap<Long, List<DemoOrderLineEntity>> linesByOrderId = new LinkedHashMap<>();
        for (DemoOrderEntity entity : entities) {
            if (entity == null || entity.id == null) {
                continue;
            }
            entity.orderLines = List.of();
            ordersById.put(entity.id, entity);
            linesByOrderId.put(entity.id, new ArrayList<>());
        }
        if (ordersById.isEmpty()) {
            return;
        }

        ArrayList<Long> orderIds = new ArrayList<>(ordersById.keySet());
        for (int start = 0; start < orderIds.size(); start += ORDER_ID_CHUNK_SIZE) {
            List<Long> chunkOrderIds = orderIds.subList(start, Math.min(orderIds.size(), start + ORDER_ID_CHUNK_SIZE));
            if (relationLimit < Integer.MAX_VALUE) {
                preloadLimitedLines(linesByOrderId, chunkOrderIds, relationLimit);
                continue;
            }
            int offset = 0;
            while (true) {
                List<Object> chunk = new ArrayList<>(chunkOrderIds);
                List<DemoOrderLineEntity> lines = orderLineRepository.query(
                        QuerySpec.where(QueryFilter.in("order_id", chunk))
                                .orderBy(QuerySort.asc("order_id"), QuerySort.asc("line_number"))
                                .offsetBy(offset)
                                .limitTo(LINE_PAGE_SIZE)
                );
                for (DemoOrderLineEntity line : lines) {
                    if (line == null || line.orderId == null) {
                        continue;
                    }
                    List<DemoOrderLineEntity> bucket = linesByOrderId.get(line.orderId);
                    if (bucket != null) {
                        bucket.add(line);
                    }
                }
                if (lines.size() < LINE_PAGE_SIZE) {
                    break;
                }
                offset += LINE_PAGE_SIZE;
            }
        }

        for (Map.Entry<Long, DemoOrderEntity> entry : ordersById.entrySet()) {
            entry.getValue().orderLines = List.copyOf(linesByOrderId.getOrDefault(entry.getKey(), List.of()));
        }
    }

    private void preloadLimitedLines(
            Map<Long, List<DemoOrderLineEntity>> linesByOrderId,
            List<Long> orderIds,
            int relationLimit
    ) {
        for (Long orderId : orderIds) {
            if (orderId == null) {
                continue;
            }
            List<DemoOrderLineEntity> lines = orderLineRepository.query(
                    QueryFilter.eq("order_id", orderId),
                    relationLimit,
                    QuerySort.asc("order_id"),
                    QuerySort.asc("line_number")
            );
            List<DemoOrderLineEntity> bucket = linesByOrderId.get(orderId);
            if (bucket != null) {
                bucket.addAll(lines);
            }
        }
    }
}

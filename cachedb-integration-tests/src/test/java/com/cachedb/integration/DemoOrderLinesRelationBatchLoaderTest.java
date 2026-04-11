package com.reactor.cachedb.integration;

import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.config.RelationConfig;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.QueryExplainPlan;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.examples.demo.DemoOrderLinesRelationBatchLoader;
import com.reactor.cachedb.examples.demo.entity.DemoOrderEntity;
import com.reactor.cachedb.examples.demo.entity.DemoOrderLineEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoOrderLinesRelationBatchLoaderTest {

    @Test
    void shouldLoadAllOrderLinesEvenWhenParentSummaryUnderstatesVolume() {
        List<QuerySpec> observedQueries = new ArrayList<>();
        List<DemoOrderLineEntity> allLines = new ArrayList<>();
        for (int index = 0; index < 2505; index++) {
            DemoOrderLineEntity line = new DemoOrderLineEntity();
            line.id = 10_000L + index;
            line.orderId = 77L;
            line.lineNumber = index + 1;
            line.sku = "SKU-" + index;
            line.quantity = 1;
            line.unitPrice = 3.0;
            line.lineTotal = 3.0;
            line.status = "OPEN";
            allLines.add(line);
        }

        EntityRepository<DemoOrderLineEntity, Long> repository = new EntityRepository<>() {
            @Override
            public Optional<DemoOrderLineEntity> findById(Long id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<DemoOrderLineEntity> findAll(Collection<Long> ids) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<DemoOrderLineEntity> findPage(PageWindow pageWindow) {
                throw new UnsupportedOperationException();
            }

            @Override
            public QueryExplainPlan explain(QuerySpec querySpec) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<DemoOrderLineEntity> query(QuerySpec querySpec) {
                observedQueries.add(querySpec);
                long orderId = Long.parseLong(String.valueOf(querySpec.filters().stream()
                        .filter(filter -> "order_id".equals(filter.column()))
                        .findFirst()
                        .map(QueryFilter::values)
                        .orElseThrow()
                        .get(0)));
                int fromIndex = Math.min(querySpec.offset(), allLines.size());
                int toIndex = Math.min(fromIndex + querySpec.limit(), allLines.size());
                if (orderId != 77L) {
                    return List.of();
                }
                return List.copyOf(allLines.subList(fromIndex, toIndex));
            }

            @Override
            public DemoOrderLineEntity save(DemoOrderLineEntity entity) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void deleteById(Long id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public EntityRepository<DemoOrderLineEntity, Long> withFetchPlan(FetchPlan fetchPlan) {
                return this;
            }
        };

        DemoOrderLinesRelationBatchLoader loader = new DemoOrderLinesRelationBatchLoader(repository);
        DemoOrderEntity order = new DemoOrderEntity();
        order.id = 77L;
        order.lineItemCount = 1;
        order.status = "OPEN";

        loader.preload(
                List.of(order),
                new com.reactor.cachedb.core.relation.RelationBatchContext(FetchPlan.of("orderLines"), RelationConfig.defaults())
        );

        assertEquals(2505, order.orderLines.size());
        assertTrue(observedQueries.size() >= 3);
        assertEquals(0, observedQueries.get(0).offset());
        assertEquals(1000, observedQueries.get(1).offset());
        assertEquals(2000, observedQueries.get(2).offset());
    }

    @Test
    void shouldHonorRelationLimitPerParentWhenFetchPlanRequestsLimitedLines() {
        List<QuerySpec> observedQueries = new ArrayList<>();
        List<DemoOrderLineEntity> allLines = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            DemoOrderLineEntity line = new DemoOrderLineEntity();
            line.id = 20_000L + index;
            line.orderId = 88L;
            line.lineNumber = index + 1;
            line.sku = "SKU-" + index;
            line.quantity = 1;
            line.unitPrice = 5.0;
            line.lineTotal = 5.0;
            line.status = "OPEN";
            allLines.add(line);
        }

        EntityRepository<DemoOrderLineEntity, Long> repository = new EntityRepository<>() {
            @Override
            public Optional<DemoOrderLineEntity> findById(Long id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<DemoOrderLineEntity> findAll(Collection<Long> ids) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<DemoOrderLineEntity> findPage(PageWindow pageWindow) {
                throw new UnsupportedOperationException();
            }

            @Override
            public QueryExplainPlan explain(QuerySpec querySpec) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<DemoOrderLineEntity> query(QuerySpec querySpec) {
                observedQueries.add(querySpec);
                return List.copyOf(allLines.subList(0, Math.min(querySpec.limit(), allLines.size())));
            }

            @Override
            public DemoOrderLineEntity save(DemoOrderLineEntity entity) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void deleteById(Long id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public EntityRepository<DemoOrderLineEntity, Long> withFetchPlan(FetchPlan fetchPlan) {
                return this;
            }
        };

        DemoOrderLinesRelationBatchLoader loader = new DemoOrderLinesRelationBatchLoader(repository);
        DemoOrderEntity order = new DemoOrderEntity();
        order.id = 88L;
        order.lineItemCount = 25;
        order.status = "OPEN";

        loader.preload(
                List.of(order),
                new com.reactor.cachedb.core.relation.RelationBatchContext(
                        FetchPlan.of("orderLines").withRelationLimit("orderLines", 8),
                        RelationConfig.defaults()
                )
        );

        assertEquals(8, order.orderLines.size());
        assertEquals(1, observedQueries.size());
        assertEquals(8, observedQueries.get(0).limit());
        assertEquals(QueryFilter.eq("order_id", 88L), observedQueries.get(0).filters().get(0));
    }
}

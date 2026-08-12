package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySortDirection;
import com.reactor.cachedb.core.query.QuerySpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryApiErgonomicsTest {

    @Test
    void shouldBuildQuerySpecWithFluentShortcuts() {
        QuerySpec querySpec = QuerySpec.where(QueryFilter.eq("status", "ACTIVE"))
                .and(QueryFilter.gte("score", 90))
                .orderBy(QuerySort.desc("score"), QuerySort.asc("id"))
                .offsetBy(20)
                .limitTo(15)
                .fetching(FetchPlan.of("orders").withRelationLimit("orders", 8));

        assertEquals(2, querySpec.filters().size());
        assertEquals(2, querySpec.sorts().size());
        assertEquals("score", querySpec.sorts().get(0).column());
        assertEquals(QuerySortDirection.DESC, querySpec.sorts().get(0).direction());
        assertEquals(20, querySpec.offset());
        assertEquals(15, querySpec.limit());
        assertEquals(8, querySpec.fetchPlan().relationLimit("orders"));
    }

    @Test
    void shouldDelegateConvenienceQueryAndRelationMethods() {
        CapturingRepository repository = new CapturingRepository();

        repository.withRelations("orders", "profile");
        assertEquals(List.of("orders", "profile"), List.copyOf(repository.lastFetchPlan.includes()));

        repository.query(
                QueryFilter.eq("status", "ACTIVE"),
                FetchPlan.of("orders", "profile").withRelationLimit("orders", 5),
                QuerySort.desc("score")
        );

        assertEquals(1, repository.lastQuery.filters().size());
        assertEquals(List.of("orders", "profile"), List.copyOf(repository.lastQuery.fetchPlan().includes()));
        assertEquals(5, repository.lastQuery.fetchPlan().relationLimit("orders"));
        assertEquals("score", repository.lastQuery.sorts().get(0).column());
    }

    @Test
    void shouldKeepWarmPlanModeAndResultCoupled() {
        CacheWarmPlan plan = CacheWarmPlan.builder("OrderEntity")
                .name("customer-orders")
                .maxRows(100)
                .coverage("customer-timeline", "customer:42", 300L)
                .projectionsOnly(true)
                .projectionName("OrderSummary")
                .build();
        CacheWarmResult result = new CacheWarmResult(
                "customer-orders", "OrderEntity", 100, 100, 5L, true, true, List.of()
        );

        CacheWarmExecution execution = new CacheWarmExecution(plan, CacheWarmExecutionMode.DRY_RUN, result);

        assertTrue(execution.dryRun());
        assertTrue(execution.projectionsOnly());
        assertEquals("customer-timeline", execution.routeName());
        assertEquals("customer:42", execution.scope());
        CacheWarmSummary summary = execution.summary("customer-orders");
        assertEquals("customer-orders", summary.operation());
        assertEquals(100, summary.rowsReadFromSource());
        assertEquals(CacheWarmTarget.PROJECTIONS_ONLY, summary.target());
        assertTrue(summary.dryRun());
        assertThrows(IllegalArgumentException.class, () -> new CacheWarmExecution(
                plan,
                CacheWarmExecutionMode.APPLY,
                new CacheWarmResult("other-plan", "OrderEntity", 0, 0, 0L, false, false, List.of())
        ));
    }

    @Test
    void shouldRejectImpossibleWarmRowAccounting() {
        assertThrows(IllegalArgumentException.class, () -> new CacheWarmResult(
                "warm-orders", "OrderEntity", 10, 11, 1L, true, true, List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> new CacheWarmResult(
                "warm-orders", "OrderEntity", -1, 0, 1L, true, true, List.of()
        ));
    }

    @Test
    void durableBatchWriterBatchesAndAppliesPendingReceiptBackpressure() {
        AtomicInteger durabilityWaits = new AtomicInteger();
        CacheDurableBatchWriter<String, Long> writer = new CacheDurableBatchWriter<>(
                "test seed",
                2,
                4,
                batch -> {
                    ArrayList<WriteReceipt<String, Long>> receipts = new ArrayList<>(batch.size());
                    long id = 1;
                    for (String entity : batch) {
                        receipts.add(new WriteReceipt<>(entity, id++, "test", OperationType.UPSERT, id, Instant.now()));
                    }
                    return receipts;
                },
                (receipts, operation) -> {
                    assertEquals("test seed", operation);
                    durabilityWaits.incrementAndGet();
                }
        );

        writer.addAll(List.of("a", "b", "c", "d", "e"));
        CacheDurableBatchResult result = writer.finish();

        assertEquals(5, result.submittedRows());
        assertEquals(3, result.writeBatches());
        assertEquals(2, result.durabilityWaits());
        assertEquals(2, durabilityWaits.get());
        assertThrows(IllegalStateException.class, () -> writer.add("late"));
    }

    private static final class CapturingRepository implements EntityRepository<String, Long> {
        private QuerySpec lastQuery = QuerySpec.builder().build();
        private FetchPlan lastFetchPlan = FetchPlan.empty();

        @Override
        public Optional<String> findById(Long id) {
            return Optional.empty();
        }

        @Override
        public List<String> findAll(Collection<Long> ids) {
            return List.of();
        }

        @Override
        public List<String> findPage(com.reactor.cachedb.core.cache.PageWindow pageWindow) {
            return List.of();
        }

        @Override
        public com.reactor.cachedb.core.query.QueryExplainPlan explain(QuerySpec querySpec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> query(QuerySpec querySpec) {
            this.lastQuery = querySpec;
            return List.of();
        }

        @Override
        public String save(String entity) {
            return entity;
        }

        @Override
        public void deleteById(Long id) {
        }

        @Override
        public EntityRepository<String, Long> withFetchPlan(FetchPlan fetchPlan) {
            this.lastFetchPlan = fetchPlan;
            return this;
        }
    }
}

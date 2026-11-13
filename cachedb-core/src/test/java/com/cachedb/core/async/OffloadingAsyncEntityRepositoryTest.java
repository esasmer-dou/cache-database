package com.reactor.cachedb.core.async;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.page.VersionedEntity;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.repository.*;
import com.reactor.cachedb.core.trace.TraceContexts;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class OffloadingAsyncEntityRepositoryTest {
    @Test void delegatesEveryEntityOperationWithoutChangingReturnContracts() throws Exception {
        var delegate = new Rows();
        try (var executor = new CacheAsyncExecutor(8, TraceContexts.threadLocal())) {
            var repository = delegate.async(executor);
            assertEquals(Optional.of("row"), repository.findByIdAsync(1).get(5, TimeUnit.SECONDS));
            assertEquals("row", repository.findHotByIdAsync(1).get(5, TimeUnit.SECONDS).optional().orElseThrow());
            assertEquals(List.of("row", "row"), repository.findAllAsync(List.of(1, 1)).get(5, TimeUnit.SECONDS));
            assertEquals("saved", repository.saveAsync("saved").get(5, TimeUnit.SECONDS));
            assertNull(repository.deleteAsync(1).get(5, TimeUnit.SECONDS));
            assertEquals(List.of("row"), repository.queryAsync(QuerySpec.builder().limit(1).build()).get(5, TimeUnit.SECONDS));
            assertEquals(1, repository.aggregateAsync(AggregateSpec.count(null)).get(5, TimeUnit.SECONDS).count());
            assertEquals(1, repository.aggregateByAsync("name", AggregateSpec.count(null))
                    .get(5, TimeUnit.SECONDS).get("group").count());
            assertEquals(1, delegate.deletes.get());
            assertThrows(UnsupportedOperationException.class, delegate::async);
            assertTrue(executor.awaitIdle(Duration.ofSeconds(5)));
        }
    }

    @Test void snapshotsIdsAndRejectsOversizeInputsAndResultsBeforeReturningPartialData() throws Exception {
        var queued = new ArrayBlockingQueue<Runnable>(2);
        try (var executor = new CacheAsyncExecutor(2, queued::add, TraceContexts.threadLocal())) {
            var delegate = new Rows();
            var repository = delegate.async(executor);
            var ids = new ArrayList<>(List.of(1, 1));
            var result = repository.findAllAsync(ids);
            ids.clear();
            queued.remove().run();
            assertEquals(List.of("row", "row"), result.get(5, TimeUnit.SECONDS));
            assertThrows(IllegalArgumentException.class,
                    () -> repository.findAllAsync(Collections.nCopies(QuerySpec.MAX_LIMIT + 1, 1)));
            assertThrows(NullPointerException.class, () -> repository.findAllAsync(Arrays.asList(1, null)));
            var bad = new Rows() {
                @Override public List<String> query(QuerySpec spec) { return List.of("one", "two"); }
            }.async(executor).queryAsync(QuerySpec.builder().limit(1).build());
            queued.remove().run();
            assertInstanceOf(IllegalArgumentException.class, assertThrows(CompletionException.class, bad::join).getCause());
            assertEquals(2, executor.snapshot().availablePermits());
        }
    }

    @Test void cacheSessionDefaultRoutesThroughRepositoryAsyncOverload() throws Exception {
        try (var executor = new CacheAsyncExecutor(2, TraceContexts.threadLocal())) {
            var delegate = new Rows();
            CacheSession session = new CacheSession() {
                public CacheAsyncExecutor asyncExecutor() { return executor; }
                @SuppressWarnings("unchecked")
                public <T, ID> EntityRepository<T, ID> repository(EntityMetadata<T, ID> m, EntityCodec<T> c) {
                    return (EntityRepository<T, ID>) delegate;
                }
                public <T, ID> EntityRepository<T, ID> repository(EntityMetadata<T, ID> m, EntityCodec<T> c,
                        com.reactor.cachedb.core.cache.CachePolicy policy) { return repository(m, c); }
            };
            AsyncEntityRepository<String, Integer> repository = session.asyncEntityRepository(null, null);
            assertEquals(Optional.of("row"), repository.findByIdAsync(1).get(5, TimeUnit.SECONDS));
        }
    }

    @Test void applicationAdapterPreservesActualSaveDeleteAndOptimisticReceipts() throws Exception {
        var upsert = new WriteReceipt<>("accepted", 1, "rows", OperationType.UPSERT, 7, null);
        WriteReceipt<String, Integer> delete = new WriteReceipt<>(null, 1, "rows", OperationType.DELETE, 8, null);
        CacheDbRepository<String, Integer> delegate = new CacheDbRepository<>() {
            public HotLookup<String> findHotById(Integer id) { return HotLookup.notCached(); }
            public Optional<VersionedEntity<String>> findVersionedHotById(Integer id) { return Optional.empty(); }
            public Optional<String> findSourceById(Integer id) { return Optional.of("source"); }
            public WriteReceipt<String, Integer> save(String entity) { return upsert; }
            public WriteReceipt<String, Integer> save(String entity, long expected) { assertEquals(6, expected); return upsert; }
            public WriteReceipt<String, Integer> saveAfter(String entity, WriteDependency dependency) {
                assertEquals(upsert.asDependency(), dependency); return upsert;
            }
            public List<WriteReceipt<String, Integer>> saveAll(Collection<String> rows) { return List.of(upsert); }
            public WriteReceipt<String, Integer> deleteById(Integer id) { return delete; }
            public boolean isDurable(WriteReceipt<?, ?> receipt) { return false; }
            public boolean awaitDurable(WriteReceipt<?, ?> receipt, Duration timeout) { throw new AssertionError("No implicit SQL wait"); }
        };
        try (var executor = new CacheAsyncExecutor(8, TraceContexts.threadLocal())) {
            var repository = delegate.asyncWithReceipt(executor);
            assertTrue(upsert.version() > 0);
            assertEquals("accepted", upsert.entity());
            assertSame(upsert, repository.saveAsync("accepted").get(5, TimeUnit.SECONDS));
            assertSame(upsert, repository.saveAsync("accepted", 6).get(5, TimeUnit.SECONDS));
            assertSame(upsert, repository.saveAfterAsync("accepted", upsert.asDependency()).get(5, TimeUnit.SECONDS));
            assertSame(upsert, repository.saveAllAsync(List.of("accepted")).get(5, TimeUnit.SECONDS).get(0));
            assertSame(delete, repository.deleteAsync(1).get(5, TimeUnit.SECONDS));
            assertEquals(Optional.of("source"), repository.findSourceByIdAsync(1).get(5, TimeUnit.SECONDS));
            assertTrue(repository.findHotByIdAsync(1).get(5, TimeUnit.SECONDS).optional().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> repository.saveAllAsync(Collections.nCopies(1001, "x")));
            assertThrows(UnsupportedOperationException.class, delegate::asyncWithReceipt);
        }
    }

    static class Rows implements EntityRepository<String, Integer> {
        final AtomicInteger deletes = new AtomicInteger();
        public Optional<String> findById(Integer id) { return Optional.of("row"); }
        public List<String> findAll(Collection<Integer> ids) { return ids.stream().map(id -> "row").toList(); }
        public List<String> findPage(PageWindow page) { throw new UnsupportedOperationException(); }
        public QueryExplainPlan explain(QuerySpec spec) { throw new UnsupportedOperationException(); }
        public List<String> query(QuerySpec spec) { return List.of("row"); }
        public String save(String entity) { return entity; }
        public void deleteById(Integer id) { deletes.incrementAndGet(); }
        public EntityRepository<String, Integer> withFetchPlan(FetchPlan plan) { return this; }
        public AggregateResult aggregate(AggregateSpec spec) {
            var accumulator = new AggregateResult.Accumulator(); accumulator.add(null); return accumulator.result();
        }
        public Map<String, AggregateResult> aggregateBy(String column, AggregateSpec spec) {
            return Map.of("group", aggregate(spec));
        }
    }
}

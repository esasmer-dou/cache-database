package com.reactor.cachedb.reactor;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.async.CacheAsyncExecutor;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.core.trace.TraceContexts;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

@Timeout(20)
class ReactiveEntityRepositoryTest {
    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test void coldValuesEmptyErrorsBoundedFluxAndRepeatedSubscriptions() throws Exception {
        var calls = new AtomicInteger();
        var delegate = new Rows() {
            @Override public Optional<String> findById(Integer id) {
                calls.incrementAndGet();
                if (id == 0) return Optional.empty();
                if (id < 0) throw new IllegalArgumentException("expected");
                return Optional.of("row");
            }
        };
        try (var executor = new CacheAsyncExecutor(8, TraceContexts.threadLocal())) {
            var repository = new ReactiveEntityRepositoryFactory().from(delegate, executor);
            var cold = repository.findById(1);
            assertEquals(0, calls.get());
            StepVerifier.create(cold).expectNext("row").expectComplete().verify(WAIT);
            StepVerifier.create(cold).expectNext("row").expectComplete().verify(WAIT);
            StepVerifier.create(repository.findById(0)).expectComplete().verify(WAIT);
            StepVerifier.create(repository.findById(-1)).expectError(IllegalArgumentException.class).verify(WAIT);
            StepVerifier.create(repository.findAll(List.of(1, 2))).expectNext("row", "row").expectComplete().verify(WAIT);
            StepVerifier.create(repository.query(QuerySpec.builder().limit(1).build())).expectNext("row").expectComplete().verify(WAIT);
            StepVerifier.create(repository.save("saved")).expectNext("saved").expectComplete().verify(WAIT);
            StepVerifier.create(repository.delete(1)).expectComplete().verify(WAIT);
            StepVerifier.create(repository.aggregate(AggregateSpec.count(null)))
                    .assertNext(value -> assertEquals(1, value.count())).expectComplete().verify(WAIT);
            StepVerifier.create(repository.aggregateBy("field", AggregateSpec.count(null)))
                    .assertNext(value -> assertEquals(1, value.get("group").count())).expectComplete().verify(WAIT);
            assertEquals(4, calls.get());
        }
    }

    @Test void subscriberTenantAndTokenAreIsolatedAndAmbientAuthorityIsRestored() throws Exception {
        var delegate = new Rows() {
            @Override public Optional<String> findById(Integer id) {
                assertFalse(CacheTenantContext.isAdministrative());
                return Optional.of(CacheTenantContext.currentTenant().orElse("none") + ":"
                        + CacheCausalContext.requiredVersion("rows", id));
            }
        };
        String token;
        try (var causal = CacheCausalContext.withMinVersion("rows", 1, 9)) { token = CacheCausalContext.encodeToken(); }
        try (var executor = new CacheAsyncExecutor(8, TraceContexts.threadLocal());
                var ambientTenant = CacheTenantContext.administrative();
                var ambientCausal = CacheCausalContext.withMinVersion("rows", 1, 50)) {
            var repository = new ReactiveEntityRepositoryFactory().from(delegate, executor);
            var cold = repository.findById(1);
            StepVerifier.create(cold.contextWrite(Context.of(CacheReactorContext.TENANT, "a",
                    CacheReactorContext.CAUSAL_TOKEN, token))).expectNext("a:9").expectComplete().verify(WAIT);
            StepVerifier.create(cold.contextWrite(Context.of(CacheReactorContext.TENANT, "b")))
                    .expectNext("b:0").expectComplete().verify(WAIT);
            StepVerifier.create(cold).expectNext("none:0").expectComplete().verify(WAIT);
            StepVerifier.create(cold.contextWrite(Context.of(CacheReactorContext.TENANT, 42)))
                    .expectError(IllegalArgumentException.class).verify(WAIT);
            StepVerifier.create(cold.contextWrite(Context.of(CacheReactorContext.CAUSAL_TOKEN, "malformed")))
                    .expectError(IllegalArgumentException.class).verify(WAIT);
            assertTrue(CacheTenantContext.isAdministrative());
            assertEquals(50, CacheCausalContext.requiredVersion("rows", 1));
        }
    }

    @Test void cancellationAfterStartRetainsPermitUntilIoStopsAndSurfacesSaturation() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var delegate = new Rows() {
            @Override public Optional<String> findById(Integer id) {
                started.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
                return Optional.of("row");
            }
        };
        try (var executor = new CacheAsyncExecutor(1, TraceContexts.threadLocal())) {
            var repository = new ReactiveEntityRepositoryFactory().from(delegate, executor);
            try {
                StepVerifier.create(repository.findById(1)).then(() -> await(started)).thenCancel().verify(WAIT);
                assertEquals(0, executor.snapshot().availablePermits());
                StepVerifier.create(repository.findById(2))
                        .expectError(com.reactor.cachedb.core.async.CacheAsyncRejectedException.class).verify(WAIT);
            } finally { release.countDown(); }
            assertTrue(executor.awaitIdle(WAIT));
            assertEquals(1, executor.snapshot().availablePermits());
        }
    }

    @Test void cancellationBeforeStartReleasesPermitAndQueueStubDrainsWithoutRunningRepository() throws Exception {
        var queue = new ArrayBlockingQueue<Runnable>(1);
        var invoked = new AtomicInteger();
        try (var executor = new CacheAsyncExecutor(1, queue::add, TraceContexts.threadLocal())) {
            var repository = new ReactiveEntityRepositoryFactory().from(new Rows() {
                @Override public Optional<String> findById(Integer id) { invoked.incrementAndGet(); return Optional.of("row"); }
            }, executor);
            StepVerifier.create(repository.findById(1)).thenCancel().verify(WAIT);
            assertEquals(1, executor.snapshot().availablePermits());
            assertEquals(1, executor.snapshot().outstandingDispatches());
            queue.remove().run();
            assertEquals(0, invoked.get());
            assertEquals(0, executor.snapshot().outstandingDispatches());
        }
    }

    @Test void concurrentSubscriptionsDoNotShareTenantOrCausalContext() throws Exception {
        var repositoryDelegate = new Rows() {
            @Override public Optional<String> findById(Integer id) {
                return Optional.of(CacheTenantContext.currentTenant().orElseThrow() + ":"
                        + CacheCausalContext.requiredVersion("rows", id));
            }
        };
        try (var executor = new CacheAsyncExecutor(8, TraceContexts.threadLocal())) {
            var repository = new ReactiveEntityRepositoryFactory().from(repositoryDelegate, executor);
            var values = reactor.core.publisher.Flux.range(0, 128).flatMap(index ->
                    repository.findById(1).map(value -> {
                        assertEquals("tenant-" + index + ":0", value);
                        return value;
                    }).contextWrite(Context.of(CacheReactorContext.TENANT, "tenant-" + index)), 4);
            StepVerifier.create(values).expectNextCount(128).expectComplete().verify(WAIT);
            assertTrue(executor.awaitIdle(WAIT));
            assertEquals(8, executor.snapshot().availablePermits());
            assertTrue(CacheTenantContext.currentTenant().isEmpty());
        }
    }

    @Test void moduleLocalAutoConfigurationCreatesStatelessFactory() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext(
                CacheReactorAutoConfiguration.class)) {
            assertNotNull(context.getBean(ReactiveEntityRepositoryFactory.class));
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    static class Rows implements EntityRepository<String, Integer> {
        public Optional<String> findById(Integer id) { return Optional.of("row"); }
        public List<String> findAll(Collection<Integer> ids) { return ids.stream().map(id -> "row").toList(); }
        public List<String> findPage(PageWindow page) { throw new UnsupportedOperationException(); }
        public QueryExplainPlan explain(QuerySpec spec) { throw new UnsupportedOperationException(); }
        public List<String> query(QuerySpec spec) { return List.of("row"); }
        public String save(String entity) { return entity; }
        public void deleteById(Integer id) {}
        public EntityRepository<String, Integer> withFetchPlan(FetchPlan plan) { return this; }
        public AggregateResult aggregate(AggregateSpec spec) {
            var accumulator = new AggregateResult.Accumulator(); accumulator.add(null); return accumulator.result();
        }
        public Map<String, AggregateResult> aggregateBy(String column, AggregateSpec spec) {
            return Map.of("group", aggregate(spec));
        }
    }
}

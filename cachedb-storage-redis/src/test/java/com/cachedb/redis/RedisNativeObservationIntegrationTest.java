package com.reactor.cachedb.redis;

import static com.reactor.cachedb.redis.RedisCompositeRangeTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.observe.*;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.QueryEvaluator;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.change.ExternalChangeHydrationRepository;
import com.reactor.cachedb.core.async.CacheAsyncExecutor;
import com.reactor.cachedb.core.trace.TraceContexts;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis", matches="true")
class RedisNativeObservationIntegrationTest {
    static final class Recorder implements CacheObservationRecorder {
        final List<Recorded> scopes = new CopyOnWriteArrayList<>();
        public Scope start(CacheOperation operation) { var scope = new Recorded(operation); scopes.add(scope); return scope; }
        Recorded last() { return scopes.getLast(); }
    }
    static final class Recorded implements CacheObservationRecorder.Scope {
        final CacheOperation operation;
        volatile Tier tier; volatile Outcome outcome; volatile Throwable failure; volatile int closes;
        Recorded(CacheOperation operation) { this.operation = operation; }
        public void tier(Tier value) { tier = value; }
        public void outcome(Outcome value) { outcome = value; }
        public void outcomeIfAbsent(Outcome value) { if (outcome == null) outcome = value; }
        public void error(Throwable value) { failure = value; outcome = Outcome.ERROR; }
        public void close() { closes++; }
    }
    static CachePolicy policy() {
        return CachePolicy.builder().hotEntityLimit(CachePolicy.UNBOUNDED).lruEvictionEnabled(false).build();
    }
    static RedisEntityRepository<Row, String> repository(RedisCompositeRangeRedisTest.Fixture f,
            CachePolicy policy, com.reactor.cachedb.core.page.EntityQueryLoader<Row> source, ReadThroughConfig read) {
        return RedisCompositePlannerIntegrationTest.repository(JedisPooledOperations.of(f.redis),
                metadata(false, RedisCompositeRangeRedisTest.SORTS), CODEC, f.keys, f.manager, policy, source, read, (r, c) -> {});
    }

    @Test void rawCapabilitiesViewsRedisAndTombstoneOutcomesArePreserved() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture()) {
            f.put(new Row("1", "a", "OPEN", 1, true));
            var recorder = new Recorder(); var repository = repository(f, policy(), null, ReadThroughConfig.defaults());
            assertSame(repository, repository.withObservationRecorder(recorder));
            assertInstanceOf(ExternalChangeHydrationRepository.class, repository);
            assertInstanceOf(com.reactor.cachedb.core.api.RedisLexicalWindowRepository.class, repository);
            var capabilities = repository.capabilities();
            var view = repository.withFetchPlan(FetchPlan.empty());
            assertInstanceOf(RedisEntityRepository.class, view);
            assertSame(capabilities, view.capabilities());
            assertTrue(view.findById("1").isPresent());
            assertEquals(1, recorder.scopes.size()); assertEquals(Tier.REDIS, recorder.last().tier);
            f.redis.set(f.keys.tombstoneKey("rows", "1"), "2");
            assertTrue(repository.findById("1").isEmpty());
            assertEquals(Outcome.TOMBSTONE, recorder.last().outcome);
            assertEquals(2, recorder.scopes.size());
            assertTrue(recorder.scopes.stream().allMatch(scope -> scope.closes == 1));
            assertSame(NoOpCacheObservationRecorder.SCOPE, CacheObservationContext.current());
        }
    }

    @Test void countAndDefaultQueryOverloadsHaveOneOuterBoundaryAndCausalErrorsCloseIt() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture()) {
            f.put(new Row("1", "a", "OPEN", 1, true));
            var recorder = new Recorder(); var repository = repository(f, policy(), null, ReadThroughConfig.defaults())
                    .withObservationRecorder(recorder);
            assertEquals(1, repository.count(query()));
            assertEquals(1, recorder.scopes.size()); assertEquals(CacheOperation.COUNT, recorder.last().operation);
            assertEquals(1, repository.query(query().rootGroup(), 1).size());
            assertEquals(2, recorder.scopes.size()); assertEquals(CacheOperation.QUERY, recorder.last().operation);
            try (var causal = CacheCausalContext.withMinVersion("rows", "1", 2)) {
                assertThrows(com.reactor.cachedb.core.exception.CacheCausalReadUnsupportedException.class,
                        () -> repository.query(query()));
            }
            assertEquals(3, recorder.scopes.size()); assertEquals(Outcome.ERROR, recorder.last().outcome);
            assertEquals(1, recorder.last().closes);
        }
    }

    @Test void disabledRecorderIsNeverStartedByReadsViewsOrBulkEmptyWrites() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture()) {
            f.put(new Row("1", "a", "OPEN", 1, true));
            var repository = repository(f, policy(), null, ReadThroughConfig.defaults()).withObservationRecorder(
                    new CacheObservationRecorder() {
                        public boolean enabled() { return false; }
                        public Scope start(CacheOperation operation) { throw new AssertionError("Disabled recorder invoked"); }
                    });
            assertTrue(repository.findById("1").isPresent());
            assertEquals(1, repository.withFetchPlan(FetchPlan.empty()).findAll(List.of("1")).size());
            assertEquals(List.of(), repository.saveAll(List.of()));
        }
    }

    @Test void realSourceFallbackChangesTierOnlyWhenTheLoaderRuns() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture()) {
            var recorder = new Recorder();
            var read = ReadThroughConfig.builder().mode(ReadThroughMode.READ_THROUGH_QUERY).hydrateLoadedEntities(false).build();
            var repository = repository(f, policy(), spec -> List.of(new Row("source", "a", "OPEN", 1, true)), read)
                    .withObservationRecorder(recorder);
            assertEquals("source", repository.query(query()).getFirst().id());
            assertEquals(1, recorder.scopes.size()); assertEquals(Tier.SOURCE, recorder.last().tier);
        }
    }

    @Test void asyncWorkerUsesTheOuterScopeInsteadOfCreatingASyncDuplicate() throws Exception {
        try (var f = new RedisCompositeRangeRedisTest.Fixture();
                var executor = new CacheAsyncExecutor(2, TraceContexts.threadLocal())) {
            f.put(new Row("1", "a", "OPEN", 1, true));
            var recorder = new Recorder();
            var repository = repository(f, policy(), null, ReadThroughConfig.defaults()).withObservationRecorder(recorder);
            assertTrue(repository.async(executor).findByIdAsync("1").get(5, TimeUnit.SECONDS).isPresent());
            assertTrue(executor.awaitIdle(Duration.ofSeconds(5)));
            assertEquals(1, recorder.scopes.size());
            assertEquals(Tier.REDIS, recorder.last().tier); assertEquals(1, recorder.last().closes);
            assertSame(NoOpCacheObservationRecorder.SCOPE, CacheObservationContext.current());
            executor.submit(() -> {
                assertSame(NoOpCacheObservationRecorder.SCOPE, CacheObservationContext.current()); return null;
            }).get(5, TimeUnit.SECONDS);
        }
    }

    @Test void bulkFallbackAndAsyncSaveCaptureHeaderBeforeWriteOperationWithOneScopeEach() throws Exception {
        try (var f = new RedisCompositeRangeRedisTest.Fixture();
                var executor = new CacheAsyncExecutor(2, TraceContexts.threadLocal())) {
            var client = JedisPooledOperations.of(f.redis);
            var metadata = metadata(false, RedisCompositeRangeRedisTest.SORTS);
            var shape = ReadShapeGuardrailConfig.defaults();
            var guardConfig = RedisGuardrailConfig.builder().enabled(false).build();
            var guard = new RedisProducerGuard(client, guardConfig, WriteBehindConfig.defaults(), f.keys, "unused", 64);
            var pages = new RedisPageCacheManager<>(client, metadata, CODEC, policy(), f.keys,
                    new RedisHotSetManager(client, f.keys), f.manager, guard, shape, null);
            var queued = new CopyOnWriteArrayList<com.reactor.cachedb.core.model.WriteOperation<?, ?>>();
            var queue = new com.reactor.cachedb.core.queue.WriteBehindQueue() {
                public <T, ID> void enqueue(com.reactor.cachedb.core.model.WriteOperation<T, ID> operation) { queued.add(operation); }
            };
            var recorder = new Recorder();
            String header = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-00";
            var trace = new ObservingTraceContextPropagator(TraceContexts.threadLocal(), () -> header);
            var repository = new RedisEntityRepository<>(client, metadata, CODEC, queue, f.keys, FetchPlan.empty(), policy(),
                    null, guard, guardConfig, "unused", "unused", 1, null, (r, c) -> {},
                    RelationConfig.defaults(), QueryIndexConfig.defaults(), null, null, null, PageCacheConfig.defaults(),
                    ReadThroughConfig.defaults(), shape, pages, new QueryEvaluator(), f.manager, null, client, null, null)
                    .withObservationRecorder(recorder, trace);
            try (var correlation = TraceContexts.withMetadata(Map.of("correlationId", "preserved"))) {
                assertEquals(2, repository.saveAll(List.of(new Row("1", "a", "OPEN", 1, true),
                        new Row("2", "a", "OPEN", 2, true))).size());
                assertEquals(1, recorder.scopes.size()); assertEquals(CacheOperation.SAVE_ALL, recorder.last().operation);
                repository.async(executor).saveAsync(new Row("3", "a", "OPEN", 3, true)).get(5, TimeUnit.SECONDS);
                assertTrue(executor.awaitIdle(Duration.ofSeconds(5)));
                assertEquals(Map.of("correlationId", "preserved"), TraceContexts.current());
            }
            assertEquals(2, recorder.scopes.size()); assertEquals(3, queued.size());
            for (var operation : queued) assertEquals(Map.of("correlationId", "preserved", "traceparent", header),
                    operation.traceMetadata());
            assertTrue(recorder.scopes.stream().allMatch(scope -> scope.closes == 1));
        }
    }

    @Test void actualNearCacheHitReportsL1AndSurvivesFetchPlanView() throws Exception {
        try (var f = new RedisCompositeRangeRedisTest.Fixture();
                var coordinator = new RedisNearCacheCoordinator(JedisPooledOperations.of(f.redis), f.keys)) {
            f.put(new Row("1", "a", "OPEN", 1, true));
            var client = JedisPooledOperations.of(f.redis);
            var metadata = metadata(false, RedisCompositeRangeRedisTest.SORTS);
            var policy = policy().withNearCachePolicy(new NearCachePolicy(32, 10, 65536));
            var shape = ReadShapeGuardrailConfig.defaults();
            var pages = new RedisPageCacheManager<>(client, metadata, CODEC, policy, f.keys,
                    new RedisHotSetManager(client, f.keys), f.manager, null, shape, null);
            var functions = new RedisFunctionExecutor(client, RedisFunctionsConfig.defaults(),
                    RedisGuardrailConfig.defaults(), new RedisFunctionArgsMapper());
            var recorder = new Recorder();
            var repository = new RedisEntityRepository<>(client, metadata, CODEC, null, f.keys, FetchPlan.empty(), policy,
                    functions, null, RedisGuardrailConfig.defaults(), "unused", "unused", 1, null, (r, c) -> {},
                    RelationConfig.defaults(), QueryIndexConfig.defaults(), null, null, null, PageCacheConfig.defaults(),
                    ReadThroughConfig.defaults(), shape, pages, new QueryEvaluator(), f.manager, null, client, null, null)
                    .withNearCache(coordinator).withObservationRecorder(recorder);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!coordinator.connected() && System.nanoTime() < deadline) Thread.sleep(2);
            assertTrue(coordinator.connected());
            assertTrue(repository.findById("1").isPresent());
            assertEquals(Tier.REDIS, recorder.last().tier);
            assertTrue(repository.withFetchPlan(FetchPlan.empty()).findById("1").isPresent());
            assertEquals(Tier.L1, recorder.last().tier);
            assertEquals(2, recorder.scopes.size());
        }
    }
}

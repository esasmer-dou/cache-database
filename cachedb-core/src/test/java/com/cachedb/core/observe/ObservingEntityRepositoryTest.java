package com.reactor.cachedb.core.observe;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.repository.*;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ObservingEntityRepositoryTest {
    @Test void queryCapacityDelegatesExactlyWithoutAnObservationOrIo() {
        for (var capacity : List.of(EntityQueryCapacity.unknown(), EntityQueryCapacity.unrestricted(),
                EntityQueryCapacity.finite(0), EntityQueryCapacity.finite(7))) {
            var calls = new ArrayList<String>();
            EntityRepository<String,String> delegate = proxy(EntityRepository.class, (p, method, args) -> {
                calls.add(method.getName()); assertEquals("entityQueryCapacity", method.getName()); return capacity;
            });
            var recorder = new RecordingCacheObservationRecorder();
            assertSame(capacity, ObservingEntityRepository.wrap(delegate, "Row", recorder).entityQueryCapacity());
            assertEquals(List.of("entityQueryCapacity"), calls);
            assertTrue(recorder.scopes.isEmpty());
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    @Test void everyPublicRepositoryProjectionAndAsyncMethodIsExplicitlyDelegated() throws Exception {
        for (var pair : List.of(Map.entry(EntityRepository.class, ObservingEntityRepository.class),
                Map.entry(ProjectionRepository.class, ObservingProjectionRepository.class),
                Map.entry(AsyncEntityRepository.class, ObservingAsyncEntityRepository.class))) {
            for (Method method : pair.getKey().getMethods()) {
                if (Modifier.isStatic(method.getModifiers())) continue;
                assertEquals(pair.getValue(), pair.getValue().getMethod(method.getName(), method.getParameterTypes()).getDeclaringClass(),
                        method.toGenericString());
            }
        }
    }
    @Test void noopFactoryNeverCreatesAWrapperAndCapabilitiesAreNotReinterpreted() {
        EntityRepository<String, String> delegate = proxy(EntityRepository.class, (p, method, args) -> RepositoryCapabilities.all());
        assertSame(delegate, ObservingEntityRepository.wrap(delegate, "Order", NoOpCacheObservationRecorder.INSTANCE));
        var recorder = new RecordingCacheObservationRecorder();
        var observed = ObservingEntityRepository.wrap(delegate, "Order", recorder);
        assertSame(RepositoryCapabilities.all(), observed.capabilities());
        assertTrue(recorder.scopes.isEmpty());
        assertSame(observed, ObservingEntityRepository.wrap(observed, "Order", recorder));
    }
    @Test void exactQueryOverloadsArgumentsAndReceiptsArePreservedWithOneObservationEach() {
        var called = new ArrayList<Method>();
        var supplied = new ArrayList<Object[]>();
        var receipt = new WriteReceipt<>("row", "id", "orders", OperationType.UPSERT, 7, Instant.EPOCH);
        EntityRepository<String, String> delegate = proxy(EntityRepository.class, (p, method, args) -> {
            called.add(method); supplied.add(args);
            if (method.getName().equals("query")) {
                CacheObservationContext.tier(Tier.REDIS);
                return List.of("row");
            }
            return receipt;
        });
        var recorder = new RecordingCacheObservationRecorder();
        var observed = ObservingEntityRepository.wrap(delegate, "Order", recorder);
        QueryFilter filter = QueryFilter.eq("status", "OPEN");
        QuerySort[] sorts = { QuerySort.desc("created_at") };
        FetchPlan plan = FetchPlan.empty();
        assertEquals(List.of("row"), observed.query((QueryNode) filter, 2, sorts));
        assertArrayEquals(new Class<?>[]{QueryNode.class, int.class, QuerySort[].class}, called.get(0).getParameterTypes());
        assertSame(filter, supplied.get(0)[0]); assertSame(sorts, supplied.get(0)[2]);
        assertEquals(Tier.REDIS, recorder.last().tier);
        observed.query(filter, plan, sorts);
        assertArrayEquals(new Class<?>[]{QueryFilter.class, FetchPlan.class, QuerySort[].class}, called.get(1).getParameterTypes());
        assertSame(receipt, observed.save("row", 6));
        assertSame(receipt, observed.saveWithReceipt("row"));
        assertSame(receipt, observed.patchWithReceipt("id", Map.of("status", "CLOSED")));
        assertSame(receipt, observed.deleteWithReceipt("id"));
        assertEquals(6, recorder.scopes.size());
        assertTrue(recorder.scopes.stream().allMatch(scope -> scope.closes.get() == 1));
    }
    @Test void bulkDeletePreservesConcreteCapabilityArgumentsReceiptsAndTrace() {
        var capabilities = RepositoryCapabilities.of(RepositoryCapability.BULK_DELETE);
        var ids = List.of("first", "second");
        var first = new WriteReceipt<String, String>(null, "first", "orders", OperationType.DELETE, 7, Instant.EPOCH);
        var second = new WriteReceipt<String, String>(null, "second", "orders", OperationType.DELETE, 8, Instant.EPOCH);
        var receipts = List.of(first, second);
        var called = new ArrayList<Method>();
        var recorder = new RecordingCacheObservationRecorder();
        var ambient = CacheObservationContext.current();
        EntityRepository<String, String> delegate = proxy(EntityRepository.class, (p, method, args) -> {
            if (method.getName().equals("capabilities")) return capabilities;
            assertEquals("bulkDelete", method.getName());
            assertArrayEquals(new Class<?>[]{Collection.class}, method.getParameterTypes());
            assertSame(ids, args[0]);
            called.add(method);
            assertSame(recorder.last(), CacheObservationContext.current());
            assertEquals(CacheOperation.DELETE, recorder.last().operation);
            assertEquals("Order", recorder.last().entity);
            assertEquals(0, recorder.last().closes.get());
            CacheObservationContext.tier(Tier.REDIS);
            return receipts;
        });
        var observed = ObservingEntityRepository.wrap(delegate, "Order", recorder);
        assertSame(capabilities, observed.capabilities());
        assertTrue(observed.capabilities().supports(RepositoryCapability.BULK_DELETE));
        assertFalse(observed.capabilities().supports(RepositoryCapability.BULK_WRITE));
        assertTrue(recorder.scopes.isEmpty());
        assertSame(receipts, observed.bulkDelete(ids));
        assertEquals(1, called.size());
        assertEquals(1, recorder.scopes.size());
        assertEquals(Tier.REDIS, recorder.last().tier);
        assertEquals(Outcome.HIT, recorder.last().outcome);
        assertNull(recorder.last().failure);
        assertEquals(1, recorder.last().closes.get());
        assertSame(ambient, CacheObservationContext.current());
    }
    @Test void bulkDeleteObservesOneOperationForTenThousandIdsRatherThanPerId() {
        var ids = new ArrayList<String>(10_000);
        var receipts = new ArrayList<WriteReceipt<String, String>>(10_000);
        for (int i = 0; i < 10_000; i++) {
            String id = Integer.toString(i);
            ids.add(id);
            receipts.add(new WriteReceipt<>(null, id, "orders", OperationType.DELETE, 1, Instant.EPOCH));
        }
        var called = new ArrayList<Method>();
        EntityRepository<String, String> delegate = proxy(EntityRepository.class, (p, method, args) -> {
            assertEquals("bulkDelete", method.getName());
            assertSame(ids, args[0]);
            called.add(method);
            return receipts;
        });
        var recorder = new RecordingCacheObservationRecorder();
        var observed = ObservingEntityRepository.wrap(delegate, "Order", recorder);
        assertSame(receipts, observed.bulkDelete(ids));
        assertEquals(10_000, receipts.size());
        assertEquals(1, called.size());
        assertEquals(1, recorder.scopes.size());
        assertEquals(CacheOperation.DELETE, recorder.last().operation);
        assertEquals(Outcome.HIT, recorder.last().outcome);
        assertEquals(1, recorder.last().closes.get());
    }
    @Test void emptyBulkDeleteStillDelegatesOnceAndRecordsOneMiss() {
        List<String> ids = List.of();
        List<WriteReceipt<String, String>> receipts = List.of();
        var called = new ArrayList<Method>();
        EntityRepository<String, String> delegate = proxy(EntityRepository.class, (p, method, args) -> {
            assertEquals("bulkDelete", method.getName());
            assertSame(ids, args[0]);
            called.add(method);
            return receipts;
        });
        var recorder = new RecordingCacheObservationRecorder();
        var observed = ObservingEntityRepository.wrap(delegate, "Order", recorder);
        assertSame(receipts, observed.bulkDelete(ids));
        assertEquals(1, called.size());
        assertEquals(1, recorder.scopes.size());
        assertEquals(CacheOperation.DELETE, recorder.last().operation);
        assertEquals(Outcome.MISS, recorder.last().outcome);
        assertNull(recorder.last().failure);
        assertEquals(1, recorder.last().closes.get());
    }
    @Test void bulkDeletePartialFailurePreservesExactFailureAndAcceptedReceipts() {
        var ids = List.of("accepted", "rejected", "uncertain", "not-attempted");
        var receipt = new WriteReceipt<String, String>(null, "accepted", "orders", OperationType.DELETE, 7, Instant.EPOCH);
        var failure = new BulkDeleteFailureException(BulkDeleteFailureException.Phase.MUTATION,
                List.of(receipt), List.of("rejected"), List.of("uncertain"), List.of("accepted"), 1);
        var called = new ArrayList<Method>();
        var recorder = new RecordingCacheObservationRecorder();
        var ambient = CacheObservationContext.current();
        EntityRepository<String, String> delegate = proxy(EntityRepository.class, (p, method, args) -> {
            assertEquals("bulkDelete", method.getName());
            assertSame(ids, args[0]);
            called.add(method);
            assertSame(recorder.last(), CacheObservationContext.current());
            CacheObservationContext.tier(Tier.REDIS);
            throw failure;
        });
        var observed = ObservingEntityRepository.wrap(delegate, "Order", recorder);
        assertSame(failure, assertThrows(BulkDeleteFailureException.class, () -> observed.bulkDelete(ids)));
        assertSame(receipt, failure.accepted().get(0));
        assertEquals(BulkDeleteFailureException.Phase.MUTATION, failure.phase());
        assertEquals(List.of("rejected"), failure.rejected());
        assertEquals(List.of("uncertain"), failure.uncertain());
        assertEquals(List.of("accepted"), failure.cleanupPending());
        assertEquals(1, failure.notAttempted());
        assertEquals(1, called.size());
        assertEquals(1, recorder.scopes.size());
        assertEquals(CacheOperation.DELETE, recorder.last().operation);
        assertEquals(Tier.REDIS, recorder.last().tier);
        assertEquals(Outcome.ERROR, recorder.last().outcome);
        assertSame(failure, recorder.last().failure);
        assertEquals(1, recorder.last().closes.get());
        assertSame(ambient, CacheObservationContext.current());
    }
    @Test void bulkDeleteDoesNotPromoteUnsupportedDelegateOrReplaceItsFailure() {
        var capabilities = RepositoryCapabilities.of(RepositoryCapability.DELETE_RECEIPT);
        var failure = new RepositoryCapabilityUnavailableException(RepositoryCapability.BULK_DELETE, EntityRepository.class);
        var ids = List.of("id");
        var called = new ArrayList<Method>();
        EntityRepository<String, String> delegate = proxy(EntityRepository.class, (p, method, args) -> {
            if (method.getName().equals("capabilities")) return capabilities;
            assertEquals("bulkDelete", method.getName());
            assertSame(ids, args[0]);
            called.add(method);
            throw failure;
        });
        var recorder = new RecordingCacheObservationRecorder();
        var observed = ObservingEntityRepository.wrap(delegate, "Order", recorder);
        assertSame(capabilities, observed.capabilities());
        assertFalse(observed.capabilities().supports(RepositoryCapability.BULK_DELETE));
        assertTrue(observed.capabilities().supports(RepositoryCapability.DELETE_RECEIPT));
        assertSame(failure, assertThrows(RepositoryCapabilityUnavailableException.class, () -> observed.bulkDelete(ids)));
        assertEquals(1, called.size());
        assertEquals(1, recorder.scopes.size());
        assertEquals(CacheOperation.DELETE, recorder.last().operation);
        assertEquals(Outcome.ERROR, recorder.last().outcome);
        assertSame(failure, recorder.last().failure);
        assertEquals(1, recorder.last().closes.get());
    }
    @Test void viewsAndProjectionsStayObservedAndPreserveTheirOwnOverloads() {
        var calls = new ArrayList<String>();
        EntityRepository<String, String> child = proxy(EntityRepository.class, (p, method, args) -> {
            calls.add(method.getName()); return Optional.of("child");
        });
        ProjectionRepository<String, String> projection = proxy(ProjectionRepository.class, (p, method, args) -> {
            calls.add(method.toGenericString()); return List.of("projected");
        });
        EntityRepository<String, String> delegate = proxy(EntityRepository.class, (p, method, args) -> {
            calls.add(method.getName()); return method.getName().equals("projected") ? projection : child;
        });
        var recorder = new RecordingCacheObservationRecorder();
        var observed = ObservingEntityRepository.wrap(delegate, "Order", recorder);
        assertEquals(Optional.of("child"), observed.withRelations("items").findById("id"));
        assertEquals("withRelations", calls.get(0));
        assertEquals(1, recorder.scopes.size());
        assertEquals(List.of("projected"), observed.<String>projected(null).query(QueryFilter.eq("x", 1), 3, QuerySort.asc("x")));
        assertEquals(2, recorder.scopes.size());
    }
    @Test void errorsRemainIdenticalAndTombstoneOutcomeSurvivesOptionalMissDerivation() {
        var recorder = new RecordingCacheObservationRecorder();
        var failure = new IllegalArgumentException("business failure");
        EntityRepository<String, String> delegate = proxy(EntityRepository.class, (p, method, args) -> {
            if (args[0].equals("error")) throw failure;
            CacheObservationContext.outcome(Outcome.TOMBSTONE);
            return Optional.empty();
        });
        var observed = ObservingEntityRepository.wrap(delegate, "Order", recorder);
        assertTrue(observed.findById("tomb").isEmpty());
        assertEquals(Outcome.TOMBSTONE, recorder.last().outcome);
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> observed.findById("error")));
        assertSame(failure, recorder.last().failure);
        assertEquals(1, recorder.last().closes.get());
    }
    @Test void asyncViewReturnsOriginalDelegateFutureAndDoesNotReplaceNativeExecution() {
        var original = new CompletableFuture<Optional<String>>();
        AsyncEntityRepository<String, String> async = proxy(AsyncEntityRepository.class, (p, method, args) -> original);
        EntityRepository<String, String> delegate = proxy(EntityRepository.class, (p, method, args) -> async);
        var recorder = new RecordingCacheObservationRecorder();
        var observed = ObservingEntityRepository.wrap(delegate, "Order", recorder);
        var result = observed.async().findByIdAsync("id");
        assertSame(original, result);
        assertEquals(1, recorder.scopes.size());
        original.complete(Optional.of("row"));
        assertEquals(1, recorder.last().closes.get());
    }
}

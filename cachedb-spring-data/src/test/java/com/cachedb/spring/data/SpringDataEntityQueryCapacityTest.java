package com.reactor.cachedb.spring.data;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.spring.data.fixture.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;

class SpringDataEntityQueryCapacityTest {
    private static Customer row(long id) { return new Customer(id, "a", "same", 20, true, null); }
    private static final class Backend implements EntityRepository<Customer,Long> {
        EntityQueryCapacity capacity;
        List<Customer> selected = List.of();
        int reads, counts, bulkCalls, singles;
        QuerySpec last;
        List<Long> deleted = List.of();
        BulkDeleteFailureException failure;
        Backend(EntityQueryCapacity capacity) { this.capacity = capacity; }
        public EntityQueryCapacity entityQueryCapacity() { return capacity; }
        public RepositoryCapabilities capabilities() { return RepositoryCapabilities.of(RepositoryCapability.BULK_DELETE); }
        public Optional<Customer> findById(Long id) { throw new AssertionError("No ownership point reads"); }
        public List<Customer> findAll(Collection<Long> ids) { throw new AssertionError("No ownership prefetch"); }
        public List<Customer> findPage(PageWindow page) { throw new AssertionError(); }
        public QueryExplainPlan explain(QuerySpec query) { throw new AssertionError("No explain I/O"); }
        public List<Customer> query(QuerySpec query) {
            reads++; last = query;
            return selected.subList(Math.min(query.offset(), selected.size()), Math.min(selected.size(), query.offset() + query.limit()));
        }
        public long count(QuerySpec query) { counts++; return selected.size(); }
        public Customer save(Customer row) { throw new AssertionError(); }
        public void deleteById(Long id) { singles++; throw new AssertionError("No serial deletes"); }
        public List<WriteReceipt<Customer,Long>> bulkDelete(Collection<Long> ids) {
            bulkCalls++; deleted = List.copyOf(ids);
            if (failure != null) throw failure;
            return ids.stream().map(id -> new WriteReceipt<Customer,Long>(null, id, "f2-customers", OperationType.DELETE, 1, Instant.EPOCH)).toList();
        }
        public EntityRepository<Customer,Long> withFetchPlan(FetchPlan plan) { return this; }
    }
    @SuppressWarnings("unchecked")
    private Customers repository(Backend backend) {
        CacheSession session = new CacheSession() {
            public <T,ID> EntityRepository<T,ID> repository(EntityMetadata<T,ID> metadata, EntityCodec<T> codec) { return (EntityRepository<T,ID>) backend; }
            public <T,ID> EntityRepository<T,ID> repository(EntityMetadata<T,ID> metadata, EntityCodec<T> codec, CachePolicy policy) { return repository(metadata, codec); }
        };
        return new CustomersCacheDbImplementation(session, true);
    }

    @Test void smallCompleteMatchUsesTheFiniteSentinelThenOneBulkDeleteWithoutCounts() {
        var backend = new Backend(EntityQueryCapacity.finite(3)); backend.selected = List.of(row(1), row(2));
        var repository = repository(backend);
        try (var tenant = CacheTenantContext.withTenant("a")) {
            assertEquals(backend.selected, repository.findByName("same")); assertEquals(3, backend.last.limit());
            assertEquals(2, repository.deleteByName("same")); assertEquals(3, backend.last.limit());
            assertEquals(List.of(1L, 2L), backend.deleted); assertEquals(1, backend.bulkCalls);
            assertEquals(2, backend.reads); assertEquals(0, backend.counts); assertEquals(0, backend.singles);
        }
    }

    @Test void exactFullAndOverfullFiniteWindowsAreAmbiguousAndNeverPartiallyDeleted() {
        for (var selected : List.of(List.of(row(1), row(2), row(3)), List.of(row(1), row(2), row(3), row(4)))) {
            var backend = new Backend(EntityQueryCapacity.finite(3)); backend.selected = selected;
            var repository = repository(backend);
            try (var tenant = CacheTenantContext.withTenant("a")) {
                assertTrue(assertThrows(IllegalStateException.class, () -> repository.findByName("same")).getMessage().contains("Use Pageable or Limit"));
                assertTrue(assertThrows(IllegalStateException.class, () -> repository.deleteByName("same")).getMessage().contains("No deletes were attempted"));
                assertEquals(3, backend.last.limit()); assertEquals(2, backend.reads);
                assertEquals(0, backend.bulkCalls); assertEquals(0, backend.counts); assertEquals(0, backend.singles);
            }
        }
    }

    @Test void unknownAndZeroCompletePathsRejectBeforeAnyIoAndKnownIdsStillUseBulk() {
        for (var capacity : List.of(EntityQueryCapacity.unknown(), EntityQueryCapacity.finite(0))) {
            var backend = new Backend(capacity); var repository = repository(backend);
            try (var tenant = CacheTenantContext.withTenant("a")) {
                var read = assertThrows(IllegalStateException.class, repository::findAll);
                var delete = assertThrows(IllegalStateException.class, () -> repository.deleteByName("same"));
                if (capacity.kind() == EntityQueryCapacity.Kind.UNKNOWN) {
                    assertTrue(read.getMessage().contains("UNKNOWN")); assertTrue(delete.getMessage().contains("UNKNOWN"));
                } else {
                    assertEquals("Derived delete has zero effective entity query capacity; no query or delete was attempted. Use explicit known IDs for deletion or an authorized bounded administrative job", delete.getMessage());
                    assertThrows(IllegalArgumentException.class, () -> repository.findByActive(true, Limit.of(1)));
                }
                assertEquals(0, backend.reads); assertEquals(0, backend.counts); assertEquals(0, backend.bulkCalls);
                repository.deleteAllById(List.of(1L, 2L));
                assertEquals(List.of(1L, 2L), backend.deleted); assertEquals(1, backend.bulkCalls); assertEquals(0, backend.singles);
            }
        }
    }

    @Test void unknownLegacyBudgetPreservesExplicitPageAndLimitValidationInTheBackend() {
        var backend = new Backend(EntityQueryCapacity.unknown()); backend.selected = List.of(row(1), row(2));
        var repository = repository(backend);
        try (var tenant = CacheTenantContext.withTenant("a")) {
            assertEquals(2, repository.findAll(PageRequest.of(0, 2)).getContent().size());
            assertEquals(1, backend.counts); assertEquals(2, backend.last.limit());
            assertEquals(1, repository.findByActive(true, Limit.of(1)).size()); assertEquals(1, backend.last.limit());
            assertEquals(2, backend.reads); assertEquals(1, backend.counts);
        }
    }

    @Test void explicitRequestsAndLookaheadAreCheckedBeforeReadOrCount() {
        var backend = new Backend(EntityQueryCapacity.finite(3)); backend.selected = List.of(row(1), row(2), row(3));
        var repository = repository(backend);
        try (var tenant = CacheTenantContext.withTenant("a")) {
            assertThrows(IllegalArgumentException.class, () -> repository.findAll(PageRequest.of(0, 4)));
            assertThrows(IllegalArgumentException.class, () -> repository.findByActive(true, Limit.of(4)));
            assertThrows(IllegalArgumentException.class, () -> repository.readByActive(true, PageRequest.of(0, 3)));
            assertEquals(0, backend.reads); assertEquals(0, backend.counts);
            assertEquals(3, repository.findAll(PageRequest.of(0, 3)).getNumberOfElements());
            assertEquals(3, repository.findByActive(true, Limit.of(3)).size());
            assertTrue(repository.readByActive(true, PageRequest.of(0, 2)).hasNext());
            assertEquals(3, backend.last.limit()); assertEquals(1, backend.counts);
        }
    }

    @Test void windowIncludesItsSentinelAndSingleNonuniqueReadsRequireTwoRows() {
        var backend = new Backend(EntityQueryCapacity.finite(2)); backend.selected = List.of(row(1), row(2));
        var repository = repository(backend);
        try (var tenant = CacheTenantContext.withTenant("a")) {
            assertThrows(IllegalArgumentException.class, () -> repository.findFirst2ByActiveOrderByAgeAsc(true, ScrollPosition.keyset()));
            assertEquals(0, backend.reads);
            backend.capacity = EntityQueryCapacity.finite(3);
            assertEquals(2, repository.findFirst2ByActiveOrderByAgeAsc(true, ScrollPosition.keyset()).size());
            assertEquals(3, backend.last.limit());
            backend.capacity = EntityQueryCapacity.finite(1); int reads = backend.reads;
            assertThrows(IllegalArgumentException.class, () -> repository.getByName("same")); assertEquals(reads, backend.reads);
            assertEquals(row(1), repository.getFirstByNameOrderByAgeDesc("same"));
            assertTrue(repository.existsByName("same")); assertEquals(1, backend.last.limit());
        }
    }

    @Test void everySelectedTenantIsVerifiedBeforeBulkAndPartialFailureIsNotRetried() {
        var backend = new Backend(EntityQueryCapacity.finite(3));
        backend.selected = List.of(row(1), new Customer(2L, "b", "same", 20, true, null));
        var repository = repository(backend);
        try (var tenant = CacheTenantContext.withTenant("a")) {
            assertThrows(com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException.class, () -> repository.deleteByName("same"));
            assertEquals(0, backend.bulkCalls); assertEquals(0, backend.singles);
            backend.selected = List.of(row(1), row(2));
            var accepted = new WriteReceipt<Customer,Long>(null, 1L, "f2-customers", OperationType.DELETE, 2, Instant.EPOCH);
            backend.failure = new BulkDeleteFailureException(BulkDeleteFailureException.Phase.MUTATION, List.of(accepted), List.of(), List.of(2L), List.of(), 0);
            assertSame(backend.failure, assertThrows(BulkDeleteFailureException.class, () -> repository.deleteByName("same")));
            assertEquals(List.of(accepted), backend.failure.accepted()); assertEquals(1, backend.bulkCalls); assertEquals(0, backend.singles);
        }
    }

    @Test void capacityIsReevaluatedForEachCallerOperationInsteadOfCached() {
        var backend = new Backend(EntityQueryCapacity.finite(3)); backend.selected = List.of(row(1));
        var repository = repository(backend);
        try (var tenant = CacheTenantContext.withTenant("a")) {
            assertEquals(1, repository.findAll().size()); assertEquals(1, backend.reads);
            backend.capacity = EntityQueryCapacity.finite(0);
            assertThrows(IllegalStateException.class, repository::findAll);
            assertThrows(IllegalArgumentException.class, () -> repository.findAll(PageRequest.of(0, 1)));
            assertEquals(1, backend.reads); assertEquals(0, backend.counts); assertEquals(0, backend.bulkCalls);
        }
    }
}

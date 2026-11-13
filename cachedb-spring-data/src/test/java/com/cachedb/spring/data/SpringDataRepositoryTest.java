package com.reactor.cachedb.spring.data;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.api.CacheSession;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.testing.InMemoryCacheDatabase;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.spring.data.fixture.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.*;

class SpringDataRepositoryTest {
    private InMemoryCacheDatabase database() {
        var db = new InMemoryCacheDatabase();
        db.repository(CustomerCacheBinding.METADATA, CustomerCacheBinding.CODEC,
                CachePolicy.builder().hotEntityLimit(20_000).build());
        var repo = new CustomersCacheDbImplementation(db, true);
        try (var scope = CacheTenantContext.withTenant("a")) {
            repo.saveAll(List.of(new Customer(1L, "a", "Ada", 20, true, null),
                    new Customer(2L, "a", "Ada", 30, true, "n"),
                    new Customer(3L, "a", "Bob", 40, false, "b"),
                    new Customer(4L, "a", "Cora", 30, true, "c")));
        }
        try (var scope = CacheTenantContext.withTenant("b")) { repo.save(new Customer(5L, "b", "Ada", 90, true, null)); }
        return db;
    }

    private List<Long> ids(List<Customer> rows) { return rows.stream().map(Customer::id).sorted().toList(); }

    @Test void crudBulkAndCompileGeneratedIdUseTheExistingBackend() {
        var db = new InMemoryCacheDatabase();
        var repo = new CustomersCacheDbImplementation(db, true);
        assertThrows(RuntimeException.class, repo::count);
        try (var scope = CacheTenantContext.withTenant("a")) {
            var saved = repo.save(new Customer(null, "a", "New", 20, true, null));
            assertNotNull(saved.id());
            assertEquals(saved, repo.findById(saved.id()).orElseThrow());
            assertTrue(repo.existsById(saved.id()));
            var bulk = repo.saveAll(List.of(new Customer(100L, "a", "Next", 21, true, "ok")));
            assertEquals(1, bulk.size());
            assertEquals(2, repo.count());
            assertEquals(List.of(saved.id()), ids(repo.findAllById(List.of(saved.id(), 999L))));
            repo.deleteAllById(List.of(100L)); assertFalse(repo.existsById(100L));
            repo.delete(saved); assertEquals(0, repo.count());
            repo.save(saved); repo.deleteAll(List.of(saved)); assertEquals(0, repo.count());
            repo.save(saved); repo.deleteById(saved.id()); assertEquals(0, repo.count());
            repo.save(saved); repo.deleteAll(); assertEquals(0, repo.count());
        }
        assertTrue(CacheTenantContext.currentTenant().isEmpty());
    }

    @Test void everyRequiredOperatorAndItsEqualityAliasesHasRealResults() {
        var repo = new CustomersCacheDbImplementation(database(), true);
        try (var scope = CacheTenantContext.withTenant("a")) {
            assertEquals(List.of(1L, 2L), ids(repo.findByName("Ada")));
            assertEquals(ids(repo.findByName("Ada")), ids(repo.queryByName("Ada")));
            assertEquals(ids(repo.findByName("Ada")), ids(repo.getByNameEquals("Ada")));
            assertEquals(ids(repo.findByName("Ada")), ids(repo.getByNameIs("Ada")));
            assertEquals(ids(repo.findByName("Ada")), ids(repo.getByNameIsEquals("Ada")));
            assertEquals(List.of(3L, 4L), ids(repo.findByNameIsNot("Ada")));
            assertEquals(List.of(1L, 3L), ids(repo.findByAgeNot(30)));
            assertEquals(List.of(1L, 3L), ids(repo.findByAgeIn(List.of(20, 40))));
            assertEquals(List.of(2L, 4L), ids(repo.findByAgeNotIn(List.of(20, 40))));
            assertEquals(List.of(1L, 2L, 3L, 4L), ids(repo.findByAgeNotIn(List.of())));
            assertEquals(List.of(2L, 3L, 4L), ids(repo.findByAgeGreaterThan(20)));
            assertEquals(List.of(2L, 3L, 4L), ids(repo.findByAgeGreaterThanEqual(30)));
            assertEquals(List.of(1L), ids(repo.findByAgeLessThan(30)));
            assertEquals(List.of(1L, 2L, 4L), ids(repo.findByAgeLessThanEqual(30)));
            assertEquals(List.of(1L, 2L, 4L), ids(repo.findByAgeBetween(20, 30)));
            assertEquals(List.of(1L), ids(repo.findByNoteIsNull()));
            assertEquals(List.of(2L, 3L, 4L), ids(repo.findByNoteIsNotNull()));
            assertEquals(List.of(1L, 2L, 4L), ids(repo.findByActiveTrue()));
            assertEquals(List.of(3L), ids(repo.findByActiveFalse()));
            assertEquals(List.of(1L, 2L), ids(repo.findByNameStartingWith("Ad")));
            assertEquals(List.of(1L, 2L, 4L), ids(repo.findByAgeGreaterThanEqualAndAgeLessThanEqualOrderByNameAscAgeDesc(20, 30)));
            assertEquals(3, repo.countByActiveTrue()); assertTrue(repo.existsByName("Ada")); assertFalse(repo.existsByName("missing"));
        }
    }

    @Test void topFirstDynamicSortLimitAndSingleResultContractsAreNotSilentlyIgnored() {
        var repo = new CustomersCacheDbImplementation(database(), true);
        try (var scope = CacheTenantContext.withTenant("a")) {
            assertEquals(List.of(3L, 2L), repo.findAll(Sort.by(Sort.Order.desc("age"))).subList(0, 2).stream().map(Customer::id).toList());
            assertEquals(30, repo.findFirstByActiveOrderByAgeDesc(true).age());
            assertEquals(2, repo.findTop2ByActiveOrderByAgeDesc(true).size());
            assertEquals(2, repo.getFirst2ByActiveOrderByAgeDesc(true).size());
            assertEquals(1, repo.findByActive(true, Limit.of(1)).size());
            assertEquals(3, repo.findByActive(true, Limit.unlimited()).size());
            assertEquals(20, repo.findByActive(true, Sort.by("age")).get(0).age());
            assertEquals(30, repo.getFirstByNameOrderByAgeDesc("Ada").age());
            assertTrue(repo.readById(999L).isEmpty()); assertNull(repo.readByName("missing"));
            assertThrows(IllegalStateException.class, () -> repo.getByName("Ada"));
            assertThrows(IllegalStateException.class, () -> repo.readByName("Ada"));
            assertThrows(IllegalArgumentException.class, () -> repo.getByActiveOrderByAgeAsc(true, Sort.by(Sort.Order.desc("age"))));
            assertThrows(IllegalArgumentException.class, () -> repo.findAll(Sort.by("missing")));
            assertThrows(IllegalArgumentException.class, () -> repo.findAll(Sort.by(Sort.Order.asc("name").ignoreCase())));
            assertThrows(IllegalArgumentException.class, () -> repo.findByActive(true, Limit.of(0)));
            assertThrows(IllegalArgumentException.class, () -> repo.findByActive(true, Limit.of(100_001)));
        }
    }

    @Test void pageAndSliceTotalsLookaheadAndOffsetLimitsAreCorrect() {
        var repo = new CustomersCacheDbImplementation(database(), true);
        try (var scope = CacheTenantContext.withTenant("a")) {
            var first = repo.findByActive(true, PageRequest.of(0, 2, Sort.by("age")));
            assertEquals(3, first.getTotalElements()); assertEquals(2, first.getNumberOfElements()); assertTrue(first.hasNext());
            var last = repo.findByActive(true, PageRequest.of(1, 2, Sort.by("age")));
            assertEquals(3, last.getTotalElements()); assertEquals(1, last.getNumberOfElements()); assertFalse(last.hasNext());
            assertTrue(repo.readByActive(true, PageRequest.of(0, 2)).hasNext());
            assertFalse(repo.readByActive(true, PageRequest.of(1, 2)).hasNext());
            assertEquals(1, repo.findByAge(30, PageRequest.of(0, 1)).size());
            assertEquals(4, repo.findAll(Pageable.unpaged()).getTotalElements());
            assertTrue(repo.findAll(PageRequest.of(10_000, 1)).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> repo.findAll(PageRequest.of(10_001, 1)));
            assertThrows(IllegalArgumentException.class, () -> repo.findAll(PageRequest.of(0, 100_001)));
            assertThrows(IllegalArgumentException.class, () -> repo.readByActive(true, PageRequest.of(0, 100_000)));
            assertFalse(repo.readByActive(true, PageRequest.of(0, 99_999)).hasNext());
        }
    }

    @Test void keysetWindowsKeepTiedIdsStableAcrossInsertsAndRejectCrossTenantPositions() {
        var db = new InMemoryCacheDatabase();
        var repo = new CustomersCacheDbImplementation(db, true);
        KeysetScrollPosition after;
        try (var scope = CacheTenantContext.withTenant("a")) {
            repo.saveAll(List.of(new Customer(1L, "a", "one", 10, true, "n"),
                    new Customer(2L, "a", "two", 10, true, "n"), new Customer(10L, "a", "ten", 10, true, "n")));
            var first = repo.findFirst2ByActiveOrderByAgeAsc(true, ScrollPosition.keyset());
            assertEquals(List.of(1L, 2L), first.getContent().stream().map(Customer::id).toList()); assertTrue(first.hasNext());
            after = (KeysetScrollPosition) first.positionAt(1);
            repo.save(new Customer(3L, "a", "three", 10, true, "n"));
            var next = repo.findFirst2ByActiveOrderByAgeAsc(true, after);
            assertEquals(List.of(3L, 10L), next.getContent().stream().map(Customer::id).toList());
            assertFalse(next.hasNext());
            assertThrows(IllegalArgumentException.class, () -> repo.readByActiveOrderByAgeDesc(true, ScrollPosition.offset(), Limit.of(2)));
            assertThrows(IllegalArgumentException.class, () -> repo.readByActiveOrderByAgeDesc(true, ScrollPosition.keyset(), Limit.of(1001)));
            assertThrows(IllegalArgumentException.class, () -> repo.readByActiveOrderByAgeDesc(true, after, Limit.of(2)));
            assertThrows(IllegalArgumentException.class, () -> repo.findFirst2ByActiveOrderByAgeAsc(true, ScrollPosition.forward(Map.of("id", 2L))));
        }
        try (var scope = CacheTenantContext.withTenant("b")) {
            assertThrows(IllegalArgumentException.class, () -> repo.findFirst2ByActiveOrderByAgeAsc(true, after));
        }
    }

    @Test void positionsRejectChangedBooleanAndIdFiltersOnTheSameDerivedMethod() {
        var repo = new CustomersCacheDbImplementation(database(), true);
        try (var scope = CacheTenantContext.withTenant("a")) {
            var active = repo.findFirst2ByActiveOrderByAgeAsc(true, ScrollPosition.keyset());
            var activePosition = (KeysetScrollPosition) active.positionAt(1);
            assertEquals(1, repo.findFirst2ByActiveOrderByAgeAsc(true, activePosition).size());
            assertThrows(IllegalArgumentException.class,
                    () -> repo.findFirst2ByActiveOrderByAgeAsc(false, activePosition));
            var ids = repo.findFirst2ByIdGreaterThanOrderByAgeAsc(0L, ScrollPosition.keyset());
            var idPosition = (KeysetScrollPosition) ids.positionAt(1);
            assertEquals(2, repo.findFirst2ByIdGreaterThanOrderByAgeAsc(0L, idPosition).size());
            assertThrows(IllegalArgumentException.class,
                    () -> repo.findFirst2ByIdGreaterThanOrderByAgeAsc(1L, idPosition));
        }
    }

    @Test void backwardWindowPositionsAndNullSortValuesHaveDeterministicSemantics() {
        var repo = new CustomersCacheDbImplementation(database(), true);
        try (var scope = CacheTenantContext.withTenant("a")) {
            var first = repo.readByActiveOrderByAgeDesc(true, ScrollPosition.keyset(), Limit.of(2));
            var second = repo.readByActiveOrderByAgeDesc(true, first.positionAt(1), Limit.of(2));
            assertEquals(1, second.size());
            var backward = ((KeysetScrollPosition) second.positionAt(0)).backward();
            var previous = repo.readByActiveOrderByAgeDesc(true, backward, Limit.of(2));
            assertEquals(first.getContent(), previous.getContent());
            repo.save(new Customer(99L, "a", "null-age", null, true, "n"));
            assertThrows(IllegalStateException.class, () -> repo.readByActiveOrderByAgeDesc(true, ScrollPosition.keyset(), Limit.of(10)));
        }
    }

    @Test void bulkAdmissionAndDeleteByCapsFailBeforeMutation() {
        var db = database();
        var repo = new CustomersCacheDbImplementation(db, true);
        try (var scope = CacheTenantContext.withTenant("a")) {
            var oversized = Collections.nCopies(1001, new Customer(99L, "a", "overflow", 1, false, null));
            assertThrows(IllegalArgumentException.class, () -> repo.saveAll(oversized));
            assertEquals(4, repo.count());
            assertThrows(IllegalArgumentException.class, () -> repo.deleteAllById(Collections.nCopies(10_001, 1L)));
            assertEquals(4, repo.count());
            assertThrows(RuntimeException.class, () -> repo.saveAll(List.of(
                    new Customer(90L, "a", "safe", 1, true, null), new Customer(91L, "b", "foreign", 1, true, null))));
            assertFalse(repo.existsById(90L));
            assertEquals(2, repo.deleteByName("Ada"));
            assertEquals(List.of(4L), ids(repo.deleteByAgeLessThan(35)));
            repo.deleteByActiveFalse(); assertEquals(0, repo.count());
        }
    }

    @Test void over10000DerivedDeletesNeverStartDeleting() {
        var db = new InMemoryCacheDatabase();
        db.repository(CustomerCacheBinding.METADATA, CustomerCacheBinding.CODEC, CachePolicy.builder().hotEntityLimit(20_000).build());
        var repo = new CustomersCacheDbImplementation(db, true);
        try (var scope = CacheTenantContext.withTenant("a")) {
            for (int start = 0; start < 10_001; start += 500) {
                var batch = new ArrayList<Customer>();
                for (int i = start; i < Math.min(10_001, start + 500); i++) batch.add(new Customer((long) i, "a", "same", i, false, "n"));
                repo.saveAll(batch);
            }
            assertThrows(IllegalStateException.class, () -> repo.deleteByName("same"));
            assertEquals(10_001, repo.count());
            assertThrows(IllegalStateException.class, repo::deleteAll);
            assertEquals(10_001, repo.count());
        }
    }

    @Test void boundedOperandsAndPrefixContractRejectInvalidCalls() {
        var db = database();
        assertThrows(IllegalArgumentException.class, () -> new CustomersCacheDbImplementation(db));
        var repo = new CustomersCacheDbImplementation(db, true);
        try (var scope = CacheTenantContext.withTenant("a")) {
            assertThrows(IllegalArgumentException.class, () -> repo.findByAgeIn(Collections.nCopies(513, 1)));
            assertThrows(NullPointerException.class, () -> repo.findByAgeIn(Arrays.asList(1, null)));
            assertThrows(IllegalArgumentException.class, () -> repo.findByName("x".repeat(4097)));
            assertEquals(4, repo.count());
        }
    }

    @Test void generatedSpringConfigurationRegistersAConcreteRepositoryWithoutAQueryProxy() {
        new ApplicationContextRunner().withBean(CacheSession.class, InMemoryCacheDatabase::new)
                .withUserConfiguration(ManagedCustomersCacheDbConfiguration.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var repository = context.getBean("managedCustomers", ManagedCustomers.class);
                    assertEquals(ManagedCustomersCacheDbImplementation.class, repository.getClass());
                    try (var scope = CacheTenantContext.withTenant("a")) {
                        repository.save(new Customer(1L, "a", "Spring", 20, true, null));
                        assertEquals(1, repository.countByActiveTrue());
                        assertEquals(1, repository.findByActive(true, PageRequest.of(0, 1)).getTotalElements());
                    }
                });
    }
}

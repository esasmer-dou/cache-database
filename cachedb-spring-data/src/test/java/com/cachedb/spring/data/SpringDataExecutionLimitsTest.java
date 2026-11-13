package com.reactor.cachedb.spring.data;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.spring.data.fixture.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;

class SpringDataExecutionLimitsTest {
    private static final Customer ROW = new Customer(1L, "a", "bounded", 20, true, "n");
    private static final class Backend implements EntityRepository<Customer, Long> {
        int available, reads, counts, deletes; QuerySpec last;
        public EntityQueryCapacity entityQueryCapacity() { return EntityQueryCapacity.unrestricted(); }
        public Optional<Customer> findById(Long id) { return Optional.of(ROW); }
        public List<Customer> findAll(Collection<Long> ids) { return List.of(); }
        public List<Customer> findPage(PageWindow page) { return List.of(); }
        public QueryExplainPlan explain(QuerySpec query) { throw new AssertionError("No extra explain round trip"); }
        public List<Customer> query(QuerySpec query) {
            last = query; reads++;
            return Collections.nCopies(Math.min(available, query.limit()), ROW);
        }
        public long count(QuerySpec query) { counts++; return available; }
        public Customer save(Customer row) { return row; }
        public void deleteById(Long id) { deletes++; }
        public EntityRepository<Customer, Long> withFetchPlan(FetchPlan plan) { return this; }
    }

    @SuppressWarnings("unchecked")
    private CacheSession session(Backend backend) {
        return new CacheSession() {
            public <T, ID> EntityRepository<T, ID> repository(EntityMetadata<T, ID> metadata, EntityCodec<T> codec) {
                return (EntityRepository<T, ID>) backend;
            }
            public <T, ID> EntityRepository<T, ID> repository(EntityMetadata<T, ID> metadata, EntityCodec<T> codec, CachePolicy policy) {
                return repository(metadata, codec);
            }
        };
    }

    @Test void unboundedListRefusesAnExactlyFullSafetyWindowButExplicitLimitIsHonored() {
        var backend = new Backend(); backend.available = QuerySpec.MAX_LIMIT;
        var repo = new CustomersCacheDbImplementation(session(backend), true);
        try (var tenant = CacheTenantContext.withTenant("a")) {
            assertThrows(IllegalStateException.class, repo::findAll);
            assertEquals(QuerySpec.MAX_LIMIT, backend.last.limit());
            assertEquals(QuerySpec.MAX_LIMIT, repo.findByActive(true, Limit.of(QuerySpec.MAX_LIMIT)).size());
            assertEquals(0, backend.counts, "A bounded List must not add a count round trip");
        }
    }

    @Test void deepOffsetIsRejectedBeforeIoAndMaximumPageSizeStillUsesCount() {
        var backend = new Backend(); backend.available = 2;
        var repo = new CustomersCacheDbImplementation(session(backend), true);
        try (var tenant = CacheTenantContext.withTenant("a")) {
            assertThrows(IllegalArgumentException.class, () -> repo.findAll(PageRequest.of(10_001, 1)));
            assertEquals(0, backend.reads); assertEquals(0, backend.counts);
            var page = repo.findAll(PageRequest.of(0, QuerySpec.MAX_LIMIT));
            assertEquals(2, page.getTotalElements()); assertEquals(1, backend.counts);
            assertEquals(QuerySpec.MAX_LIMIT, backend.last.limit());
            assertEquals(30, backend.last.queryTimeoutSeconds());
        }
    }

    @Test void deletionSpillIsDetectedBeforeTheFirstDeleteAndSlicesAvoidCounts() {
        var backend = new Backend(); backend.available = 10_001;
        var repo = new CustomersCacheDbImplementation(session(backend), true);
        try (var tenant = CacheTenantContext.withTenant("a")) {
            assertThrows(IllegalStateException.class, () -> repo.deleteByName("bounded"));
            assertEquals(10_001, backend.last.limit()); assertEquals(0, backend.deletes);
            assertTrue(repo.readByActive(true, PageRequest.of(0, 2)).hasNext());
            assertEquals(3, backend.last.limit()); assertEquals(0, backend.counts);
        }
    }
}

package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySortDirection;
import com.reactor.cachedb.core.query.QuerySpec;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

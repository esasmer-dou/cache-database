package com.reactor.cachedb.core.api;

import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.QueryExplainPlan;
import com.reactor.cachedb.core.query.QuerySpec;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryCapabilitiesTest {

    @Test
    void exposesOptionalCapabilitiesWithoutAllocatingSetsOnChecks() {
        RepositoryCapabilities capabilities = RepositoryCapabilities.of(
                RepositoryCapability.VERSIONED_READ,
                RepositoryCapability.BULK_WRITE
        );

        assertTrue(capabilities.supports(RepositoryCapability.VERSIONED_READ));
        assertTrue(capabilities.supports(RepositoryCapability.BULK_WRITE));
        assertFalse(capabilities.supports(RepositoryCapability.PROJECTION));
        assertEquals(List.of(RepositoryCapability.VERSIONED_READ, RepositoryCapability.BULK_WRITE), capabilities.asList());
    }

    @Test
    void unsupportedOperationsExposeTheMissingCapability() {
        MinimalRepository repository = new MinimalRepository();

        RepositoryCapabilityUnavailableException failure = assertThrows(
                RepositoryCapabilityUnavailableException.class,
                () -> repository.saveAll(List.of("one"))
        );

        assertEquals(RepositoryCapability.BULK_WRITE, failure.capability());
        assertTrue(failure.implementationType().endsWith("MinimalRepository"));
    }

    private static final class MinimalRepository implements EntityRepository<String, Long> {
        @Override public Optional<String> findById(Long id) { return Optional.empty(); }
        @Override public List<String> findAll(Collection<Long> ids) { return List.of(); }
        @Override public List<String> findPage(PageWindow pageWindow) { return List.of(); }
        @Override public QueryExplainPlan explain(QuerySpec querySpec) { throw new UnsupportedOperationException(); }
        @Override public List<String> query(QuerySpec querySpec) { return List.of(); }
        @Override public String save(String entity) { return entity; }
        @Override public void deleteById(Long id) { }
        @Override public EntityRepository<String, Long> withFetchPlan(FetchPlan fetchPlan) { return this; }
    }
}

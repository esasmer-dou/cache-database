package com.reactor.cachedb.core.api;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.config.ReadShapeGuardrailConfig;
import com.reactor.cachedb.core.guardrail.ReadShapeGuardrails;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class EntityQueryCapacityTest {
    @Test void unknownUnrestrictedAndFiniteZeroAreDistinctAndInvalidStatesAreRejected() {
        var states = Set.of(EntityQueryCapacity.unknown(), EntityQueryCapacity.unrestricted(),
                EntityQueryCapacity.finite(0), EntityQueryCapacity.finite(1));
        assertEquals(4, states.size());
        assertSame(EntityQueryCapacity.finite(0), EntityQueryCapacity.finite(0));
        assertThrows(NullPointerException.class, () -> new EntityQueryCapacity(null, 0));
        assertThrows(IllegalArgumentException.class, () -> EntityQueryCapacity.finite(-1));
        assertThrows(IllegalArgumentException.class, () -> new EntityQueryCapacity(EntityQueryCapacity.Kind.UNKNOWN, 1));
        assertThrows(IllegalArgumentException.class, () -> new EntityQueryCapacity(EntityQueryCapacity.Kind.UNRESTRICTED, 1));
    }

    @Test void legacyDefaultDoesNotClaimAnyKnownCapacityOrPerformIo() {
        EntityRepository<String,String> legacy = new EntityRepository<>() {
            public Optional<String> findById(String id) { throw new AssertionError(); }
            public List<String> findAll(Collection<String> ids) { throw new AssertionError(); }
            public List<String> findPage(PageWindow page) { throw new AssertionError(); }
            public QueryExplainPlan explain(QuerySpec query) { throw new AssertionError(); }
            public List<String> query(QuerySpec query) { throw new AssertionError(); }
            public String save(String row) { throw new AssertionError(); }
            public void deleteById(String id) { throw new AssertionError(); }
            public EntityRepository<String,String> withFetchPlan(FetchPlan plan) { return this; }
        };
        assertSame(EntityQueryCapacity.unknown(), legacy.entityQueryCapacity());
    }

    @Test void finiteBudgetUsesTheSameLimitsAndZeroRejectionAsNativeValidation() {
        var policy = CachePolicy.builder().hotEntityLimit(8).pageSize(8).build();
        for (int headroom : List.of(0, 1, 8, 10)) {
            var config = ReadShapeGuardrailConfig.builder().hotSetHeadroom(headroom).maxEntityQueryLimit(6).build();
            int expected = Math.min(6, Math.max(0, 8 - headroom));
            assertEquals(EntityQueryCapacity.finite(expected), ReadShapeGuardrails.entityQueryCapacity(policy, config));
            for (int requested = 1; requested <= 9; requested++) {
                var query = QuerySpec.allOf().limitTo(requested);
                if (requested <= expected)
                    assertDoesNotThrow(() -> ReadShapeGuardrails.validateEntityQuery("Row", query, policy, config));
                else assertThrows(IllegalArgumentException.class,
                        () -> ReadShapeGuardrails.validateEntityQuery("Row", query, policy, config));
            }
        }
    }

    @Test void unrestrictedMeansNoActiveRowRestrictionNotAnEncodedZero() {
        var policy = CachePolicy.builder().hotEntityLimit(CachePolicy.UNBOUNDED).pageSize(0).build();
        assertEquals(EntityQueryCapacity.unrestricted(), ReadShapeGuardrails.entityQueryCapacity(policy, ReadShapeGuardrailConfig.defaults()));
        assertEquals(EntityQueryCapacity.finite(4), ReadShapeGuardrails.entityQueryCapacity(policy,
                ReadShapeGuardrailConfig.builder().maxEntityQueryLimit(4).build()));
        assertEquals(EntityQueryCapacity.unrestricted(), ReadShapeGuardrails.entityQueryCapacity(policy, null));
        assertEquals(EntityQueryCapacity.unrestricted(), ReadShapeGuardrails.entityQueryCapacity(CachePolicy.defaults(),
                ReadShapeGuardrailConfig.builder().enabled(false).build()));
        assertEquals(EntityQueryCapacity.unrestricted(), ReadShapeGuardrails.entityQueryCapacity(CachePolicy.defaults(),
                ReadShapeGuardrailConfig.builder().rejectEntityQueryOverLimit(false).build()));
    }
}

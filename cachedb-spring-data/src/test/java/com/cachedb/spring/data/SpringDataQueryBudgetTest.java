package com.reactor.cachedb.spring.data;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.query.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SpringDataQueryBudgetTest {
    private enum State { OPEN; @Override public String toString() { return "not-the-stored-name"; } }
    @Test void inNotInAndBetweenLowerToBoundedCoreNodes() {
        assertEquals(List.of("OPEN"), SpringDataQueries.enumValues(List.of(State.OPEN)));
        assertEquals(512, SpringDataQueries.values(Collections.nCopies(512, 1)).size());
        assertThrows(IllegalArgumentException.class, () -> SpringDataQueries.values(Collections.nCopies(513, 1)));
        assertThrows(IllegalArgumentException.class, () -> SpringDataQueries.notIn("age", Collections.nCopies(33, 1)));
        assertThrows(NullPointerException.class, () -> SpringDataQueries.values(Arrays.asList(1, null)));
        var evaluator = new QueryEvaluator();
        var excluded = SpringDataQueries.notIn("age", List.of(1, 2));
        assertFalse(evaluator.matches(Map.of("age", 1), excluded));
        assertTrue(evaluator.matches(Map.of("age", 3), excluded));
        var between = SpringDataQueries.between("age", 1, 3);
        assertTrue(evaluator.matches(Map.of("age", 1), between));
        assertTrue(evaluator.matches(Map.of("age", 3), between));
        assertFalse(evaluator.matches(Map.of("age", 4), between));
    }

    @Test void predicateNodeStringAndTotalByteBudgetsAreEnforcedBeforeDelegation() {
        assertThrows(IllegalArgumentException.class, () -> SpringDataQueries.bounded(QuerySpec.where(QueryFilter.eq("name", "x".repeat(4097)))));
        assertThrows(IllegalArgumentException.class, () -> SpringDataQueries.bounded(QuerySpec.where(QueryFilter.eq("age", java.math.BigInteger.ONE.shiftLeft(4000)))));
        assertThrows(IllegalArgumentException.class, () -> SpringDataQueries.bounded(QuerySpec.where(QueryFilter.eq("amount", new java.math.BigDecimal("1").setScale(2000)))));
        assertThrows(IllegalArgumentException.class, () -> SpringDataQueries.bounded(QuerySpec.where(QueryFilter.eq("amount", Double.NaN))));
        var tiny = Collections.nCopies(4096, (QueryNode) QueryFilter.eq("age", 1));
        assertThrows(IllegalArgumentException.class, () -> SpringDataQueries.bounded(QuerySpec.allOf(tiny.toArray(QueryNode[]::new))));
        var expensive = Collections.nCopies(2000, (QueryNode) QueryFilter.eq("name", "\u4e00".repeat(4096)));
        assertThrows(IllegalArgumentException.class, () -> SpringDataQueries.bounded(QuerySpec.allOf(expensive.toArray(QueryNode[]::new))));
        assertDoesNotThrow(() -> SpringDataQueries.bounded(QuerySpec.where(SpringDataQueries.notIn("age", Collections.nCopies(32, 1)))));
    }
}

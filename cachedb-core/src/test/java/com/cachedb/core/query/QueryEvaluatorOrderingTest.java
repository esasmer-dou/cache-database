package com.reactor.cachedb.core.query;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class QueryEvaluatorOrderingTest {
    @Test
    void preservesNullCollectionAndDescendingComparisons() {
        var evaluator = new QueryEvaluator();
        var absent = Map.<String, Object>of();
        var small = Map.<String, Object>of("v", List.of(1));
        var large = Map.<String, Object>of("v", List.of(1, 2));
        var ascending = evaluator.comparator(QuerySort.asc("v"));
        var descending = evaluator.comparator(QuerySort.desc("v"));
        assertTrue(ascending.compare(small, large) < 0);
        assertTrue(ascending.compare(absent, small) > 0);
        assertTrue(descending.compare(absent, small) < 0);
        assertEquals(0, ascending.compare(absent, absent));
        for (var left : List.of(absent, small, large))
            for (var right : List.of(absent, small, large))
                assertEquals(ascending.compare(right, left), descending.compare(left, right));
        assertTrue(evaluator.matches(absent, QueryGroup.and()));
        assertFalse(evaluator.matches(absent, QueryGroup.or()));
        assertThrows(
                NullPointerException.class,
                () -> evaluator.matches(absent, new QueryGroup(null, List.of())));
        assertThrows(NullPointerException.class, () -> evaluator.comparator(null));
    }
}

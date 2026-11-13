package com.reactor.cachedb.spring.data;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.query.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpringDataQueryFingerprintTest {
    private String hash(Object value) {
        return SpringDataQueryFingerprint.of(QuerySpec.where(QueryFilter.eq("value", value)));
    }

    @Test void sameTypedFilterHasTheSameFixedLengthFingerprint() {
        assertEquals(hash("ğışöüİ"), hash("ğışöüİ"));
        assertEquals(43, hash("ğışöüİ").length());
        assertEquals(hash(new BigDecimal("1.00")), hash(new BigDecimal("1.00")));
        assertEquals(hash("x".repeat(4096)), hash("x".repeat(4096)));
    }

    @Test void typesNullsOperandOrderAndTreeShapeCannotAlias() {
        assertNotEquals(hash(1), hash(1L));
        assertNotEquals(hash(1L), hash("1"));
        assertNotEquals(hash(null), hash("null"));
        assertNotEquals(hash(true), hash("true"));
        assertNotEquals(hash(String.valueOf((char) 0xd800)), hash("?"));
        assertNotEquals(hash(new BigDecimal("1.0")), hash(new BigDecimal("1.00")));
        var ordered = QuerySpec.where(QueryFilter.in("id", List.of(1L, 2L)));
        var reversed = QuerySpec.where(QueryFilter.in("id", List.of(2L, 1L)));
        assertNotEquals(SpringDataQueryFingerprint.of(ordered), SpringDataQueryFingerprint.of(reversed));
        var and = QuerySpec.where(QueryGroup.and(QueryFilter.eq("a", "b;c"), QueryFilter.eq("d", "e")));
        var or = QuerySpec.where(QueryGroup.or(QueryFilter.eq("a", "b;c"), QueryFilter.eq("d", "e")));
        assertNotEquals(SpringDataQueryFingerprint.of(and), SpringDataQueryFingerprint.of(or));
    }

    @Test void unsupportedValuesNeverInvokeTheirToString() {
        Object hostile = new Object() {
            @Override public String toString() { throw new AssertionError("Must not stringify an arbitrary object"); }
        };
        assertThrows(IllegalArgumentException.class, () -> hash(hostile));
        assertThrows(IllegalArgumentException.class, () -> SpringDataQueries.bounded(
                QuerySpec.where(QueryFilter.eq("value", hostile))));
    }

    @Test void operandNumericAndStructuralBoundsApplyBeforeHashing() {
        assertThrows(IllegalArgumentException.class, () -> hash("x".repeat(4097)));
        assertThrows(IllegalArgumentException.class, () -> hash(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> hash(new BigDecimal("1E-1000000000")));
        QueryNode[] nodes = new QueryNode[4097];
        java.util.Arrays.fill(nodes, QueryFilter.eq("id", 1L));
        QuerySpec wide = QuerySpec.where(QueryGroup.and(nodes));
        assertThrows(IllegalArgumentException.class, () -> SpringDataQueryFingerprint.of(wide));
    }
}

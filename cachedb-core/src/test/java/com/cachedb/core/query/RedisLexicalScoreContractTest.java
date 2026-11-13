package com.reactor.cachedb.core.query;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedisLexicalScoreContractTest {
    @Test void long53IsConditionalNotATypeWideOrFloatingPointPromise() {
        long maximum = RedisLexicalScoreContract.MAX_LONG53;
        assertTrue(RedisLexicalScoreContract.attestableType("long"));
        assertTrue(RedisLexicalScoreContract.attestableType("java.lang.Long"));
        assertFalse(RedisLexicalScoreContract.losslessType("long"));
        assertFalse(RedisLexicalScoreContract.losslessType("java.lang.Long"));
        for (long value : new long[]{-maximum, -1, 0, 1, maximum})
            assertTrue(RedisLexicalScoreContract.long53Value(value));
        for (long value : new long[]{Long.MIN_VALUE, -maximum - 1, maximum + 1, Long.MAX_VALUE})
            assertFalse(RedisLexicalScoreContract.long53Value(value));
        assertFalse(RedisLexicalScoreContract.long53Value(1));
        assertFalse(RedisLexicalScoreContract.long53Value(1d));
        assertFalse(RedisLexicalScoreContract.long53Value(null));
        assertFalse(RedisLexicalScoreContract.attestableType("java.time.Instant"));
        assertFalse(RedisLexicalScoreContract.attestableType("java.math.BigDecimal"));
    }

    @Test void onlyKnownInjectiveScoreDomainsAreEligible() {
        for (String type : List.of("byte", "java.lang.Byte", "short", "java.lang.Short",
                "int", "java.lang.Integer", "java.time.LocalDate"))
            assertTrue(RedisLexicalScoreContract.losslessType(type), type);
        for (String type : List.of("long", "java.lang.Long", "float", "java.lang.Float", "double",
                "java.lang.Double", "java.math.BigDecimal", "java.time.Instant", "java.time.LocalDateTime",
                "java.time.OffsetDateTime", "java.lang.String", "unknown"))
            assertFalse(RedisLexicalScoreContract.losslessType(type), type);
        assertFalse(RedisLexicalScoreContract.losslessType(null));
    }

    @Test void runtimeTypeMustMatchDeclaredDomainExactly() {
        assertTrue(RedisLexicalScoreContract.matchesRuntimeType("int", Integer.MAX_VALUE));
        assertFalse(RedisLexicalScoreContract.matchesRuntimeType("int", 1L));
        assertFalse(RedisLexicalScoreContract.matchesRuntimeType("java.lang.Integer", null));
        assertTrue(RedisLexicalScoreContract.matchesRuntimeType("java.time.LocalDate", LocalDate.MAX));
        assertTrue(RedisLexicalScoreContract.matchesRuntimeType("java.time.LocalDate", LocalDate.MIN));
        assertFalse(RedisLexicalScoreContract.matchesRuntimeType("java.time.LocalDate", Instant.EPOCH));
    }

    @Test void rejectedTypesReallyHaveLogicalValuesThatCollapseToTheSameRedisScore() {
        long lower = 9_007_199_254_740_992L, upper = lower + 1;
        assertTrue(Long.compare(lower, upper) < 0);
        assertEquals((double) lower, (double) upper);
        BigDecimal a = new BigDecimal("1.0000000000000000001"), b = new BigDecimal("1.0000000000000000002");
        assertTrue(a.compareTo(b) < 0);
        assertEquals(a.doubleValue(), b.doubleValue());
        Instant t = Instant.parse("2026-01-01T00:00:00.000000001Z"), u = t.plusNanos(1);
        assertTrue(t.compareTo(u) < 0);
        assertEquals(t.toEpochMilli(), u.toEpochMilli());
        assertTrue(Double.compare(-0d, 0d) < 0);
        assertTrue(OffsetDateTime.parse("2026-01-01T00:00:00Z").compareTo(
                OffsetDateTime.parse("2026-01-01T01:00:00+01:00")) < 0);
    }
}

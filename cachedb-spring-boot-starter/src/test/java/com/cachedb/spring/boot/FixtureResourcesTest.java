package com.reactor.cachedb.spring.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FixtureResourcesTest {
    @Test
    void closesEveryResourceInExplicitOwnershipOrder() throws Exception {
        var order = new ArrayList<String>();
        FixtureResources.close(resource(order, "wire", null), resource(order, "redis-keys", null),
                resource(order, "redis", null), resource(order, "sql", null));
        assertEquals(List.of("wire", "redis-keys", "redis", "sql"), order);
    }

    @Test
    void firstFailureRemainsPrimaryAndAllRemainingResourcesClose() {
        var order = new ArrayList<String>();
        var first = new IOException("wire");
        var second = new IllegalStateException("redis");
        var third = new AssertionError("sql");
        assertSame(first, assertThrows(IOException.class, () -> FixtureResources.close(
                resource(order, "wire", first), resource(order, "redis-keys", null),
                resource(order, "redis", second), resource(order, "sql", third))));
        assertArrayEquals(new Throwable[]{second, third}, first.getSuppressed());
        assertEquals(List.of("wire", "redis-keys", "redis", "sql"), order);
    }

    @Test
    void fatalFailureCannotSkipTheRemainingCleanup() {
        var order = new ArrayList<String>();
        var fatal = new AssertionError("wire");
        assertSame(fatal, assertThrows(AssertionError.class, () -> FixtureResources.close(
                resource(order, "wire", fatal), resource(order, "sql", null))));
        assertEquals(List.of("wire", "sql"), order);
    }

    @Test
    void partialConstructionAllowsAbsentResources() throws Exception {
        var order = new ArrayList<String>();
        FixtureResources.close(null, null, resource(order, "sql", null));
        assertEquals(List.of("sql"), order);
    }

    @Test
    void cleanupCannotReplaceTheConstructorFailure() {
        var order = new ArrayList<String>();
        var original = new IllegalStateException("construction");
        var wire = new IOException("wire");
        var sql = new IOException("sql");
        FixtureResources.closeOnFailure(original, resource(order, "wire", wire),
                null, resource(order, "sql", sql));
        assertArrayEquals(new Throwable[]{wire}, original.getSuppressed());
        assertArrayEquals(new Throwable[]{sql}, wire.getSuppressed());
        assertEquals(List.of("wire", "sql"), order);
    }

    @Test
    void repeatedFailureIdentityDoesNotInterruptCleanup() {
        var order = new ArrayList<String>();
        var same = new IOException("same failure");
        FixtureResources.closeOnFailure(same, resource(order, "wire", same),
                resource(order, "redis", same), resource(order, "sql", null));
        assertEquals(0, same.getSuppressed().length);
        assertEquals(List.of("wire", "redis", "sql"), order);
    }

    @Test
    void successfulCleanupAddsNoSuppressedFailure() {
        var order = new ArrayList<String>();
        var original = new IllegalStateException("construction");
        FixtureResources.closeOnFailure(original, null, resource(order, "sql", null));
        assertEquals(0, original.getSuppressed().length);
        assertEquals(List.of("sql"), order);
    }

    private static AutoCloseable resource(List<String> order, String name, Throwable failure) {
        return () -> {
            order.add(name);
            if (failure instanceof Error fatal) throw fatal;
            if (failure instanceof Exception checked) throw checked;
        };
    }
}

package com.reactor.cachedb.spring.boot.snapshot;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.repository.WindowRequest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class InMemorySnapshotRepositoryTest {
    @Test
    void generationsAreUniqueAcrossRepositoriesAndExpireCycles() {
        var first = new InMemorySnapshotRepository();
        var second = new InMemorySnapshotRepository();
        for (var repository : List.of(first, second)) {
            repository.putSnapshot("a", "[]", "p", 1);
            repository.putSnapshot("b", "[]", "p", 2);
        }
        String generation = first.lookupRaw("a").generation();
        assertNotEquals(generation, second.lookupRaw("a").generation());
        var cursor = first.findPage("p", WindowRequest.first(1)).nextRequest(1).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> second.findPage("p", cursor));
        first.expire();
        first.putSnapshot("a", "[]", "p", 1);
        first.putSnapshot("b", "[]", "p", 2);
        assertNotEquals(generation, first.lookupRaw("a").generation());
        assertThrows(IllegalArgumentException.class, () -> first.findPage("p", cursor));
    }

    @Test
    void refreshKeepsOtherPartitionCursorsAndInvalidatesChangedPartition() {
        var repository = new InMemorySnapshotRepository();
        repository.putSnapshot("a", "[]", "A", 1);
        repository.putSnapshot("b", "[]", "B", 1);
        repository.putSnapshot("c", "[]", "B", 2);
        var cursor = repository.findPage("B", WindowRequest.first(1)).nextRequest(1).orElseThrow();
        repository.onRefresh(ids -> Map.of("a", "[1]"));
        repository.refreshRoots(List.of("a"));
        assertEquals("c", repository.findPage("B", cursor).items().get(0).id());
        repository.onRefresh(ids -> Map.of());
        repository.refreshRoots(List.of("b"));
        assertThrows(IllegalArgumentException.class, () -> repository.findPage("B", cursor));
    }
}

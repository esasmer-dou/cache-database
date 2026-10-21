package com.reactor.cachedb.core.testing;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class InMemoryReadContractTest {
    @Test
    void versionedAndBulkReadsKeepAbsenceInputOrderAndDuplicates() {
        var repository =
                new InMemoryCacheDatabase()
                        .repository(
                                InMemoryCacheRepositoryTest.metadata(),
                                InMemoryCacheRepositoryTest.codec());
        assertTrue(repository.findVersionedById(1L).isEmpty());
        repository.save(new InMemoryCacheRepositoryTest.Ticket(1L, "OPEN"), 0);
        repository.save(new InMemoryCacheRepositoryTest.Ticket(2L, "CLOSED"), 0);
        var first = repository.findVersionedById(1L).orElseThrow();
        assertEquals(1, first.version());
        assertEquals("OPEN", first.entity().status());
        repository.save(new InMemoryCacheRepositoryTest.Ticket(1L, "PENDING"), 1);
        var second = repository.findVersionedById(1L).orElseThrow();
        assertEquals(2, second.version());
        assertEquals("PENDING", second.entity().status());
        var values = repository.findAll(List.of(2L, 100L, 1L, 2L));
        assertEquals(
                List.of(
                        new InMemoryCacheRepositoryTest.Ticket(2L, "CLOSED"),
                        new InMemoryCacheRepositoryTest.Ticket(1L, "PENDING"),
                        new InMemoryCacheRepositoryTest.Ticket(2L, "CLOSED")),
                values);
        assertThrows(UnsupportedOperationException.class, values::clear);
        assertTrue(repository.findAll(List.of()).isEmpty());
        repository.deleteById(1L);
        assertTrue(repository.findVersionedById(1L).isEmpty());
        assertEquals(1, first.version());
        assertEquals("OPEN", first.entity().status());
    }
}

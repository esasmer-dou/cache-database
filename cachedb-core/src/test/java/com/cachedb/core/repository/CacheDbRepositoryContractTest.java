package com.reactor.cachedb.core.repository;

import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.model.WriteDependency;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.page.VersionedEntity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheDbRepositoryContractTest {

    @Test
    void partialUpdateUsesTheCurrentRedisVersion() {
        TestRepository repository = new TestRepository(new VersionedEntity<>(new Counter(10), 7));

        WriteReceipt<Counter, Long> receipt = repository.updateHot(42L, current -> new Counter(current.value() + 1));

        assertEquals(7L, repository.expectedVersion);
        assertEquals(11, receipt.entity().value());
    }

    @Test
    void partialUpdateNeverFallsBackToSqlWhenRedisVersionIsMissing() {
        TestRepository repository = new TestRepository(null);

        HotUpdateUnavailableException failure = assertThrows(
                HotUpdateUnavailableException.class,
                () -> repository.updateHot(42L, current -> current)
        );

        assertEquals(42L, failure.id());
        assertEquals(0, repository.sourceReads);
    }

    @Test
    void durabilityHelperReturnsTheTypedReceiptAndFailsLoudlyOnTimeout() {
        TestRepository durable = new TestRepository(null, true);
        TestRepository delayed = new TestRepository(null, false);
        WriteReceipt<Counter, Long> receipt = durable.save(new Counter(1));

        assertSame(receipt, durable.awaitDurableOrThrow(receipt, Duration.ofSeconds(1)));
        WriteDurabilityTimeoutException failure = assertThrows(
                WriteDurabilityTimeoutException.class,
                () -> delayed.awaitDurableOrThrow(receipt, Duration.ofMillis(10))
        );
        assertSame(receipt, failure.receipt());
        assertEquals(Duration.ofMillis(10), failure.timeout());
    }

    @Test
    void batchDurabilityFailurePreservesReceiptsAndOperationContext() {
        WriteReceipt<Counter, Long> receipt = new TestRepository(null).save(new Counter(1));

        WriteBatchDurabilityTimeoutException failure = new WriteBatchDurabilityTimeoutException(
                List.of(receipt),
                Duration.ofSeconds(2),
                "sample seed/orders"
        );

        assertEquals(List.of(receipt), failure.receipts());
        assertEquals(Duration.ofSeconds(2), failure.timeout());
        assertEquals("sample seed/orders", failure.operation());
        assertTrue(failure.getMessage().contains("sample seed/orders"));
    }

    private record Counter(int value) {
    }

    private static final class TestRepository implements CacheDbRepository<Counter, Long> {
        private final VersionedEntity<Counter> current;
        private long expectedVersion;
        private int sourceReads;
        private final boolean durable;

        private TestRepository(VersionedEntity<Counter> current) {
            this(current, true);
        }

        private TestRepository(VersionedEntity<Counter> current, boolean durable) {
            this.current = current;
            this.durable = durable;
        }

        @Override
        public HotLookup<Counter> findHotById(Long id) {
            return current == null ? HotLookup.notCached() : HotLookup.hit(current.entity());
        }

        @Override
        public Optional<VersionedEntity<Counter>> findVersionedHotById(Long id) {
            return Optional.ofNullable(current);
        }

        @Override
        public Optional<Counter> findSourceById(Long id) {
            sourceReads++;
            return Optional.empty();
        }

        @Override
        public WriteReceipt<Counter, Long> save(Counter entity) {
            return receipt(entity, 1);
        }

        @Override
        public WriteReceipt<Counter, Long> save(Counter entity, long expectedVersion) {
            this.expectedVersion = expectedVersion;
            return receipt(entity, expectedVersion + 1);
        }

        @Override
        public WriteReceipt<Counter, Long> saveAfter(Counter entity, WriteDependency dependency) {
            return receipt(entity, 1);
        }

        @Override
        public List<WriteReceipt<Counter, Long>> saveAll(Collection<Counter> entities) {
            return entities.stream().map(this::save).toList();
        }

        @Override
        public WriteReceipt<Counter, Long> deleteById(Long id) {
            return receipt(null, 1);
        }

        @Override
        public boolean isDurable(WriteReceipt<?, ?> receipt) {
            return durable;
        }

        @Override
        public boolean awaitDurable(WriteReceipt<?, ?> receipt, Duration timeout) {
            return durable;
        }

        private WriteReceipt<Counter, Long> receipt(Counter entity, long version) {
            return new WriteReceipt<>(entity, 42L, "counters", OperationType.UPSERT, version, Instant.EPOCH);
        }
    }
}

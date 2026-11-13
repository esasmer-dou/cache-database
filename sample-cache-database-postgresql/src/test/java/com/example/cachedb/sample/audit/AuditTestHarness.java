package com.example.cachedb.sample.audit;

import static org.mockito.Mockito.*;
import com.example.cachedb.sample.controls.ControlsAccess;
import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.core.retention.*;
import com.reactor.cachedb.starter.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class AuditTestHarness {
    final EntityRepository<EvictAuditNote, String> evict = backend();
    final EntityRepository<DeleteAuditNote, String> delete = backend();
    final SourceRepository<EvictAuditNote, String> evictSource = source();
    final SourceRepository<DeleteAuditNote, String> deleteSource = source();
    final DeleteAuditNotes data = mock(DeleteAuditNotes.class);
    final CacheDatabase database = mock(CacheDatabase.class);
    final ControlsAccess access = new ControlsAccess("supplier-test", "tenant-a-test", "tenant-b-test");
    final AtomicInteger sweeps = new AtomicInteger();
    RetentionException sweepFailure;
    final RetentionSweeper sweeper = new RetentionSweeper(List.of(target(EvictAuditNote.class), target(DeleteAuditNote.class)));
    final AuditStores stores = new AuditStores(evict, delete, evictSource, deleteSource, data, sweeper, database);
    final AuditNoteController controller = new AuditNoteController(stores, access);
    @SuppressWarnings("unchecked") private static <T> EntityRepository<T, String> backend() {
        EntityRepository<T, String> repository = mock(EntityRepository.class);
        when(repository.entityQueryCapacity()).thenReturn(EntityQueryCapacity.finite(8));
        return repository;
    }
    @SuppressWarnings("unchecked") private static <T> SourceRepository<T, String> source() { return mock(SourceRepository.class); }
    private RetentionTarget target(Class<?> type) {
        return new RetentionTarget() {
            public Class<?> entityType() { return type; }
            public String scopeToken() { return (type == EvictAuditNote.class ? "a" : "b").repeat(64); }
            public RetentionSweepResult sweep(int maxRows) {
                if (!com.reactor.cachedb.core.tenant.CacheTenantContext.isAdministrative()) throw new AssertionError("Missing administrative scope");
                sweeps.incrementAndGet();
                if (sweepFailure != null) throw sweepFailure;
                return new RetentionSweepResult(1, type == EvictAuditNote.class ? 1 : 0, 1,
                        type == DeleteAuditNote.class ? 1 : 0, 0, true, 1, Instant.EPOCH);
            }
        };
    }
}

package com.example.cachedb.sample.audit;

import com.reactor.cachedb.annotations.*;
import java.time.LocalDate;

@CacheEntity(table = "sample_audit_evict", redisNamespace = "sample-audit-evict", indexedColumns = {"created_on", "state"})
@CacheRetention(field = "createdOn", days = 1, action = RetentionAction.EVICT_CACHE)
public record EvictAuditNote(
        @CacheId(column = "note_id") String id,
        @CacheColumn("note_value") String note,
        @CacheColumn("created_on") LocalDate createdOn,
        @CacheColumn("state") String state) {
    public EvictAuditNote { AuditNoteRules.row(id, note, createdOn, state); }
    @Override public String toString() { return "EvictAuditNote[redacted]"; }
}

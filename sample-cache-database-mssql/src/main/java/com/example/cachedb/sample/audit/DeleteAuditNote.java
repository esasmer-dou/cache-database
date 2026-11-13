package com.example.cachedb.sample.audit;

import com.reactor.cachedb.annotations.*;
import java.time.LocalDate;

@CacheEntity(table = "sample_audit_delete", redisNamespace = "sample-audit-delete", indexedColumns = {"created_on", "state"})
@CacheRetention(field = "createdOn", days = 1, action = RetentionAction.DELETE)
public record DeleteAuditNote(
        @CacheId(column = "note_id") String id,
        @CacheColumn("note_value") String note,
        @CacheColumn("created_on") LocalDate createdOn,
        @CacheColumn("state") String state) {
    public DeleteAuditNote { AuditNoteRules.row(id, note, createdOn, state); }
    @Override public String toString() { return "DeleteAuditNote[redacted]"; }
}

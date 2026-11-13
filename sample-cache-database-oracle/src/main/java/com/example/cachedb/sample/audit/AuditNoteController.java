package com.example.cachedb.sample.audit;

import com.example.cachedb.sample.controls.ControlsAccess;
import com.reactor.cachedb.core.api.BulkDeleteFailureException;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("demo")
@ConditionalOnProperty(name = "sample.audit-api.enabled", havingValue = "true")
@RequestMapping("/api/examples/v019/audit-notes")
public final class AuditNoteController {
    private final AuditStores stores;
    private final ControlsAccess access;
    public AuditNoteController(AuditStores stores, ControlsAccess access) { this.stores = stores; this.access = access; }
    public record Input(String note, Boolean expired) {}
    public record Slots(List<Integer> slots) {}
    public record SweepInput(Integer maxRows) {}
    public record Receipt(String id, String version, String operation, Instant acceptedAt) {
        static Receipt from(WriteReceipt<?, ?> receipt) {
            if (!(receipt.id() instanceof String id)) throw new IllegalStateException("Unexpected receipt identity");
            AuditNoteRules.slot(id);
            return new Receipt(id, Long.toString(receipt.version()), receipt.operationType().name(), receipt.acceptedAt());
        }
    }
    public record Note(int slot, String note, String createdOn, String state, String origin,
            Receipt receipt, boolean sqlDurabilityConfirmed) {}
    public record DeleteOutcome(String api, String phase, List<String> selected, List<Receipt> accepted,
            List<String> rejected, List<String> uncertain, List<String> cleanupPending, int notAttempted,
            String receiptAvailability, boolean sqlDurabilityConfirmed, boolean retrySafe) {}
    public record SweepOutcome(String action, int examined, int evicted, int indexCleaned, int deleteAccepted,
            int skipped, boolean exhausted, long cutoff, Instant finishedAt,
            boolean indexedResidentOnly, boolean sqlDurabilityConfirmed, boolean sourceErasureConfirmed) {}

    @PutMapping("/{policy}/{slot}")
    public ResponseEntity<Note> save(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-CacheDB-Causal", required = false) String causal,
            @PathVariable String policy, @PathVariable int slot, @RequestBody Input input) {
        AuditNoteRules.verify(access, auth, causal);
        return ResponseEntity.accepted().body(write(policy, slot, input, false));
    }
    @PostMapping("/{policy}/{slot}/source-roundtrip")
    public Note sourceRoundtrip(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-CacheDB-Causal", required = false) String causal,
            @PathVariable String policy, @PathVariable int slot, @RequestBody Input input) {
        AuditNoteRules.verify(access, auth, causal);
        return write(policy, slot, input, true);
    }
    private Note write(String policy, int slot, Input input, boolean durable) {
        AuditNoteRules.policy(policy);
        String id = AuditNoteRules.id(slot);
        if (input == null || input.expired() == null) throw new IllegalArgumentException("note and expired are required");
        LocalDate date = LocalDate.now(ZoneOffset.UTC).minusDays(input.expired() ? 2 : 0);
        String state = input.expired() ? "EXPIRED" : "ACTIVE";
        if ("evict".equals(policy)) {
            var row = new EvictAuditNote(id, input.note(), date, state);
            var receipt = stores.evict().saveWithReceipt(row);
            if (durable) {
                confirm(receipt);
                if (!stores.evictSource().findById(id).filter(row::equals).isPresent()) throw sourceChanged(receipt);
            }
            return note(row, durable ? "SOURCE_VERIFIED" : "REDIS_ACCEPTED", Receipt.from(receipt), durable);
        }
        var row = new DeleteAuditNote(id, input.note(), date, state);
        var receipt = stores.delete().saveWithReceipt(row);
        if (durable) {
            confirm(receipt);
            if (!stores.deleteSource().findById(id).filter(row::equals).isPresent()) throw sourceChanged(receipt);
        }
        return note(row, durable ? "SOURCE_VERIFIED" : "REDIS_ACCEPTED", Receipt.from(receipt), durable);
    }
    private void confirm(WriteReceipt<?, ?> receipt) {
        if (!stores.database().awaitDurable(receipt, Duration.ofSeconds(5)))
            throw new WriteUnconfirmed(HttpStatus.GATEWAY_TIMEOUT, receipt, "DURABILITY_TIMEOUT");
    }
    private static ResponseStatusException sourceChanged(WriteReceipt<?, ?> receipt) {
        return new WriteUnconfirmed(HttpStatus.CONFLICT, receipt, "SOURCE_CHANGED");
    }
    public static final class WriteUnconfirmed extends ResponseStatusException {
        private final Receipt accepted;
        WriteUnconfirmed(HttpStatus status, WriteReceipt<?, ?> receipt, String reason) {
            super(status, reason); this.accepted = Receipt.from(receipt);
        }
        public Receipt accepted() { return accepted; }
    }
    @GetMapping("/{policy}/{slot}")
    public Note hot(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-CacheDB-Causal", required = false) String causal,
            @PathVariable String policy, @PathVariable int slot) {
        AuditNoteRules.verify(access, auth, causal);
        AuditNoteRules.policy(policy); String id = AuditNoteRules.id(slot);
        if ("evict".equals(policy)) return note(stores.evict().findHotById(id).value(), "HOT", null, false);
        return note(stores.delete().findHotById(id).value(), "HOT", null, false);
    }
    @GetMapping("/{policy}/{slot}/source")
    public Note source(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-CacheDB-Causal", required = false) String causal,
            @PathVariable String policy, @PathVariable int slot) {
        AuditNoteRules.verify(access, auth, causal);
        AuditNoteRules.policy(policy); String id = AuditNoteRules.id(slot);
        if ("evict".equals(policy)) return note(stores.evictSource().findById(id).orElse(null), "SOURCE_OBSERVED", null, false);
        return note(stores.deleteSource().findById(id).orElse(null), "SOURCE_OBSERVED", null, false);
    }
    @PostMapping("/delete/spring-data")
    public ResponseEntity<DeleteOutcome> springData(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-CacheDB-Causal", required = false) String causal) {
        AuditNoteRules.verify(access, auth, causal);
        var capacity = stores.delete().entityQueryCapacity();
        if (capacity.kind() == com.reactor.cachedb.core.api.EntityQueryCapacity.Kind.FINITE && capacity.maxRows() == 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AUDIT_DERIVED_DELETE_ZERO_QUERY_BUDGET");
        try {
            var selected = stores.data().deleteByState("EXPIRED");
            if (selected.size() > 8) throw new IllegalStateException("Audit selection exceeds fixed identities");
            return ResponseEntity.accepted().body(new DeleteOutcome("SPRING_DATA", "COMPLETE",
                    selected.stream().map(DeleteAuditNote::id).toList(), List.of(), List.of(), List.of(), List.of(),
                    0, "NOT_EXPOSED_BY_SPRING_DATA", false, false));
        } catch (BulkDeleteFailureException partial) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(partial("SPRING_DATA", partial));
        }
    }
    @PostMapping("/delete/bulk")
    public ResponseEntity<DeleteOutcome> bulk(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-CacheDB-Causal", required = false) String causal, @RequestBody Slots slots) {
        AuditNoteRules.verify(access, auth, causal);
        var ids = AuditNoteRules.ids(slots == null ? null : slots.slots());
        try {
            var receipts = stores.delete().bulkDelete(ids);
            return ResponseEntity.accepted().body(new DeleteOutcome("NATIVE_BULK", "COMPLETE", ids,
                    receipts.stream().map(Receipt::from).toList(), List.of(), List.of(), List.of(), 0,
                    "ACTUAL_WRITE_RECEIPTS", false, false));
        } catch (BulkDeleteFailureException partial) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(partial("NATIVE_BULK", partial));
        }
    }
    static DeleteOutcome partial(String api, BulkDeleteFailureException failure) {
        if (failure.accepted().size() > 8) throw new IllegalStateException("Audit receipt outcome exceeds fixed identities");
        return new DeleteOutcome(api, failure.phase().name(), List.of(),
                failure.accepted().stream().map(Receipt::from).toList(), ids(failure.rejected()), ids(failure.uncertain()),
                ids(failure.cleanupPending()), failure.notAttempted(), "ACTUAL_ACCEPTED_RECEIPTS", false, false);
    }
    private static List<String> ids(List<?> values) {
        if (values.size() > 8) throw new IllegalStateException("Audit outcome exceeds fixed identities");
        return values.stream().map(value -> {
            if (!(value instanceof String id)) throw new IllegalStateException("Unexpected outcome identity");
            AuditNoteRules.slot(id); return id;
        }).toList();
    }
    @PostMapping("/{policy}/sweep")
    public SweepOutcome sweep(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-CacheDB-Causal", required = false) String causal,
            @PathVariable String policy, @RequestBody SweepInput input) {
        AuditNoteRules.verify(access, auth, causal);
        AuditNoteRules.policy(policy);
        int count = AuditNoteRules.maxRows(input == null ? null : input.maxRows());
        try (var admin = CacheTenantContext.administrative()) {
            var result = stores.sweeper().sweepOnce("evict".equals(policy) ? EvictAuditNote.class : DeleteAuditNote.class, count);
            return new SweepOutcome("evict".equals(policy) ? "EVICT_CACHE" : "DELETE", result.examined(),
                    result.evicted(), result.indexCleaned(), result.deleteAccepted(), result.skipped(),
                    result.exhausted(), result.cutoff(), result.finishedAt(), true, false, false);
        }
    }
    private static Note note(EvictAuditNote row, String origin, Receipt receipt, boolean durable) {
        if (row == null) throw missing();
        return new Note(AuditNoteRules.slot(row.id()), row.note(), row.createdOn().toString(), row.state(), origin, receipt, durable);
    }
    private static Note note(DeleteAuditNote row, String origin, Receipt receipt, boolean durable) {
        if (row == null) throw missing();
        return new Note(AuditNoteRules.slot(row.id()), row.note(), row.createdOn().toString(), row.state(), origin, receipt, durable);
    }
    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Not observed on the selected read path; no source-erasure claim");
    }
}

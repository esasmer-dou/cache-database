package com.example.cachedb.sample.encryption;

import com.example.cachedb.sample.controls.ControlsAccess;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("demo & encryption")
@RequestMapping("/api/examples/v019/encryption/notes")
public final class EncryptedNoteController {
    private final EncryptedNotes notes;
    private final ControlsAccess access;
    public EncryptedNoteController(EncryptedNotes notes, ControlsAccess access) { this.notes = notes; this.access = access; }
    public record Input(String note) {
        @Override public String toString() { return "Input[redacted]"; }
    }
    public record Output(int slot, String note, String version, boolean sqlDurabilityConfirmed) {
        @Override public String toString() { return "Output[redacted]"; }
    }
    static String id(int slot) {
        if (slot < 1 || slot > 8) throw new IllegalArgumentException("slot must be 1..8");
        return "note-" + slot;
    }
    static void verify(ControlsAccess access, String auth, String causal) {
        access.supplier(auth);
        if ((causal != null && !causal.isEmpty()) || CacheCausalContext.hasRequirements()
                || CacheTenantContext.isAdministrative() || CacheTenantContext.currentTenant().isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Global supplier notes reject ambient tenant/causal requirements");
    }
    @PutMapping("/{slot}")
    public ResponseEntity<Output> save(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-CacheDB-Causal", required = false) String causal,
            @PathVariable int slot, @RequestBody Input input) {
        verify(access, auth, causal);
        var receipt = notes.save(new EncryptedNote(id(slot), input == null ? null : input.note()));
        return ResponseEntity.accepted().body(new Output(slot, receipt.entity().note(), Long.toString(receipt.version()), false));
    }
    @GetMapping("/{slot}")
    public Output hot(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-CacheDB-Causal", required = false) String causal, @PathVariable int slot) {
        verify(access, auth, causal);
        var row = notes.findVersionedHotById(id(slot))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No hot note; source presence is not inferred"));
        return new Output(slot, row.entity().note(), Long.toString(row.version()), false);
    }
    @PostMapping("/{slot}/source-roundtrip")
    public Output sourceRoundtrip(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-CacheDB-Causal", required = false) String causal,
            @PathVariable int slot, @RequestBody Input input) {
        verify(access, auth, causal);
        var entity = new EncryptedNote(id(slot), input == null ? null : input.note());
        var receipt = notes.save(entity);
        if (!notes.awaitDurable(receipt, Duration.ofSeconds(5)))
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Write accepted; SQL durability was not confirmed within five seconds");
        var source = notes.findSourceById(entity.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Durable source row was not observed"));
        if (!source.equals(entity))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source changed before observation; no read-your-write claim");
        return new Output(slot, source.note(), Long.toString(receipt.version()), true);
    }
}

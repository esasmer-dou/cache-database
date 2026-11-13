package com.example.cachedb.sample.audit;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class AuditNoteRules {
    private AuditNoteRules() {}
    public static String id(int slot) {
        if (slot < 1 || slot > 8) throw new IllegalArgumentException("slot must be 1..8");
        return "audit-" + slot;
    }
    public static int slot(String id) {
        if (id == null || id.length() != 7 || !id.startsWith("audit-"))
            throw new IllegalArgumentException("Invalid audit identity");
        int slot = id.charAt(6) - '0'; id(slot); return slot;
    }
    public static String policy(String policy) {
        if (!"evict".equals(policy) && !"delete".equals(policy))
            throw new IllegalArgumentException("policy must be evict or delete");
        return policy;
    }
    public static void row(String id, String note, LocalDate createdOn, String state) {
        slot(id);
        if (note == null || note.isEmpty() || note.length() > 512 || note.indexOf('\0') >= 0)
            throw new IllegalArgumentException("note must contain 1..512 UTF-8 bytes without NUL");
        for (int i = 0; i < note.length(); i++) {
            char c = note.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= note.length() || !Character.isLowSurrogate(note.charAt(i)))
                    throw new IllegalArgumentException("Invalid Unicode");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid Unicode");
        }
        if (note.getBytes(StandardCharsets.UTF_8).length > 512 || createdOn == null
                || !("EXPIRED".equals(state) || "ACTIVE".equals(state)))
            throw new IllegalArgumentException("Invalid audit row");
    }
    public static List<String> ids(List<Integer> slots) {
        if (slots == null || slots.isEmpty() || slots.size() > 8)
            throw new IllegalArgumentException("slots must contain 1..8 unique values");
        var result = new java.util.ArrayList<String>(slots.size());
        for (Integer slot : slots) {
            if (slot == null) throw new IllegalArgumentException("Null slot");
            String id = id(slot);
            if (result.contains(id)) throw new IllegalArgumentException("Duplicate slot");
            result.add(id);
        }
        return List.copyOf(result);
    }
    public static int maxRows(Integer count) {
        if (count == null || count < 1 || count > 8) throw new IllegalArgumentException("maxRows must be 1..8");
        return count;
    }
    public static void verify(com.example.cachedb.sample.controls.ControlsAccess access, String auth, String causal) {
        access.supplier(auth);
        if ((causal != null && !causal.isEmpty())
                || com.reactor.cachedb.core.causal.CacheCausalContext.hasRequirements()
                || com.reactor.cachedb.core.tenant.CacheTenantContext.isAdministrative()
                || com.reactor.cachedb.core.tenant.CacheTenantContext.currentTenant().isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Global audit examples reject ambient tenant/causal requirements");
    }
}

package com.example.cachedb.sample.rangedata;

import com.example.cachedb.sample.controls.ControlsAccess;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class RangeDataGuard {
    private static final java.util.regex.Pattern RUN = java.util.regex.Pattern.compile("[a-z0-9]{1,32}");
    private static final java.util.regex.Pattern SLOT = java.util.regex.Pattern.compile("[a-z0-9]{1,8}");
    private static final java.util.regex.Pattern RANK = java.util.regex.Pattern.compile("-?(0|[1-9][0-9]{0,18})");
    private static final java.util.regex.Pattern CURSOR = java.util.regex.Pattern.compile("[A-Za-z0-9_=-]*");
    private final ControlsAccess access;
    private final Semaphore permits = new Semaphore(32);
    public RangeDataGuard(ControlsAccess access) { this.access = java.util.Objects.requireNonNull(access); }

    public <T> T call(String authorization, String causal, Function<String, T> operation) {
        String tenant = access.tenant(authorization);
        if ((causal != null && !causal.isEmpty()) || CacheCausalContext.hasRequirements())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "These Redis-resident examples do not certify causal query completeness");
        if (CacheTenantContext.isAdministrative()
                || CacheTenantContext.currentTenant().filter(active -> !active.equals(tenant)).isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ambient tenant differs from verified identity");
        if (!permits.tryAcquire())
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "32 range/data operations already active");
        try (var scope = CacheTenantContext.withTenant(tenant)) {
            return operation.apply(tenant);
        } finally { permits.release(); }
    }
    public static String run(String value) {
        if (value == null || !RUN.matcher(value).matches())
            throw new IllegalArgumentException("run must contain 1..32 lowercase ASCII letters/digits");
        return value;
    }
    public static String id(String tenant, String run, String slot) {
        if (slot == null || !SLOT.matcher(slot).matches())
            throw new IllegalArgumentException("slot must contain 1..8 lowercase ASCII letters/digits");
        return tenant + ":" + run(run) + ":" + slot;
    }
    public static long rank(String value) {
        if (value == null || !RANK.matcher(value).matches())
            throw new IllegalArgumentException("rank must be a decimal string in the signed 64-bit range");
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("rank exceeds signed 64-bit range"); }
    }
    public static int limit(int value) {
        if (value < 1 || value > 32) throw new IllegalArgumentException("limit must be 1..32");
        return value;
    }
    public static String cursor(String value) {
        if (value != null && (value.length() > 8192 || !CURSOR.matcher(value).matches()))
            throw new IllegalArgumentException("cursor must be at most 8192 base64url characters");
        return value == null || value.isEmpty() ? null : value;
    }
}

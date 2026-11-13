package com.reactor.cachedb.core.protection;

import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Binding;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Administrative ownership preparation for EXISTING Redis identities, not an ID allocator,
 * source-history inventory, generation migration, or permission to open a tenant.
 */
public final class SourceOwnerOnboarding {
    public static final int CHUNK = 128, MAX_ID_BYTES = 1024, MAX_PAGE_BYTES = 131072;
    public static final long MAX_VERSION = 9007199254740991L;
    private SourceOwnerOnboarding() {}

    /** encode/decode must be canonical, bounded and reflection-free. */
    public interface IdCodec<I> {
        String encode(I id);
        I decode(String canonical);
    }

    public record Plan(Binding binding, String versionColumn, String idFormatToken) {
        public Plan {
            Objects.requireNonNull(binding);
            if (!binding.tenantPurgeEnabled()) fail(ProtectionException.Code.UNWIRED_PROTOCOL);
            // Reuse the existing SQL identifier validator; do not accept SQL expressions.
            new TenantPurgeProtocol.SourcePlan(binding.source().table(), versionColumn, binding.source().idColumn());
            ProtectionKey.token(idFormatToken);
        }
        public String fingerprint() {
            return ProtectionKey.hash("source-owner-v1:" + binding.fingerprint() + ":" + versionColumn + ":" + idFormatToken);
        }
    }

    public record Request(Plan plan, Generation generation, String tenant, UUID operationId,
                          String operatorToken, int leaseSeconds) {
        public Request {
            Objects.requireNonNull(plan); Objects.requireNonNull(generation); Objects.requireNonNull(operationId);
            ProtectionKey.token(operatorToken);
            if (!plan.binding().key(tenant).equals(generation.key()) || leaseSeconds < 15 || leaseSeconds > 120)
                fail(ProtectionException.Code.INVALID_REQUEST);
        }
        @Override public String toString() { return "OwnerOnboardingRequest[redacted]"; }
    }

    public record Session(Request request, Lease lease) {
        public Session {
            Objects.requireNonNull(request); Objects.requireNonNull(lease);
            if (!request.generation().key().equals(lease.key()) || !request.operationId().equals(lease.operationId()))
                fail(ProtectionException.Code.INVALID_REQUEST);
        }
        @Override public String toString() { return "OwnerOnboardingSession[redacted]"; }
    }

    /** Page of a stable, sealed EXISTING-artifact worklist. Never a raw Redis SCAN response. */
    public record Page(UUID id, String nextCursor, boolean last, List<String> ids) {
        public Page {
            Objects.requireNonNull(id); TenantPurgeProtocol.cursor(nextCursor);
            if (ids == null || ids.size() > CHUNK || ids.isEmpty() && !last)
                fail(ProtectionException.Code.INVALID_REQUEST);
            ids = List.copyOf(ids);
            int bytes = 0;
            var unique = new HashSet<String>();
            for (String idValue : ids) {
                bytes = Math.addExact(bytes, canonicalId(idValue).getBytes(StandardCharsets.UTF_8).length);
                if (!unique.add(idValue)) fail(ProtectionException.Code.INVALID_REQUEST);
            }
            if (bytes > MAX_PAGE_BYTES) fail(ProtectionException.Code.INVALID_REQUEST);
        }
        @Override public String toString() { return "OwnerOnboardingPage[redacted]"; }
    }

    public enum State { FENCING, RUNNING, WARMED, INCOMPLETE }
    public enum Ownership { SOURCE_OWNED, UNKNOWN }
    public record OwnerProof<I>(I id, String canonicalId, Ownership ownership, long sourceVersion) {
        public OwnerProof {
            Objects.requireNonNull(id); SourceOwnerOnboarding.canonicalId(canonicalId); Objects.requireNonNull(ownership);
            if (sourceVersion < 0 || sourceVersion > MAX_VERSION
                    || ownership == Ownership.UNKNOWN && sourceVersion != 0)
                fail(ProtectionException.Code.INVALID_PROGRESS);
        }
        @Override public String toString() { return "SourceOwnerProof[redacted]"; }
    }

    /** Counts are logical page outcomes, including idempotent repeats, not commands executed. */
    public record Installed(int owned, int absent, int unknown) {
        public Installed {
            if (owned < 0 || owned > CHUNK || absent < 0 || absent > CHUNK || unknown < 0 || unknown > CHUNK
                    || owned + absent + unknown > CHUNK)
                fail(ProtectionException.Code.INVALID_PROGRESS);
        }
        public int total() { return owned + absent + unknown; }
    }

    public record Checkpoint(long revision, UUID operationId, String planToken, long generation,
            String operatorToken, State state, String inventoryToken, String cursor, Page pending,
            boolean last, long examined, long owned, long absent, long unknown) {
        public Checkpoint {
            Objects.requireNonNull(operationId); Objects.requireNonNull(state);
            ProtectionKey.token(planToken); ProtectionKey.token(operatorToken); TenantPurgeProtocol.cursor(cursor);
            if (inventoryToken != null) ProtectionKey.token(inventoryToken);
            if (revision < 0 || generation <= 0 || examined < 0 || owned < 0 || absent < 0 || unknown < 0
                    || owned > examined || absent > examined - owned || unknown != examined - owned - absent
                    || state == State.FENCING && (inventoryToken != null || pending != null || last || examined != 0)
                    || state != State.FENCING && inventoryToken == null || pending != null && last
                    || (state == State.WARMED || state == State.INCOMPLETE) && pending != null
                    || state == State.WARMED && (!last || unknown != 0))
                fail(ProtectionException.Code.INVALID_PROGRESS);
        }
        public static Checkpoint initial(Request request) {
            return new Checkpoint(0, request.operationId(), request.plan().fingerprint(), request.generation().value(),
                    request.operatorToken(), State.FENCING, null, "-", null, false, 0, 0, 0, 0);
        }
        public Checkpoint fenced(String inventory) {
            if (state != State.FENCING) fail(ProtectionException.Code.INVALID_PROGRESS);
            return next(State.RUNNING, inventory, cursor, null, false, examined, owned, absent, unknown);
        }
        public Checkpoint staged(Page page) {
            if (state != State.RUNNING || pending != null || last || page == null
                    || !page.last() && page.nextCursor().equals(cursor)) fail(ProtectionException.Code.INVALID_PROGRESS);
            return next(state, inventoryToken, cursor, page, false, examined, owned, absent, unknown);
        }
        public Checkpoint acknowledged(Installed result) {
            if (state != State.RUNNING || pending == null || result.total() != pending.ids().size())
                fail(ProtectionException.Code.INVALID_PROGRESS);
            return next(result.unknown() == 0 ? State.RUNNING : State.INCOMPLETE, inventoryToken,
                    pending.nextCursor(), null, pending.last(), Math.addExact(examined, result.total()),
                    Math.addExact(owned, result.owned()), Math.addExact(absent, result.absent()),
                    Math.addExact(unknown, result.unknown()));
        }
        public Checkpoint verified() {
            if (state != State.RUNNING || pending != null || !last || unknown != 0)
                fail(ProtectionException.Code.INVALID_PROGRESS);
            return next(State.WARMED, inventoryToken, cursor, null, true, examined, owned, absent, unknown);
        }
        public Checkpoint incomplete() {
            if (state != State.RUNNING || pending != null) fail(ProtectionException.Code.INVALID_PROGRESS);
            return next(State.INCOMPLETE, inventoryToken, cursor, null, last, examined, owned, absent, unknown);
        }
        private Checkpoint next(State next, String inventory, String nextCursor, Page page, boolean terminal,
                                long seen, long warmed, long missing, long unresolved) {
            return new Checkpoint(Math.addExact(revision, 1), operationId, planToken, generation, operatorToken,
                    next, inventory, nextCursor, page, terminal, seen, warmed, missing, unresolved);
        }
        @Override public String toString() {
            return "OwnerOnboardingCheckpoint[state=" + state + ",examined=" + examined + ",unknown=" + unknown + "]";
        }
    }

    /**
     * Native administrative bridge, not an attestation supplied by a request caller.
     * Every method validates the actual enrolled runtime/store/queue roster, current distributed
     * CLOSED state, lease epoch/ownership, generation, binding and source-writer drain.
     * Missing/unattested routes must throw INCOMPLETE_INVENTORY or return false from verify.
     */
    public interface ClosedRuntime<I> {
        String closeAndDrain(Session session);
        void requireClosed(Session session, String inventoryToken);
        Page existingIdentities(Session session, String inventoryToken, String cursor, int limit, int maxBytes);
        /**
         * Atomic per identity, idempotent by operation/page. Recheck closure/lease/owner conflicts,
         * validate source proof, and set an owner ONLY if identity artifacts already exist.
         * UNKNOWN proofs never install owners. Do not create payload/version/tombstone artifacts,
         * overwrite foreign owners, stamp old queue/external history, or change versions/TTL.
         * Repeating a page returns the same logical counts; a partial network failure throws.
         */
        Installed installExisting(Session session, String inventoryToken, Page page, List<OwnerProof<I>> proofs);
        /** Fresh bounded verification of the sealed worklist, NOT a universal erasure/creation certificate. */
        boolean verify(Session session, String inventoryToken);
    }

    public interface Store<I> {
        Session acquire(Request request);
        void renew(Session session);
        Checkpoint load(Session session);
        Checkpoint save(Session session, Checkpoint before, Checkpoint after);
        /** Indexed one-batch source query under the SAME CLOSED durable guard row. */
        List<OwnerProof<I>> resolve(Session session, Checkpoint pending);
        void release(Session session);
    }

    public static String canonicalId(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_ID_BYTES)
            fail(ProtectionException.Code.INVALID_REQUEST);
        ProtectionKey.hash(value); // Also rejects malformed Unicode.
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_ID_BYTES)
            fail(ProtectionException.Code.INVALID_REQUEST);
        for (int i = 0; i < value.length(); i++)
            if (Character.isISOControl(value.charAt(i))) fail(ProtectionException.Code.INVALID_REQUEST);
        return value;
    }
    public static void fail(ProtectionException.Code code) { throw new ProtectionException(code); }
}

package com.reactor.cachedb.core.protection;

import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Initial source population and activation contracts; no existing public record components change. */
public final class InitialTenantBootstrap {
    private InitialTenantBootstrap() {}
    public enum Phase { CLOSURE, NATIVE_INVENTORY, DURABLE_VERSIONS, SOURCE_VERSIONS, HOT_WINDOW,
                        VERIFY_WINDOW, READY, SQL_OPEN, ACTIVE, INCOMPLETE }
    public enum RuntimeState { CLOSED, OPEN, INCOMPLETE }

    public record Request(Generation generation, String tenant, UUID operationId, String operatorToken, int leaseSeconds) {
        public Request {
            Objects.requireNonNull(generation); Objects.requireNonNull(operationId);
            ProtectionKey.checkTenant(tenant); ProtectionKey.token(operatorToken);
            if (leaseSeconds < 15 || leaseSeconds > 120) invalid();
        }
        @Override public String toString() { return "InitialBootstrapRequest[redacted]"; }
    }
    /** A real durable guard lease, never a private fence or a request-supplied CLOSED Boolean. */
    public record Session(Request request, Lease lease, String planToken, String manifestToken) {
        public Session {
            Objects.requireNonNull(request); Objects.requireNonNull(lease);
            ProtectionKey.token(planToken); ProtectionKey.token(manifestToken);
            if (!request.generation().key().equals(lease.key()) || !request.operationId().equals(lease.operationId())) invalid();
        }
        @Override public String toString() { return "InitialBootstrapSession[redacted]"; }
    }
    public record Closure(TenantReadFence.Evidence evidence, String runId, String rosterToken) {
        public Closure {
            Objects.requireNonNull(evidence); ProtectionKey.token(rosterToken);
            if (evidence.state() != TenantReadFence.State.CLOSED || runId == null || !runId.matches("[0-9a-f]{40}")) invalid();
        }
    }
    public record RowRef(String canonicalId, long sourceVersion, String digest) {
        public RowRef {
            SourceOwnerOnboarding.canonicalId(canonicalId); positiveVersion(sourceVersion); ProtectionKey.token(digest);
        }
        @Override public String toString() { return "InitialSourceRowRef[redacted]"; }
    }
    public record Row<T, ID>(ID id, T entity, RowRef proof, String redisPayload, int payloadBytes) {
        public Row {
            Objects.requireNonNull(id); Objects.requireNonNull(entity); Objects.requireNonNull(proof);
            if (redisPayload == null || payloadBytes < 1 || payloadBytes > 1048576
                    || com.reactor.cachedb.core.codec.Utf8Length.of(redisPayload) != payloadBytes) invalid();
        }
        @Override public String toString() { return "InitialSourceRow[redacted]"; }
    }
    public record Page<T, ID>(UUID id, List<Row<T, ID>> rows, boolean last) {
        public Page { Objects.requireNonNull(id); rows = List.copyOf(rows); if (rows.size() > 128 || rows.isEmpty() && !last) invalid(); }
        @Override public String toString() { return "InitialSourcePage[redacted]"; }
    }
    public record WindowProof(long rows, String digest) {
        public WindowProof { if (rows < 0 || rows > 10000) invalid(); ProtectionKey.token(digest); }
    }
    public record Activation(String token, String planToken, String manifestToken, String inventoryToken,
            WindowProof window, String runtimeProof, TenantVersionRange range) {
        public Activation {
            ProtectionKey.token(token); ProtectionKey.token(planToken); ProtectionKey.token(manifestToken);
            ProtectionKey.token(inventoryToken); Objects.requireNonNull(window); ProtectionKey.token(runtimeProof);
            Objects.requireNonNull(range);
            if (!range.inventory().equals(inventoryToken)) invalid();
        }
    }
    /**
     * Store-owned keyset reader: only committed acknowledged pages, at most one captured page
     * (128 rows) per call. Cursor 0 starts; -1 is terminal. Never materialize the whole window.
     * Calls revalidate the original Session/plan/operation and exact source rows under the guard.
     */
    public interface AcknowledgedPages<T, ID> {
        String planToken();
        String manifestToken();
        AcknowledgedPage<T, ID> read(Session session, int pageCursor, int maxBytes);
    }
    public record AcknowledgedPage<T, ID>(int pageNumber, int nextPage, Page<T, ID> page,
            boolean verified, int encodedBytes, WindowProof window) {
        public AcknowledgedPage {
            Objects.requireNonNull(page); Objects.requireNonNull(window);
            if (pageNumber < 0 || pageNumber >= InitialSourceHotWindow.MAX_PAGES
                    || nextPage != -1 && nextPage != pageNumber + 1
                    || nextPage >= InitialSourceHotWindow.MAX_PAGES || encodedBytes < 1 || encodedBytes > 8 * 1048576)
                invalid();
        }
    }
    public record Progress(Phase phase, long sourceRows, long warmedRows, long highwater, String planToken) {
        public Progress { Objects.requireNonNull(phase); ProtectionKey.token(planToken);
            if (sourceRows < 0 || warmedRows < 0 || warmedRows > 10000 || highwater < 0 || highwater > TenantVersionRange.MAX_VERSION) invalid(); }
    }

    /**
     * Implemented by the actual enrolled root's CLOSED-maintenance bridge, not an application
     * approval stub. Every mutation validates the actual client/key strategy, generation,
     * live maintenance ownership, roster/manifest, run ID and physical drain evidence.
     */
    public interface NativeBridge<T, ID> {
        String manifestToken();
        void requireBinding(InitialSourceHotWindow<T, ID> window);
        /** Bound once by the root coordinator to the real JDBC journal, never a process-local map. */
        void bindAcknowledgedPages(AcknowledgedPages<T, ID> pages);
        Closure closeAndDrain(Session session);
        void requireClosed(Session session, Closure closure);
        /** Atomic owner claim + payload/source-version/index/L1 installation for <=128 selected rows.
         * No owner-only SET, SQL enqueue, new business version, NULL normalization, or general ID claim.
         * Throws on ANY conflict/admission rejection/unknown partial outcome. Retries are idempotent. */
        void hydrate(Session session, Closure closure, Page<T, ID> page);
        /** One fresh verification batch; the coordinator durably visits EVERY acknowledged page. */
        void verifyPage(Session session, Closure closure, Page<T, ID> page);
        /** Final fresh verification walks the bound durable acknowledged-page reader (max 128 pages).
         * It reconciles current owner/payload/version/index/quota/L1 and TTL through activation;
         * neither prior ACK/verified flags nor the rows/digest summary substitutes for live evidence. */
        String verifyWindow(Session session, Closure closure, WindowProof window);
        void installRange(Session session, Closure closure, TenantVersionRange range);
        /** OPEN is accepted only for this exact durable activation and its installed range/run ID.
         * Never close or reinstall a range when recovering a successfully opened activation. */
        RuntimeState observeActivation(Session session, Activation activation);
        /** Invoke only after SQL OPEN commit. Conditional same-slot activation; original replay stamps stay original. */
        void activate(Session session, Closure closure, Activation activation);
    }
    public static void positiveVersion(long value) {
        if (value < 1 || value > TenantVersionRange.MAX_VERSION) invalid();
    }
    private static void invalid() { throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST); }
}

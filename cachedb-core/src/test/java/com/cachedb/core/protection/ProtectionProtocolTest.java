package com.reactor.cachedb.core.protection;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProtectionProtocolTest {
    @Test void identityAndPublicDiagnosticsNeverContainRawTenantOrConfirmation() {
        String raw = "private.person@example.test";
        var key = ProtectionKey.of("orders", raw);
        assertFalse(key.toString().contains(raw));
        assertFalse(key.progressKeySuffix().contains(raw));
        assertEquals(key, ProtectionKey.of("orders", raw));
        assertNotEquals(key.tenantToken(), ProtectionKey.of("invoices", raw).tenantToken());
        var options = new PurgeOptions(UUID.randomUUID(), raw, ProtectionKey.hash("operator"), false, 128, 15, 5000, PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA);
        assertFalse(options.toString().contains(raw));
        assertThrows(ProtectionException.class, () -> ProtectionKey.of("orders", "\uD800"));
        assertThrows(ProtectionException.class, () -> ProtectionKey.of("orders", "tenant\n"));
        assertThrows(ProtectionException.class, () -> new PurgeOptions(UUID.randomUUID(), raw, raw, false, 128, 15, 5000, PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA));
    }

    @Test void progressCodecRoundTripsAMaximumPendingPageAndRejectsCorruptOrOversizedInput() {
        var ids = new ArrayList<String>(128);
        for (int i = 0; i < 128; i++) ids.add(ProtectionKey.hash("object-" + i));
        var page = new Page(UUID.randomUUID(), ProtectionKey.hash("cursor"), false, ids);
        var progress = new Progress(7, UUID.randomUUID(), 3, 0, Stage.REDIS, true,
                ProtectionKey.hash("operator"), Instant.EPOCH, null, 4, 8, 0,
                ProtectionKey.hash("inventory"), TenantPurgeProtocol.START, page, 64, ProtectionKey.hash("binding"));
        byte[] encoded = PurgeProgressCodec.encode(progress);
        assertTrue(encoded.length <= PurgeProgressCodec.MAX_BYTES);
        assertEquals(progress, PurgeProgressCodec.decode(encoded));
        assertFalse(progress.toString().contains(ids.get(0)));
        assertFalse(page.toString().contains(ids.get(0)));
        assertThrows(ProtectionException.class, () -> PurgeProgressCodec.decode(new byte[PurgeProgressCodec.MAX_BYTES + 1]));
        assertThrows(ProtectionException.class, () -> PurgeProgressCodec.decode(Arrays.copyOf(encoded, encoded.length - 1)));
        encoded[0] = 0;
        assertThrows(ProtectionException.class, () -> PurgeProgressCodec.decode(encoded));
    }

    @Test void retainedRangeHasExactLimitsAndRedactedDiagnostics() {
        var key=ProtectionKey.of("orders","a");var request=UUID.randomUUID();String run="a".repeat(40),inventory=ProtectionKey.hash("inventory");
        var grant=new TenantVersionRange(key,1,1,1,TenantVersionRange.MAX_VERSION,TenantVersionRange.MAX_VERSION,request,run,inventory);
        assertEquals(9_007_199_254_740_991L,grant.last());assertEquals("TenantVersionRange[redacted]",grant.toString());
        assertThrows(ProtectionException.class,()->new TenantVersionRange(key,1,1,1,1,1_000_001,request,run,inventory));
        assertThrows(ProtectionException.class,()->new TenantVersionRange(key,1,1,1,TenantVersionRange.MAX_VERSION,TenantVersionRange.MAX_VERSION+1,request,run,inventory));
        assertThrows(ProtectionException.class,()->new TenantVersionRange(key,1,0,1,1,1,request,run,inventory));
    }

    @Test void emptyActivationBindsImmutableDecisionAndRejectsWrongGenerationLeaseAndReceipt() {
        var key = ProtectionKey.of("orders", "a"); UUID operation = UUID.randomUUID(), activation = UUID.randomUUID(), owner = UUID.randomUUID();
        String certificate = ProtectionKey.hash("certificate"), manifest = ProtectionKey.hash("manifest"), inventory = ProtectionKey.hash("inventory");
        var id = new EmptyTenantActivation.Identity(key, operation, activation, 7, 8, certificate, manifest, inventory, ProtectionKey.hash("operator"));
        var prepared = new EmptyTenantActivation.Checkpoint(id, EmptyTenantActivation.Phase.PREPARED, 9, owner, 0, null);
        assertThrows(ProtectionException.class, prepared::receiptToken);
        var range = new TenantVersionRange(key, 8, 9, 4, 101, 200, activation, "a".repeat(40), inventory);
        var open = new EmptyTenantActivation.Checkpoint(id, EmptyTenantActivation.Phase.SQL_OPEN, 9, owner, 1, range);
        var receipt = new EmptyTenantActivation.Receipt(id.token(), open.receiptToken(), range.runId(), 9, owner);
        receipt.require(open);
        assertThrows(ProtectionException.class, () -> receipt.require(new EmptyTenantActivation.Checkpoint(id,
                EmptyTenantActivation.Phase.SQL_OPEN, 10, UUID.randomUUID(), 0, range)));
        assertThrows(ProtectionException.class, () -> open.requireLease(new Lease(key, operation, UUID.randomUUID(), 10, 15)));
        assertThrows(ProtectionException.class, () -> new EmptyTenantActivation.Identity(key, operation, activation, 7, 9,
                certificate, manifest, inventory, ProtectionKey.hash("operator")));
        assertNotEquals(id.token(), new EmptyTenantActivation.Identity(key, operation, UUID.randomUUID(), 7, 8,
                certificate, manifest, inventory, ProtectionKey.hash("operator")).token());
        var retainedSource = new PurgeOptions(operation, "a", ProtectionKey.hash("operator"), false, 128, 15, 5000,
                PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA);
        assertEquals(ProtectionException.Code.MIGRATION_REQUIRED, assertThrows(ProtectionException.class,
                () -> new EmptyTenantActivation.Request(activation, EmptyTenantActivation.Mode.EMPTY, retainedSource, certificate, manifest)).code());
    }

    @Test void everyInputHasAHardLimitAndMetadataCannotTargetControlTablesOrInjectSql() {
        var ids = Collections.nCopies(129, "id");
        assertThrows(ProtectionException.class, () -> new Page(UUID.randomUUID(), "-", true, ids));
        assertThrows(ProtectionException.class, () -> new Page(UUID.randomUUID(), "-", false, List.of()));
        assertThrows(ProtectionException.class, () -> new Page(UUID.randomUUID(), "-", true, List.of("same", "same")));
        assertThrows(ProtectionException.class, () -> new SourcePlan("orders;DELETE", "tenant", "id"));
        assertThrows(ProtectionException.class, () -> new SourcePlan("cachedb_tenant_guard", "tenant", "id"));
        assertThrows(ProtectionException.class, () -> new SourcePlan("cachedb_empty_activation", "tenant_token", "activation_id"));
        assertThrows(ProtectionException.class, () -> new SourcePlan("control.CACHEDB_EMPTY_ACTIVATION", "tenant_token", "activation_id"));
        assertThrows(ProtectionException.class, () -> new Generation(ProtectionKey.of("orders", "a"), 0));
        assertThrows(ProtectionException.class, () -> new PurgeOptions(UUID.randomUUID(), "a",
                ProtectionKey.hash("operator"), false, 10_001, 15, 5000, PurgeScope.ACTIVE_STORES_RETAINING_SECURITY_METADATA));
    }
}

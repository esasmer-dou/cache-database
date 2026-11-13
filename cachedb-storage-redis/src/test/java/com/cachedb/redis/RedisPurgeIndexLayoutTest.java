package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.NearCachePolicy;
import com.reactor.cachedb.core.config.QueryIndexConfig;
import com.reactor.cachedb.core.crypto.EncryptedColumnDescriptor;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.protection.ProtectionException;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Pure contracts. Tests remain drafts and are executed only by the controlled parent build. */
class RedisPurgeIndexLayoutTest {
    static final class Metadata implements EntityMetadata<Object, Object> {
        String namespace = "original";
        final Map<String, String> types = new HashMap<>(Map.of("id", "java.lang.String", "label", "java.lang.String"));
        final Map<String, EncryptedColumnDescriptor> encrypted = new HashMap<>();
        public String entityName() { return "Row"; }
        public String tableName() { return "rows"; }
        public String redisNamespace() { return namespace; }
        public String idColumn() { return "id"; }
        public Class<Object> entityType() { return Object.class; }
        public Function<Object, Object> idAccessor() { return Function.identity(); }
        public List<String> columns() { return List.of("id", "label"); }
        public Map<String, String> columnTypes() { return types; }
        public Map<String, EncryptedColumnDescriptor> encryptedColumns() { return encrypted; }
        public List<RelationDefinition> relations() { return List.of(); }
    }
    static RedisPurgeIndexLayout layout(Metadata metadata) {
        return new RedisPurgeIndexLayout(metadata, new RedisKeyStrategy("test"), QueryIndexConfig.defaults(),
                Map.of(), List.of("lock", "writers"));
    }
    @Test void frozenPhysicalSchemaCannotFollowMutableMetadataIntoAnotherNamespace() {
        var metadata = new Metadata(); var layout = layout(metadata);
        String fingerprint = layout.fingerprint;
        metadata.namespace = "foreign"; metadata.types.clear();
        assertEquals("original", layout.metadata.redisNamespace());
        assertEquals("java.lang.String", layout.metadata.columnTypes().get("label"));
        assertEquals(fingerprint, layout.fingerprint);
        for (var action : layout.removals("one", "a", Map.of("id", "one", "label", "value"), false))
            assertFalse(action.key().contains(":foreign:"));
        assertThrows(UnsupportedOperationException.class, () -> layout.metadata.columnTypes().clear());
    }
    @Test void redactedDescriptorToStringIsNeverUsedAsTheSchemaFingerprint() {
        var a = new Metadata();
        a.encrypted.put("label", new EncryptedColumnDescriptor("ring_a", EncryptedColumnDescriptor.Scope.REDIS_ONLY, true));
        var first = layout(a);
        String display = a.encrypted.get("label").toString();
        a.encrypted.put("label", new EncryptedColumnDescriptor("ring_b", EncryptedColumnDescriptor.Scope.REDIS_ONLY, true));
        assertEquals(display, a.encrypted.get("label").toString());
        assertNotEquals(first.fingerprint, layout(a).fingerprint);
        a.encrypted.put("label", new EncryptedColumnDescriptor("ring_a", EncryptedColumnDescriptor.Scope.REDIS_AND_SOURCE, true));
        assertNotEquals(first.fingerprint, layout(a).fingerprint);
        assertEquals("ring_a", first.metadata.encryptedColumns().get("label").keyRing());
    }
    @Test void partialOrdinaryMetadataDoesNotAuthorizeDestroyingOwnershipEvidence() {
        var layout = layout(new Metadata());
        var error = assertThrows(ProtectionException.class,
                () -> layout.removals("one", "a", Map.of("id", "one"), false));
        assertEquals(ProtectionException.Code.INCOMPLETE_INVENTORY, error.code());
    }
    @Test void strictExistingHotOwnerEncodingRejectsLegacyForeignAndOverflowWithoutValuesInErrors() {
        String set = "owned-set", bytes = "owned-budget";
        assertEquals(12, RedisPageCacheManager.purgeTenantPayloadBytes(set + "\n" + bytes + "\n12", set, bytes));
        assertEquals(0, RedisPageCacheManager.purgeTenantPayloadBytes(null, set, bytes));
        for (String raw : List.of("legacy-private-owner", "foreign\n" + bytes + "\n12",
                set + "\n" + bytes + "\n-1", set + "\n" + bytes + "\n9007199254740992",
                set + "\n" + bytes + "\n01", set + "\n" + bytes + "\n12\nextra")) {
            var error = assertThrows(ProtectionException.class,
                    () -> RedisPageCacheManager.purgeTenantPayloadBytes(raw, set, bytes));
            assertFalse(error.toString().contains(raw)); assertNull(error.getCause());
        }
    }
}

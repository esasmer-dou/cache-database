package com.reactor.cachedb.processor;

import static com.reactor.cachedb.processor.EncryptionCompilerFixture.compile;
import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.retention.RetentionPolicy;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheProtectionMetadataProcessorTest {
    @TempDir Path temp;

    private EncryptionCompilerFixture.Compilation entity(String configuration, String annotation,
            String tenant, String timestamp) throws Exception {
        String source = """
                package protection;
                import com.reactor.cachedb.annotations.*;
                @CacheEntity(table="rows", redisNamespace="rows", %s)
                %s
                public record Row(@CacheId String id, %s,
                        @CacheColumn("created_at") %s createdAt) {}
                """.formatted(configuration, annotation, tenant, timestamp);
        return compile(temp, Map.of("protection/Row.java", source));
    }

    private static final String TENANT = "@CacheTenantId @CacheColumn(\"tenant_id\") String tenant";

    private EntityMetadata<?, ?> metadata(EncryptionCompilerFixture.Compilation result,
            java.util.function.Consumer<EntityMetadata<?, ?>> verify) throws Exception {
        assertTrue(result.success(), result.diagnostics());
        try (var loader = new URLClassLoader(new java.net.URL[]{result.classes().toUri().toURL()}, getClass().getClassLoader())) {
            var metadata = (EntityMetadata<?, ?>) loader.loadClass("protection.RowCacheBinding")
                    .getField("METADATA").get(null);
            verify.accept(metadata);
            return metadata;
        }
    }

    @Test void legacyBindingsRetainDisabledProtectionAndEmptyRetention() throws Exception {
        var result = entity("indexedColumns={\"tenant_id\"}", "", TENANT, "java.time.Instant");
        metadata(result, value -> {
            assertFalse(value.tenantPurgeEnabled());
            assertTrue(value.retentionPolicy().isEmpty());
            assertEquals("tenant_id", value.tenantFieldName());
        });
        String binding = result.source("protection/RowCacheBinding.java");
        assertFalse(binding.contains("RETENTION_POLICY"));
        assertFalse(binding.contains("boolean tenantPurgeEnabled()"));
    }

    @Test void explicitPurgeProducesRealIndexedTenantMetadata() throws Exception {
        metadata(entity("tenantPurgeEnabled=true, indexedColumns={\"tenant_id\"}", "", TENANT,
                "java.time.Instant"), value -> {
            assertTrue(value.tenantPurgeEnabled());
            assertEquals("tenant_id", value.tenantFieldName());
            assertTrue(value.indexedColumns().contains("tenant_id"));
        });
    }

    @Test void missingOrUnindexedTenantFailsBeforeGeneratingAnUnsafeBinding() throws Exception {
        for (String tenant : new String[]{"@CacheColumn(\"tenant_id\") String tenant", TENANT}) {
            var result = entity("tenantPurgeEnabled=true, indexedColumns={}", "", tenant, "java.time.Instant");
            assertFalse(result.success());
            assertTrue(result.diagnostics().contains("tenantPurgeEnabled requires"), result.diagnostics());
        }
    }

    @Test void multipleAndNonStringTenantFieldsCannotOptIn() throws Exception {
        for (String tenant : new String[]{
                "@CacheTenantId @CacheColumn(\"tenant_id\") Long tenant",
                TENANT + ", @CacheTenantId @CacheColumn(\"alternate\") String alternate"}) {
            var result = entity("tenantPurgeEnabled=true, indexedColumns={\"tenant_id\"}", "", tenant, "java.time.Instant");
            assertFalse(result.success());
            assertTrue(result.diagnostics().contains("tenantPurgeEnabled requires"), result.diagnostics());
        }
    }

    @Test void everySupportedTemporalTypeProducesTheMatchingScoreUnit() throws Exception {
        for (String type : new String[]{"java.time.Instant", "java.time.LocalDateTime", "java.time.OffsetDateTime", "java.time.LocalDate"}) {
            for (String alias : new String[]{"createdAt", "created_at"}) {
                var result = entity("indexedColumns={\"created_at\"}",
                        "@CacheRetention(field=\"" + alias + "\", days=90)", TENANT, type);
                metadata(result, value -> {
                    var policy = value.retentionPolicy().orElseThrow();
                    assertEquals("created_at", policy.field());
                    assertEquals(90, policy.days());
                    assertEquals(RetentionPolicy.Action.EVICT_CACHE, policy.action());
                    assertEquals(type.equals("java.time.LocalDate") ? RetentionPolicy.ScoreUnit.EPOCH_DAY
                            : RetentionPolicy.ScoreUnit.EPOCH_MILLIS, policy.scoreUnit());
                    policy.validate(value);
                    assertSame(value.retentionPolicy(), value.retentionPolicy(), "Do not allocate policy/Optional per access");
                });
            }
        }
    }

    @Test void deleteRetentionAndPurgeCanBeDeclaredTogether() throws Exception {
        metadata(entity("tenantPurgeEnabled=true, indexedColumns={\"tenant_id\",\"created_at\"}",
                "@CacheRetention(field=\"createdAt\", days=36500, action=RetentionAction.DELETE)", TENANT,
                "java.time.Instant"), value -> {
            assertTrue(value.tenantPurgeEnabled());
            assertEquals(RetentionPolicy.Action.DELETE, value.retentionPolicy().orElseThrow().action());
            assertEquals(36500, value.retentionPolicy().orElseThrow().days());
        });
    }

    @Test void invalidRetentionDaysFailCompilation() throws Exception {
        for (int days : new int[]{0, -1, 36501}) {
            var result = entity("indexedColumns={\"created_at\"}",
                    "@CacheRetention(field=\"createdAt\", days=" + days + ")", TENANT, "java.time.Instant");
            assertFalse(result.success());
            assertTrue(result.diagnostics().contains("@CacheRetention.days"), result.diagnostics());
        }
    }

    @Test void missingAndUnindexedRetentionFieldsFailCompilation() throws Exception {
        for (String field : new String[]{"missing", "createdAt"}) {
            var result = entity("indexedColumns={\"tenant_id\"}",
                    "@CacheRetention(field=\"" + field + "\", days=1)", TENANT, "java.time.Instant");
            assertFalse(result.success());
            assertTrue(result.diagnostics().contains("persisted indexed temporal field"), result.diagnostics());
        }
    }

    @Test void unsupportedTemporalAndScalarTypesCannotBeUsedAsRetentionScores() throws Exception {
        for (String type : new String[]{"String", "long", "java.time.LocalTime", "java.time.ZonedDateTime"}) {
            var result = entity("indexedColumns={\"created_at\"}",
                    "@CacheRetention(field=\"createdAt\", days=1)", TENANT, type);
            assertFalse(result.success());
            String expected = type.equals("java.time.ZonedDateTime")
                    ? "Unsupported persisted record component type: java.time.ZonedDateTime"
                    : "persisted indexed temporal field";
            assertTrue(result.diagnostics().contains(expected), result.diagnostics());
        }
    }

    @Test void retentionWithoutAnEntityCannotBeSilentlyIgnored() throws Exception {
        var result = compile(temp, Map.of("protection/Row.java", """
                package protection;
                @com.reactor.cachedb.annotations.CacheRetention(field="createdAt", days=1)
                public class Row {}
                """));
        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("@CacheRetention requires @CacheEntity"), result.diagnostics());
    }
}

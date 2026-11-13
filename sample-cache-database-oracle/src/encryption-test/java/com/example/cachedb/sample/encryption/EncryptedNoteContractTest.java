package com.example.cachedb.sample.encryption;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.cachedb.sample.controls.ControlsAccess;
import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;

class EncryptedNoteContractTest {
    static final String AES = Base64.getEncoder().encodeToString(new byte[32]);
    static final String HMAC = hmac();
    private static String hmac() { byte[] bytes = new byte[32]; Arrays.fill(bytes, (byte) 7); return Base64.getEncoder().encodeToString(bytes); }
    private ControlsAccess access() { return new ControlsAccess("supplier-test", "tenant-a-test", "tenant-b-test"); }

    @Test void generatedCodecRequiresRealKeyBindingAndRedisOnlyKeepsSqlLogical() {
        var row = new EncryptedNote("note-1", "Türkçe alan: ğışöüİ");
        assertThrows(CacheEncryptionException.class, () -> EncryptedNoteCacheBinding.CODEC.toRedisValue(row));
        var keys = new EncryptionConfiguration().demoNoteKeys(new MockEnvironment()
                .withProperty("SAMPLE_ENCRYPTION_AES_KEY", AES).withProperty("SAMPLE_ENCRYPTION_HMAC_KEY", HMAC));
        var codec = EncryptedNoteCacheBinding.CODEC.bindEncryption(keys);
        assertTrue(codec.encryptionBound());
        String first = codec.toRedisValue(row), second = codec.toRedisValue(row);
        assertNotEquals(first, second, "GCM nonces must not be reused");
        assertTrue(first.contains("~E1:demo-v1:")); assertFalse(first.contains(row.note()));
        assertEquals(row, codec.fromRedisValue(first));
        assertEquals(row.note(), codec.toSourceColumns(row).get("note_value"));
        assertEquals("EncryptedNote[redacted]", row.toString());
    }
    @Test void missingOrAliasedKeysCannotStartTheDemoProvider() {
        assertThrows(IllegalStateException.class, () -> new EncryptionConfiguration().demoNoteKeys(new MockEnvironment()));
        assertThrows(CacheEncryptionException.class, () -> new EncryptionConfiguration().demoNoteKeys(new MockEnvironment()
                .withProperty("SAMPLE_ENCRYPTION_AES_KEY", AES).withProperty("SAMPLE_ENCRYPTION_HMAC_KEY", AES)));
    }
    @Test void noteIdsAndUtf8BytesAreBoundedIndependently() {
        assertThrows(IllegalArgumentException.class, () -> EncryptedNoteController.id(9));
        assertThrows(IllegalArgumentException.class, () -> new EncryptedNote("note-1", ""));
        assertThrows(IllegalArgumentException.class, () -> new EncryptedNote("note-1", "text" + (char) 0));
        assertThrows(IllegalArgumentException.class, () -> new EncryptedNote("note-1", "ğ".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> new EncryptedNote("note-1", "\ud800"));
        assertEquals(512, new EncryptedNote("note-8", "ğ".repeat(256)).note().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }
    @Test void supplierOnlyRoutesRejectTenantAndNonemptyCausalContexts() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> EncryptedNoteController.verify(access(), "Bearer tenant-a-test", ""));
        try (var tenant = CacheTenantContext.withTenant("shop-a")) {
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> EncryptedNoteController.verify(access(), "Bearer supplier-test", ""));
        }
        try (var causal = CacheCausalContext.withMinVersion("sample-encrypted-notes", "note-1", 1)) {
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> EncryptedNoteController.verify(access(), "Bearer supplier-test", ""));
        }
        try (var empty = CacheCausalContext.empty()) {
            assertDoesNotThrow(() -> EncryptedNoteController.verify(access(), "Bearer supplier-test", ""));
        }
    }
    @Test void filterRejectsUnauthorizedAndChunkedOversizeBodiesBeforeCallingTheController() throws Exception {
        var filter = new EncryptedNoteRequestFilter(access()); var chain = mock(jakarta.servlet.FilterChain.class);
        var denied = new MockHttpServletRequest("PUT", "/api/examples/v019/encryption/notes/1");
        denied.setContent(new byte[2049]); var rejected = new MockHttpServletResponse();
        filter.doFilter(denied, rejected, chain); assertEquals(401, rejected.getStatus()); verifyNoInteractions(chain);
        var request = new MockHttpServletRequest("PUT", "/api/examples/v019/encryption/notes/1");
        request.addHeader("Authorization", "Bearer supplier-test"); request.setContent(new byte[2049]);
        var chunked = new jakarta.servlet.http.HttpServletRequestWrapper(request) {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
        };
        var response = new MockHttpServletResponse(); filter.doFilter(chunked, response, chain);
        assertEquals(413, response.getStatus()); verifyNoInteractions(chain);
    }
}

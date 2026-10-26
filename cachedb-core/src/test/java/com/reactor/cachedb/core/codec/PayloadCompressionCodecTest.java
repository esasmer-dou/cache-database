package com.reactor.cachedb.core.codec;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.exception.CacheCodecException;
import org.junit.jupiter.api.Test;

class PayloadCompressionCodecTest {
    @Test void thresholdUsesUtf8BytesAndDisabledModePreservesRawPayload() {
        assertEquals("short", PayloadCompressionCodec.compressIfBeneficial("short", 4096));
        String raw = "Türkçe İı Şş Ğğ Öö Üü Çç ".repeat(1000);
        assertEquals(raw, PayloadCompressionCodec.compressIfBeneficial(raw, 0));
        String compressed = PayloadCompressionCodec.compressIfBeneficial(raw, 4096);
        assertTrue(compressed.startsWith("~Z:"));
        assertTrue(compressed.length() < raw.length());
        assertEquals(raw, PayloadCompressionCodec.decompressIfNeeded(compressed, 100_000));
    }
    @Test void rejectsBombTruncationInvalidBase64AndTrailingData() {
        String compressed = PayloadCompressionCodec.compressIfBeneficial("x".repeat(100_000), 100);
        assertThrows(CacheCodecException.class, () -> PayloadCompressionCodec.decompressIfNeeded(compressed, 1024));
        assertThrows(CacheCodecException.class, () -> PayloadCompressionCodec.decompressIfNeeded("~Z:not-valid!", 1024));
        assertThrows(CacheCodecException.class, () -> PayloadCompressionCodec.decompressIfNeeded(compressed.substring(0, compressed.length() / 2), 100_000));
    }
}

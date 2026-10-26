package com.reactor.cachedb.core.trace;

import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import java.util.Map;

public final class TraceMetadataCodec {
    private TraceMetadataCodec() {}
    public static String encode(Map<String, String> metadata) {
        var values = TraceContexts.boundedCopy(metadata);
        return values.isEmpty() ? "" : LengthPrefixedPayloadCodec.encode(values);
    }
    public static Map<String, String> decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return Map.of();
        if (encoded.length() > 8 * 1040) throw new IllegalArgumentException("Oversized trace metadata");
        return TraceContexts.boundedCopy(LengthPrefixedPayloadCodec.decode(encoded));
    }
}

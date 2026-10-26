package com.reactor.cachedb.core.codec;

import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.exception.CacheCodecException;
import java.util.Map;
import java.util.Objects;

/** Shared framing for repository, page, projection-source and write-envelope hydration. */
public final class PayloadEntityCodec<T> implements EntityCodec<T> {
    private final EntityCodec<T> delegate;
    private final EntityMetadata<T, ?> metadata;
    private final int version;
    private final int threshold;
    private final CachePayloadUpcaster upcaster;

    public PayloadEntityCodec(EntityCodec<T> delegate, EntityMetadata<T, ?> metadata, int threshold) {
        this.delegate = Objects.requireNonNull(delegate);
        this.metadata = Objects.requireNonNull(metadata);
        this.version = metadata.schemaVersion();
        this.upcaster = Objects.requireNonNull(metadata.payloadUpcaster());
        if (version < 1 || threshold < 0 || threshold > PayloadCompressionCodec.MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("Invalid payload framing configuration");
        if (version > 1 && upcaster.targetVersion() != version)
            throw new IllegalArgumentException("Schema upcaster target must match entity schema version");
        this.threshold = threshold;
    }

    @SuppressWarnings("unchecked")
    public static <T> EntityCodec<T> wrap(EntityCodec<T> codec, EntityMetadata<T, ?> metadata, int threshold) {
        if (codec instanceof PayloadEntityCodec<?> framed) {
            if (framed.metadata == metadata && framed.threshold == threshold) return codec;
            codec = (EntityCodec<T>) framed.delegate;
        }
        return new PayloadEntityCodec<>(codec, metadata, threshold);
    }

    public String toRedisValue(T entity) {
        String body = delegate.toRedisValue(entity);
        if (body == null) return null; // Preserve legacy caller-side null validation.
        if (version > 1) body = "~v" + version + ':' + body;
        return PayloadCompressionCodec.compressIfBeneficial(body, threshold);
    }

    public T fromRedisValue(String payload) {
        String body = PayloadCompressionCodec.decompressIfNeeded(payload, PayloadCompressionCodec.MAX_PAYLOAD_BYTES);
        int from = 1;
        if (body.startsWith("~v")) {
            int end = body.indexOf(':', 2);
            if (end < 3 || end > 11) throw new CacheCodecException("Invalid schema header");
            try { from = Integer.parseInt(body.substring(2, end)); }
            catch (NumberFormatException malformed) { throw new CacheCodecException("Invalid schema version", malformed); }
            if (from < 1) throw new CacheCodecException("Invalid schema version");
            body = body.substring(end + 1);
        }
        if (from > version) throw new CacheCodecException("Future payload schema is not supported by this reader");
        if (from < version) body = Objects.requireNonNull(upcaster.upcast(body, from, version), "upcast payload");
        if (body.length() > PayloadCompressionCodec.MAX_PAYLOAD_BYTES / 3
                && Utf8Length.of(body) > PayloadCompressionCodec.MAX_PAYLOAD_BYTES)
            throw new CacheCodecException("Upcast payload exceeds budget");
        return delegate.fromRedisValue(body);
    }
    public Map<String, Object> toColumns(T entity) { return delegate.toColumns(entity); }
    public T fromColumns(Map<String, Object> columns) { return delegate.fromColumns(columns); }
    public boolean directColumnAccess() { return delegate.directColumnAccess(); }
    public Object entityColumnValue(T entity, String column) { return delegate.entityColumnValue(entity, column); }
    public Object columnValue(Map<String, Object> columns, String column) { return delegate.columnValue(columns, column); }
    public Map<String, Object> normalizePatchColumns(Map<String, Object> updates) { return delegate.normalizePatchColumns(updates); }
}

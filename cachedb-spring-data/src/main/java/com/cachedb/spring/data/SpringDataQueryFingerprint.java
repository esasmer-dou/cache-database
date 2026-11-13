package com.reactor.cachedb.spring.data;

import com.reactor.cachedb.core.query.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Base64;

/** Structural, typed operand hashing. No entity reflection, serialization proxy or generic toString. */
final class SpringDataQueryFingerprint {
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private final MessageDigest digest;
    private long bytes;

    private SpringDataQueryFingerprint() {
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    static String of(QuerySpec query) {
        SpringDataQueries.bounded(query);
        var fingerprint = new SpringDataQueryFingerprint();
        var pending = new ArrayDeque<QueryNode>();
        pending.add(query.rootGroup());
        int nodes = 0;
        while (!pending.isEmpty()) {
            if (++nodes > 4096) throw new IllegalArgumentException("Fingerprint node budget exceeded");
            QueryNode node = pending.removeLast();
            if (node instanceof QueryGroup group) {
                fingerprint.field("group");
                fingerprint.field(group.operator().name());
                fingerprint.field(Integer.toString(group.nodes().size()));
                if (pending.size() + group.nodes().size() > 4096)
                    throw new IllegalArgumentException("Fingerprint pending-node budget exceeded");
                for (int i = group.nodes().size() - 1; i >= 0; i--) pending.addLast(group.nodes().get(i));
            } else if (node instanceof QueryFilter filter) {
                fingerprint.field("filter");
                fingerprint.field(filter.column());
                fingerprint.field(filter.operator().name());
                fingerprint.field(SpringDataQueries.scalar(filter.value()));
                fingerprint.field(Integer.toString(filter.values().size()));
                for (Object value : filter.values()) fingerprint.field(SpringDataQueries.scalar(value));
            } else throw new IllegalArgumentException("Unsupported query node");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fingerprint.digest.digest());
    }

    static String contract(QuerySpec query, String route, String scope, java.util.List<QuerySort> sorts) {
        var fingerprint = new SpringDataQueryFingerprint();
        fingerprint.field("window-contract-v1");
        fingerprint.field(route);
        fingerprint.field(scope);
        fingerprint.field(Integer.toString(sorts.size()));
        for (var sort : sorts) {
            fingerprint.field(sort.column());
            fingerprint.field(sort.direction().name());
        }
        fingerprint.field(of(query));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fingerprint.digest.digest());
    }

    /** The protected branch must never invoke of(query), which hashes raw logical operands. */
    static String contract(QuerySpec query, String route, String scope, java.util.List<QuerySort> sorts,
            com.reactor.cachedb.core.model.EntityMetadata<?, ?> metadata,
            com.reactor.cachedb.core.model.EntityCodec<?> codec) {
        java.util.Objects.requireNonNull(metadata, "metadata");
        java.util.Objects.requireNonNull(codec, "codec");
        if (!com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(metadata)) {
            if (codec.encryptedMapping()) throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.unbound();
            return contract(query, route, scope, sorts);
        }
        try {
            com.reactor.cachedb.core.crypto.EncryptedStorageChecks.requireBound(metadata, codec);
            com.reactor.cachedb.core.crypto.EncryptedStorageChecks.validateMetadata(metadata);
            com.reactor.cachedb.core.crypto.EncryptedStorageChecks.validateQuery(metadata, query);
            SpringDataQueries.bounded(query);
            if (sorts == null || sorts.size() > 16) throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue();
            var parts = new ProtectedParts();
            parts.add("spring-data-protected-window-v1");
            parts.add(metadata.entityName()); parts.add(metadata.entityType().getName());
            parts.add(metadata.tableName()); parts.add(metadata.redisNamespace());
            parts.add(metadata.idColumn()); parts.add(metadata.versionColumn()); parts.add(metadata.deletedColumn());
            parts.add(metadata.tenantFieldName()); parts.add(Integer.toString(metadata.schemaVersion()));
            parts.add(metadata.activeMarkerValue()); parts.add(metadata.deletedMarkerValue());
            parts.add(Integer.toString(metadata.columns().size()));
            for (String column : metadata.columns()) {
                parts.add(column); parts.add(metadata.columnTypes().get(column));
                var encrypted = metadata.encryptedColumns().get(column);
                parts.add(encrypted == null ? "logical" : "encrypted");
                if (encrypted != null) {
                    parts.add(encrypted.keyRing()); parts.add(encrypted.scope().name()); parts.add(Boolean.toString(encrypted.blindIndex()));
                }
            }
            parts.add(route); parts.add(scope); parts.add(Integer.toString(sorts.size()));
            for (var sort : sorts) {
                com.reactor.cachedb.core.crypto.EncryptedStorageChecks.rejectProtectedRole(metadata, sort.column());
                parts.add(sort.column()); parts.add(sort.direction().name());
            }
            var pending = new ArrayDeque<QueryNode>(); pending.add(query.rootGroup()); int nodes = 0;
            while (!pending.isEmpty()) {
                if (++nodes > 4096) throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue();
                QueryNode node = pending.removeLast();
                if (node instanceof QueryGroup group) {
                    parts.add("group"); parts.add(group.operator().name()); parts.add(Integer.toString(group.nodes().size()));
                    if (pending.size() + group.nodes().size() > 4096)
                        throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue();
                    for (int i = group.nodes().size() - 1; i >= 0; i--) pending.addLast(group.nodes().get(i));
                } else if (node instanceof QueryFilter filter) {
                    parts.add("filter"); parts.add(filter.column()); parts.add(filter.operator().name());
                    parts.add(SpringDataQueries.scalar(filter.value()));
                    if (com.reactor.cachedb.core.crypto.EncryptedStorageChecks.descriptor(metadata, filter.column()) != null) {
                        parts.add("blind-eq");
                        parts.add((String) com.reactor.cachedb.core.crypto.EncryptedStorageChecks.indexLiteral(
                                metadata, codec, filter.column(), filter.value()));
                    }
                    parts.add(Integer.toString(filter.values().size()));
                    for (Object value : filter.values()) parts.add(SpringDataQueries.scalar(value));
                } else throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue();
            }
            return checkedProtectedToken(codec.encryptionIntentFingerprint(parts.values));
        } catch (com.reactor.cachedb.core.crypto.CacheEncryptionException safe) {
            throw new com.reactor.cachedb.core.crypto.CacheEncryptionException(safe.code());
        } catch (RuntimeException unsafe) {
            throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue();
        }
    }

    private static final class ProtectedParts {
        private final java.util.List<String> values = new java.util.ArrayList<>();
        private long bytes;
        void add(String value) {
            if (value != null && value.length() > 4098)
                throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.invalidValue();
            // Conservative admission before any MAC encoding or retained part-list growth.
            bytes += 4L + (value == null ? 0 : 3L * value.length());
            if (values.size() >= com.reactor.cachedb.core.crypto.JcaFieldEncryption.MAX_INTENT_PARTS
                    || bytes > com.reactor.cachedb.core.crypto.JcaFieldEncryption.MAX_INTENT_BYTES - 1024)
                throw new com.reactor.cachedb.core.crypto.CacheEncryptionException(
                        com.reactor.cachedb.core.crypto.CacheEncryptionException.Code.FIELD_LIMIT);
            values.add(value);
        }
    }

    private static String checkedProtectedToken(String token) {
        if (token == null || token.length() != 47 || !token.startsWith("~I1:"))
            throw new com.reactor.cachedb.core.crypto.CacheEncryptionException(
                    com.reactor.cachedb.core.crypto.CacheEncryptionException.Code.INTENT_KEY_UNAVAILABLE);
        for (int i = 4; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_'))
                throw new com.reactor.cachedb.core.crypto.CacheEncryptionException(
                        com.reactor.cachedb.core.crypto.CacheEncryptionException.Code.INTENT_KEY_UNAVAILABLE);
        }
        if ("AEIMQUYcgkosw048".indexOf(token.charAt(46)) < 0)
            throw new com.reactor.cachedb.core.crypto.CacheEncryptionException(
                    com.reactor.cachedb.core.crypto.CacheEncryptionException.Code.INTENT_KEY_UNAVAILABLE);
        return token;
    }

    private void field(String value) {
        if (value == null || value.length() > 4098) throw new IllegalArgumentException("Invalid fingerprint field");
        // Charge before encoding: UTF-8 uses at most three bytes per Java UTF-16 code unit.
        bytes = Math.addExact(bytes, 4L + 3L * value.length());
        if (bytes > MAX_BYTES) throw new IllegalArgumentException("Query fingerprint exceeds 16 MiB");
        // Hash exact UTF-16 code units, including unpaired surrogates; replacement encoding would alias '?'.
        byte[] encoded = new byte[2 * value.length()];
        for (int i = 0; i < value.length(); i++) {
            char unit = value.charAt(i);
            encoded[2 * i] = (byte) (unit >>> 8);
            encoded[2 * i + 1] = (byte) unit;
        }
        int length = encoded.length;
        digest.update((byte) (length >>> 24));
        digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8));
        digest.update((byte) length);
        digest.update(encoded);
    }
}

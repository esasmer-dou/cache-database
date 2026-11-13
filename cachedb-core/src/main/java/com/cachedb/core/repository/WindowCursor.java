package com.reactor.cachedb.core.repository;

import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Opaque, deterministic cursor carrying typed stable-sort values. */
public final class WindowCursor {
    private static final int MAX_TOKEN_LENGTH = 8_192;
    private static final int MAX_SORT_VALUES = 16;
    private static final String VERSION_KEY = "cachedb_cursor_version";
    private static final String CONTRACT_KEY = "cachedb_cursor_contract";
    private static final String CONTRACT_VERSION = "2";
    private static final String REDIS_LEXICAL_VERSION = "3-redis-score-lex-id";
    private static final String REDIS_LONG_LEXICAL_VERSION = "4-redis-long-lex-id";
    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(WindowCursor::sha256);

    private WindowCursor() {
    }

    public static String encode(Map<String, ?> values) {
        requireSortValues(values);
        LinkedHashMap<String, String> encoded = new LinkedHashMap<>(values.size());
        values.forEach((key, value) -> encoded.put(requireKey(key), encodeValue(value)));
        return encodePayload(encoded);
    }

    public static String encode(Map<String, ?> values, String contract) {
        requireSortValues(values);
        String contractFingerprint = fingerprint(contract);
        LinkedHashMap<String, String> encoded = new LinkedHashMap<>(values.size() + 2);
        encoded.put(VERSION_KEY, CONTRACT_VERSION);
        encoded.put(CONTRACT_KEY, contractFingerprint);
        values.forEach((key, value) -> {
            String normalizedKey = requireKey(key);
            if (VERSION_KEY.equals(normalizedKey) || CONTRACT_KEY.equals(normalizedKey)) {
                throw new IllegalArgumentException("Cursor sort field uses a reserved CacheDB name: " + normalizedKey);
            }
            encoded.put(normalizedKey, encodeValue(value));
        });
        return encodePayload(encoded);
    }

    private static String encodePayload(Map<String, String> encoded) {
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(
                LengthPrefixedPayloadCodec.encode(encoded).getBytes(StandardCharsets.UTF_8)
        );
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException("cursor exceeds " + MAX_TOKEN_LENGTH + " characters");
        }
        return token;
    }

    public static Map<String, Object> decode(String token) {
        return decodePayload(token, null, false);
    }

    public static Map<String, Object> decode(String token, String expectedContract) {
        return decodePayload(token, fingerprint(expectedContract), true);
    }

    private static Map<String, Object> decodePayload(String token, String expectedFingerprint, boolean requireContract) {
        return decodePayload(token, expectedFingerprint, requireContract, CONTRACT_VERSION);
    }

    private static Map<String, Object> decodePayload(
            String token, String expectedFingerprint, boolean requireContract, String expectedVersion
    ) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException("cursor is blank or too large");
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            String payload;
            if (REDIS_LEXICAL_VERSION.equals(expectedVersion) || REDIS_LONG_LEXICAL_VERSION.equals(expectedVersion)) {
                try { payload = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
                catch (java.nio.charset.CharacterCodingException invalid) {
                    throw new IllegalArgumentException("Invalid Redis cursor UTF-8", invalid);
                }
            } else payload = new String(bytes, StandardCharsets.UTF_8);
            LinkedHashMap<String, Object> decoded = new LinkedHashMap<>();
            Map<String, String> fields;
            if (REDIS_LONG_LEXICAL_VERSION.equals(expectedVersion)) {
                fields = new LinkedHashMap<>();
                var cursor = LengthPrefixedPayloadCodec.cursor(payload);
                while (cursor.next()) {
                    String key = cursor.name();
                    if (fields.containsKey(key)) throw new IllegalArgumentException("Duplicate Long cursor field");
                    fields.put(key, cursor.value());
                }
            } else fields = LengthPrefixedPayloadCodec.decode(payload);
            String version = fields.get(VERSION_KEY);
            String actualFingerprint = fields.get(CONTRACT_KEY);
            boolean contractPresent = version != null || actualFingerprint != null;
            if (requireContract && (!expectedVersion.equals(version) || actualFingerprint == null)) {
                throw new CursorContractMismatchException(expectedFingerprint, actualFingerprint);
            }
            if (contractPresent && (!expectedVersion.equals(version) || actualFingerprint == null)) {
                throw new IllegalArgumentException("Unsupported CacheDB cursor contract format");
            }
            if (requireContract && !MessageDigest.isEqual(
                    expectedFingerprint.getBytes(StandardCharsets.US_ASCII),
                    actualFingerprint.getBytes(StandardCharsets.US_ASCII)
            )) {
                throw new CursorContractMismatchException(expectedFingerprint, actualFingerprint);
            }
            fields.forEach((key, value) -> {
                if (!VERSION_KEY.equals(key) && !CONTRACT_KEY.equals(key)) {
                    decoded.put(requireKey(key), decodeValue(value));
                }
            });
            if (decoded.isEmpty() || decoded.size() > MAX_SORT_VALUES) {
                throw new IllegalArgumentException("cursor has an invalid number of values");
            }
            return Map.copyOf(decoded);
        } catch (CursorContractMismatchException failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid CacheDB cursor", failure);
        }
    }

    /** Distinct wire contract; legacy typed cursors must never be reinterpreted as Redis order. */
    public static String encodeRedisLexical(double score, String id, String contract) {
        RedisLexicalPosition position = new RedisLexicalPosition(score, id);
        var fields = new LinkedHashMap<String, String>();
        fields.put(VERSION_KEY, REDIS_LEXICAL_VERSION);
        fields.put(CONTRACT_KEY, fingerprint(contract));
        fields.put("redis_score", encodeValue(position.score()));
        fields.put("redis_id", encodeValue(position.id()));
        return encodePayload(fields);
    }

    public static RedisLexicalPosition decodeRedisLexical(String token, String contract) {
        Map<String, Object> values = decodePayload(token, fingerprint(contract), true, REDIS_LEXICAL_VERSION);
        if (values.size() != 2 || !(values.get("redis_score") instanceof Double score)
                || !(values.get("redis_id") instanceof String id))
            throw new IllegalArgumentException("Redis lexical cursor requires a Double score and String ID");
        return new RedisLexicalPosition(score, id);
    }

    /** Exact Long position; never encode a rounded Redis double as a Long continuation. */
    public static String encodeRedisLongLexical(long sortValue, String id, String contract) {
        RedisLongPosition position = new RedisLongPosition(sortValue, id);
        var fields = new LinkedHashMap<String, String>();
        fields.put(VERSION_KEY, REDIS_LONG_LEXICAL_VERSION);
        fields.put(CONTRACT_KEY, fingerprint(contract));
        fields.put("redis_long", encodeValue(position.sortValue()));
        fields.put("redis_id", encodeValue(position.id()));
        return encodePayload(fields);
    }

    public static RedisLongPosition decodeRedisLongLexical(String token, String contract) {
        Map<String, Object> values = decodePayload(token, fingerprint(contract), true, REDIS_LONG_LEXICAL_VERSION);
        if (values.size() != 2 || !(values.get("redis_long") instanceof Long value)
                || !(values.get("redis_id") instanceof String id))
            throw new IllegalArgumentException("Redis Long cursor requires an exact Long value and String ID");
        return new RedisLongPosition(value, id);
    }

    public record RedisLongPosition(long sortValue, String id) {
        public RedisLongPosition { requireRedisId(id); }
    }

    private static void requireRedisId(String id) {
        if (id == null || id.isEmpty() || id.length() > 4096)
            throw new IllegalArgumentException("Invalid Redis lexical ID");
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == id.length() || !Character.isLowSurrogate(id.charAt(i)))
                    throw new IllegalArgumentException("Invalid cursor Unicode");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid cursor Unicode");
        }
        if (id.getBytes(StandardCharsets.UTF_8).length > 4096)
            throw new IllegalArgumentException("Cursor ID exceeds 4096 UTF-8 bytes");
    }

    public record RedisLexicalPosition(double score, String id) {
        public RedisLexicalPosition {
            if (!Double.isFinite(score) || id == null || id.isEmpty() || id.length() > 4096)
                throw new IllegalArgumentException("Invalid Redis lexical position");
            for (int i = 0; i < id.length(); i++) {
                char c = id.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i == id.length() || !Character.isLowSurrogate(id.charAt(i)))
                        throw new IllegalArgumentException("Invalid cursor Unicode");
                } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid cursor Unicode");
            }
            if (id.getBytes(StandardCharsets.UTF_8).length > 4096)
                throw new IllegalArgumentException("Cursor ID exceeds 4096 UTF-8 bytes");
            if (score == 0d) score = 0d;
        }
    }

    private static void requireSortValues(Map<String, ?> values) {
        if (values == null || values.isEmpty() || values.size() > MAX_SORT_VALUES) {
            throw new IllegalArgumentException("cursor requires between 1 and " + MAX_SORT_VALUES + " sort values");
        }
    }

    private static String fingerprint(String contract) {
        if (contract == null || contract.isBlank() || contract.length() > 4_096) {
            throw new IllegalArgumentException("cursor contract must contain between 1 and 4096 characters");
        }
        MessageDigest digest = SHA_256.get();
        digest.reset();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                digest.digest(contract.getBytes(StandardCharsets.UTF_8))
        );
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static String encodeValue(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Cursor values must not be null");
        }
        if (value instanceof String item) return "S:" + item;
        if (value instanceof Integer item) return "I:" + item;
        if (value instanceof Long item) return "L:" + item;
        if (value instanceof Short item) return "H:" + item;
        if (value instanceof Byte item) return "Y:" + item;
        if (value instanceof Double item) return "D:" + item;
        if (value instanceof Float item) return "F:" + item;
        if (value instanceof BigDecimal item) return "M:" + item.toPlainString();
        if (value instanceof Boolean item) return "B:" + item;
        if (value instanceof Instant item) return "T:" + item;
        if (value instanceof LocalDate item) return "A:" + item;
        if (value instanceof LocalDateTime item) return "C:" + item;
        if (value instanceof OffsetDateTime item) return "O:" + item;
        if (value instanceof UUID item) return "U:" + item;
        if (value instanceof Enum<?> item) return "E:" + item.name();
        throw new IllegalArgumentException("Unsupported cursor value type: " + value.getClass().getName());
    }

    private static Object decodeValue(String encoded) {
        if (encoded == null || encoded.length() < 2 || encoded.charAt(1) != ':') {
            throw new IllegalArgumentException("Malformed cursor value");
        }
        String value = encoded.substring(2);
        return switch (encoded.charAt(0)) {
            case 'S', 'E' -> value;
            case 'I' -> Integer.valueOf(value);
            case 'L' -> Long.valueOf(value);
            case 'H' -> Short.valueOf(value);
            case 'Y' -> Byte.valueOf(value);
            case 'D' -> Double.valueOf(value);
            case 'F' -> Float.valueOf(value);
            case 'M' -> new BigDecimal(value);
            case 'B' -> parseBoolean(value);
            case 'T' -> Instant.parse(value);
            case 'A' -> LocalDate.parse(value);
            case 'C' -> LocalDateTime.parse(value);
            case 'O' -> OffsetDateTime.parse(value);
            case 'U' -> UUID.fromString(value);
            default -> throw new IllegalArgumentException("Unknown cursor value type");
        };
    }

    private static String requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid cursor field: " + key);
        }
        return key;
    }

    private static Boolean parseBoolean(String value) {
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("Malformed boolean cursor value");
        }
        return Boolean.valueOf(value);
    }
}

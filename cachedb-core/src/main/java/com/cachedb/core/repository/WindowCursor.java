package com.reactor.cachedb.core.repository;

import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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

    private WindowCursor() {
    }

    public static String encode(Map<String, ?> values) {
        if (values == null || values.isEmpty() || values.size() > 16) {
            throw new IllegalArgumentException("cursor requires between 1 and 16 sort values");
        }
        LinkedHashMap<String, String> encoded = new LinkedHashMap<>(values.size());
        values.forEach((key, value) -> encoded.put(requireKey(key), encodeValue(value)));
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(
                LengthPrefixedPayloadCodec.encode(encoded).getBytes(StandardCharsets.UTF_8)
        );
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException("cursor exceeds " + MAX_TOKEN_LENGTH + " characters");
        }
        return token;
    }

    public static Map<String, Object> decode(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException("cursor is blank or too large");
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            LinkedHashMap<String, Object> decoded = new LinkedHashMap<>();
            LengthPrefixedPayloadCodec.decode(payload).forEach((key, value) -> decoded.put(requireKey(key), decodeValue(value)));
            if (decoded.isEmpty() || decoded.size() > 16) {
                throw new IllegalArgumentException("cursor has an invalid number of values");
            }
            return Map.copyOf(decoded);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid CacheDB cursor", failure);
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

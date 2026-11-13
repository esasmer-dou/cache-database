package com.reactor.cachedb.spring.boot.cache;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.cache.interceptor.SimpleKey;

final class CacheDbCacheKeys {
    static final int MAX_INPUT_CHARS = 65536;
    private CacheDbCacheKeys() {}

    static void checkName(String name) {
        if (name == null || name.length() > 128 || name.isBlank()
                || !name.matches("[A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("Cache name must be 1..128 ASCII letters/digits/._-");
    }

    static String prefix(String prefix) {
        if (prefix == null || prefix.length() > 128 || prefix.isBlank()
                || !prefix.matches("[A-Za-z0-9_.:-]+"))
            throw new IllegalArgumentException("Spring Cache prefix must be 1..128 ASCII letters/digits/._:- (no hash tags)");
        return prefix;
    }

    static String encode(Object key) {
        String raw = scalar(key);
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        String encoded = bytes.length <= 768
                ? Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) : null;
        return encoded != null && encoded.length() <= 1024 ? encoded : "h:" + hash(bytes);
    }

    static String scalar(Object key) {
        if (key == null) throw new IllegalArgumentException("Null cache keys are not supported");
        if (key instanceof SimpleKey simple) {
            if (simple.getClass() == SimpleKey.class && simple.equals(SimpleKey.EMPTY)) return "empty:";
            throw new IllegalArgumentException("Opaque nonempty SimpleKey has no public parameter accessor; set @CacheConfig(keyGenerator=\"cacheDbKeyGenerator\") or return that bean from your existing CachingConfigurer.keyGenerator()");
        }
        String tag;
        String value;
        if (key instanceof String text) { tag = "s"; value = text; }
        else if (key instanceof Character character) { tag = "c"; value = character.toString(); }
        else if (key instanceof Boolean bool) { tag = "z"; value = bool.toString(); }
        else if (key instanceof Byte number) { tag = "b"; value = number.toString(); }
        else if (key instanceof Short number) { tag = "s16"; value = number.toString(); }
        else if (key instanceof Integer number) { tag = "i"; value = number.toString(); }
        else if (key instanceof Long number) { tag = "l"; value = number.toString(); }
        else if (key instanceof Float number) {
            if (!Float.isFinite(number)) throw new IllegalArgumentException("Non-finite cache key");
            tag = "f"; value = Float.toHexString(number);
        } else if (key instanceof Double number) {
            if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite cache key");
            tag = "d"; value = Double.toHexString(number);
        } else if (key instanceof UUID uuid) { tag = "u"; value = uuid.toString(); }
        else if (key instanceof BigInteger integer && integer.getClass() == BigInteger.class) {
            if (integer.bitLength() > 4096) throw new IllegalArgumentException("Numeric cache key exceeds 4096 bits");
            tag = "bi"; value = integer.toString();
        } else if (key instanceof BigDecimal decimal && decimal.getClass() == BigDecimal.class) {
            if (decimal.unscaledValue().bitLength() > 4096) throw new IllegalArgumentException("Numeric cache key exceeds 4096 bits");
            tag = "bd"; value = decimal.toString();
        } else throw new IllegalArgumentException("Unsupported cache key; use an explicit KeyGenerator returning String");
        if (value.length() > MAX_INPUT_CHARS) throw new IllegalArgumentException("Cache key input exceeds 65536 characters");
        unicode(value);
        return tag + ":" + value;
    }

    static void unicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IllegalArgumentException("Malformed Unicode cache identity");
            } else if (Character.isLowSurrogate(ch)) throw new IllegalArgumentException("Malformed Unicode cache identity");
        }
    }

    static String hash(String value) {
        return hash(value.getBytes(StandardCharsets.UTF_8));
    }
    private static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
}

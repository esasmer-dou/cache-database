package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.QueryIndexConfig;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Canonical index encodings. Changes here must preserve existing Redis keys. */
final class RedisIndexKeySpace {
    static String serializeValue(Object value) { return value == null ? "__NULL__" : String.valueOf(value); }
    static String decodeValue(String value) { return "__NULL__".equals(value) ? null : value; }
    static String encodeKeyPart(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    static String normalizeText(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    static List<String> tokenize(String value, QueryIndexConfig config) {
        String normalized = normalizeText(value);
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        int start = -1;
        for (int offset = 0; offset <= normalized.length();) {
            int point = offset == normalized.length() ? -1 : normalized.codePointAt(offset);
            boolean word = point >= 0 && (Character.isAlphabetic(point) || Character.isDigit(point));
            if (word && start < 0) start = offset;
            if (!word && start >= 0) {
                if (offset - start >= config.textTokenMinLength()) {
                    tokens.add(normalized.substring(start, Math.min(offset, start + config.textTokenMaxLength())));
                    if (tokens.size() >= config.textMaxTokensPerValue()) break;
                }
                start = -1;
            }
            if (point < 0) break;
            offset += Character.charCount(point);
        }
        return new ArrayList<>(tokens);
    }
    static String toSnakeCase(String value) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isUpperCase(current) && index > 0) builder.append('_');
            builder.append(Character.toLowerCase(current));
        }
        return builder.toString();
    }
    private RedisIndexKeySpace() {}
}

package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.model.EntityMetadata;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Separate metadata markers retain null identity and collision-free composite encodings. */
final class RedisCompositeIndexes {
    static final String MARKER = "$cachedb:cidx:";
    private RedisCompositeIndexes() {}
    static String key(RedisKeyStrategy keys, String namespace, String name, List<String> columns, Map<String, ?> values) {
        StringBuilder tuple = new StringBuilder();
        for (String column : columns) {
            Object value = values.get(column);
            if (value == null) return null;
            String text = value.toString();
            if (text.length() > 4096) throw new IllegalArgumentException("Composite value exceeds 4096 characters");
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new IllegalArgumentException("Invalid composite Unicode");
                } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid composite Unicode");
            }
            String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8);
            if (!tuple.isEmpty()) tuple.append('|');
            tuple.append(encoded.length()).append(':').append(encoded);
            if (tuple.length() > 4096) throw new IllegalArgumentException("Composite key exceeds 4096 characters");
        }
        return keys.indexExactKey(namespace, MARKER + name, tuple.toString());
    }
    static void append(RedisKeyStrategy keys, EntityMetadata<?, ?> metadata, Map<String, ?> columns, Map<String, String> meta) {
        metadata.compositeExactIndexes().forEach((name, fields) -> {
            String key = key(keys, metadata.redisNamespace(), name, fields, columns);
            if (key != null) meta.put(MARKER + name, key);
        });
    }
    static void validate(EntityMetadata<?, ?> metadata) {
        if (metadata.compositeExactIndexes().size() > 32) throw new IllegalArgumentException("At most 32 composite indexes");
        if (metadata.columns().stream().anyMatch(column -> column.startsWith(MARKER))) throw new IllegalArgumentException("Reserved composite metadata prefix");
        metadata.compositeExactIndexes().forEach((name, columns) -> {
            Objects.requireNonNull(columns);
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") || columns.size() < 2 || columns.size() > 4
                    || columns.stream().distinct().count() != columns.size() || !metadata.indexedColumns().containsAll(columns))
                throw new IllegalArgumentException("Invalid composite index declaration");
        });
    }
}

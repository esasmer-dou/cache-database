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
    static final String SORT_MARKER = "$cachedb:cidx-sort:";
    private RedisCompositeIndexes() {}
    static String signature(EntityMetadata<?, ?> metadata) {
        if (metadata.compositeExactIndexes().size() > 32) throw new IllegalArgumentException("At most 32 composite indexes");
        long length = 3;
        for (var index : metadata.compositeExactIndexes().entrySet()) {
            length += index.getKey().length() + 2L;
            for (String column : index.getValue()) length += column.length() + 1L;
            String sort = metadata.compositeSortColumns().get(index.getKey());
            if (sort != null) length += sort.length() + 1L;
            if (length > 65_536) throw new IllegalArgumentException("Composite signature exceeds 64 KiB");
        }
        return "v1|" + metadata.compositeExactIndexes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(index -> index.getKey() + "=" + String.join(",", index.getValue())
                        + (metadata.compositeSortColumns().containsKey(index.getKey())
                                ? ">" + metadata.compositeSortColumns().get(index.getKey()) : ""))
                .collect(java.util.stream.Collectors.joining(";"));
    }
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
            String sort = metadata.compositeSortColumns().get(name);
            if (key != null && sort != null) {
                Double score = RedisScoreSupport.toScore(metadata.columnTypes(), sort, columns.get(sort));
                if (score != null) {
                    if (!Double.isFinite(score)) throw new IllegalArgumentException("Composite score must be finite");
                    meta.put(SORT_MARKER + name, key + ":sort|" + (score == 0d ? 0d : score));
                }
            }
        });
    }
    static void validate(EntityMetadata<?, ?> metadata) {
        if (metadata.compositeExactIndexes().size() > 32) throw new IllegalArgumentException("At most 32 composite indexes");
        if (metadata.columns().stream().anyMatch(column -> column.startsWith(MARKER) || column.startsWith(SORT_MARKER)))
            throw new IllegalArgumentException("Reserved composite metadata prefix");
        metadata.compositeExactIndexes().forEach((name, columns) -> {
            Objects.requireNonNull(columns);
            int minimumPrefix = metadata.compositeSortColumns().containsKey(name) ? 1 : 2;
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") || columns.size() < minimumPrefix || columns.size() > 4
                    || columns.stream().distinct().count() != columns.size() || !metadata.indexedColumns().containsAll(columns))
                throw new IllegalArgumentException("Invalid composite index declaration");
        });
        metadata.compositeSortColumns().forEach((name, sort) -> {
            List<String> prefix = metadata.compositeExactIndexes().get(name);
            if (prefix == null || sort == null || prefix.contains(sort) || !metadata.columns().contains(sort)
                    || !metadata.indexedColumns().contains(sort) || !supportsSortType(metadata.columnTypes().get(sort)))
                throw new IllegalArgumentException("Invalid composite sort declaration: " + name);
        });
    }

    static boolean supportsSortType(String type) {
        return type != null && java.util.Set.of(
                "int", "java.lang.Integer", "long", "java.lang.Long",
                "double", "java.lang.Double", "float", "java.lang.Float",
                "short", "java.lang.Short", "byte", "java.lang.Byte",
                "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime",
                "java.time.OffsetDateTime").contains(type);
    }

    record SortEntry(String key, double score) {}

    static SortEntry sortEntry(String marker) {
        int split = marker.lastIndexOf('|');
        if (split < 1 || marker.length() > 8192 || !marker.substring(0, split).endsWith(":sort"))
            throw new IllegalArgumentException("Malformed composite sort marker");
        double score = Double.parseDouble(marker.substring(split + 1));
        if (!Double.isFinite(score)) throw new IllegalArgumentException("Composite score must be finite");
        return new SortEntry(marker.substring(0, split), score);
    }

    /** Consume sort markers before the unchanged legacy planner sees ordinary columns. */
    static RedisIndexMutationPlanner.Plan applySortDiff(redis.clients.jedis.AbstractPipeline pipeline,
            String id, RedisIndexMutationPlanner.Plan plan, boolean enabled) {
        if (plan.changed().stream().noneMatch(column -> column.startsWith(SORT_MARKER))) return plan;
        validateMember(id);
        validateSortMeta(plan.prior());
        validateSortMeta(plan.next());
        var regular = new java.util.LinkedHashSet<String>();
        for (String column : plan.changed()) {
            if (!column.startsWith(SORT_MARKER)) { regular.add(column); continue; }
            String old = plan.prior().get(column), next = plan.next().get(column);
            SortEntry prior = old == null ? null : sortEntry(old);
            SortEntry current = next == null ? null : sortEntry(next);
            if (prior != null && (current == null || !prior.key().equals(current.key()) || !enabled))
                pipeline.zrem(prior.key(), id);
            if (enabled && current != null) pipeline.zadd(current.key(), current.score(), id);
        }
        return new RedisIndexMutationPlanner.Plan(plan.prior(), plan.next(),
                java.util.Collections.unmodifiableSet(regular));
    }

    static void validateMember(String id) {
        if (id == null || id.isEmpty() || id.length() > 4096)
            throw new IllegalArgumentException("Composite ID must contain 1..4096 UTF-8 bytes");
        int bytes = 0;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == id.length() || !Character.isLowSurrogate(id.charAt(i)))
                    throw new IllegalArgumentException("Invalid composite ID Unicode");
                bytes += 4;
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid composite ID Unicode");
            else bytes += c < 128 ? 1 : c < 2048 ? 2 : 3;
            if (bytes > 4096) throw new IllegalArgumentException("Composite ID exceeds 4096 UTF-8 bytes");
        }
    }

    static void validateSortMeta(Map<String, String> meta) {
        int markers = 0;
        if (meta.size() > com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec.MAX_FIELDS)
            throw new IllegalArgumentException("Too many composite metadata fields");
        for (var entry : meta.entrySet()) if (entry.getKey().startsWith(SORT_MARKER)) {
            if (++markers > 32 || !entry.getKey().substring(SORT_MARKER.length()).matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
                throw new IllegalArgumentException("Invalid composite sort metadata name/count");
            sortEntry(java.util.Objects.requireNonNull(entry.getValue(), "sort marker"));
        }
    }

    static int compareMembers(String left, String right) {
        int a = 0, b = 0;
        while (a < left.length() && b < right.length()) {
            int x = left.codePointAt(a), y = right.codePointAt(b);
            if (x != y) return Integer.compare(x, y);
            a += Character.charCount(x); b += Character.charCount(y);
        }
        return Integer.compare(left.length() - a, right.length() - b);
    }
}

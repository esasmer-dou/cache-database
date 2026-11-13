package com.reactor.cachedb.redis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import redis.clients.jedis.util.SafeEncoder;

/** Decodes compact RESP tuples without copying their nested lists. */
final class RedisCompactFacetReplyDecoder {
    private static final String BUDGET_MARKER = "cachedb-facet-phase-budget-v1";
    private static final byte[] RAW_BUDGET_MARKER = BUDGET_MARKER.getBytes(StandardCharsets.US_ASCII);

    private RedisCompactFacetReplyDecoder() {}

    static boolean isBudgetMarker(Object value) {
        return BUDGET_MARKER.equals(value)
                || value instanceof byte[] bytes && Arrays.equals(RAW_BUDGET_MARKER, bytes);
    }

    static RedisClientOperations.FacetIndexCounts parse(Object response, int size, long maxBytes) {
        if (!(response instanceof List<?> result) || result.size() != 2
                || !(result.get(0) instanceof List<?> pairs) || pairs.size() > Math.min(size, 256)
                || !(result.get(1) instanceof List<?> rawNulls) || rawNulls.size() > size)
            throw new IllegalStateException("Invalid facet count response");
        var counts = new LinkedHashMap<String, Long>();
        long total = 0, bytes = 0;
        for (Object entry : pairs) {
            if (!(entry instanceof List<?> pair) || pair.size() != 2 || !(pair.get(0) instanceof byte[] rawGroup)
                    || !(pair.get(1) instanceof Long count) || count < 1 || count > size)
                throw new IllegalStateException("Invalid facet count group");
            // Match legacy Jedis String decoding and the existing UTF-16 weighted budget.
            String group = SafeEncoder.encode(rawGroup);
            if (group.equals("__NULL__") || group.length() > 4096 || counts.putIfAbsent(group, count) != null)
                throw new IllegalStateException("Invalid facet count group");
            total += count;
            bytes += count * (2L * group.length() + 16);
            if (total + rawNulls.size() > size || bytes > maxBytes)
                throw new IllegalStateException("Facet count budget exceeded");
        }
        var nulls = new ArrayList<Integer>(rawNulls.size());
        int previous = -1;
        for (Object index : rawNulls) {
            if (!(index instanceof Number number) || number.longValue() <= previous || number.longValue() >= size
                    || number.longValue() != number.doubleValue())
                throw new IllegalStateException("Invalid index batch candidate position");
            previous = number.intValue();
            nulls.add(previous);
        }
        return new RedisClientOperations.FacetIndexCounts(counts, nulls);
    }
}

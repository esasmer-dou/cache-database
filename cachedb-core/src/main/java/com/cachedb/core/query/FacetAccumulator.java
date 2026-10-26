package com.reactor.cachedb.core.query;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.LinkedHashMap;

/** Bounded deterministic grouping shared by live storage and the consumer test kit. */
public final class FacetAccumulator {
    public static final int MAX_GROUPS = 256;
    private final Map<String, AggregateResult.Accumulator> groups = new TreeMap<>();
    private int rows;
    public void add(Object key, Object value, boolean countField) {
        if (++rows > 100_000) throw new IllegalStateException("Facet candidate limit exceeded: max 100000");
        if (key == null) return;
        String group = key.toString();
        if (group.length() > 4096) throw new IllegalArgumentException("Facet group key exceeds 4096 characters");
        var accumulator = groups.get(group);
        if (accumulator == null) {
            if (groups.size() == MAX_GROUPS) throw new IllegalStateException("Facet group limit exceeded: max 256");
            groups.put(group, accumulator = new AggregateResult.Accumulator());
        }
        if (!countField || value != null) accumulator.add(countField ? null : value);
    }
    public Map<String, AggregateResult> result() {
        var result = new LinkedHashMap<String, AggregateResult>();
        groups.forEach((key, value) -> result.put(key, value.result()));
        return Collections.unmodifiableMap(result);
    }
}

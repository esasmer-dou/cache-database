package com.reactor.cachedb.core.readmodel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable, encounter-ordered counts with well-formed UTF-16 keys and group weights summing to at most count. */
public record CountFacetValues(long count, Map<String, Long> groups) {
    public CountFacetValues {
        Objects.requireNonNull(groups, "groups");
        if (count < 0) throw new IllegalArgumentException("count must be nonnegative");
        if (groups.size() > 256) throw new IllegalArgumentException("At most 256 groups are allowed");
        var copy = new LinkedHashMap<String, Long>();
        long total = 0;
        for (var entry : groups.entrySet()) {
            if (copy.size() == 256) throw new IllegalArgumentException("At most 256 groups are allowed");
            String key = Objects.requireNonNull(entry.getKey(), "group key");
            long weight = Objects.requireNonNull(entry.getValue(), "group weight");
            validateGroupKey(key);
            if (weight <= 0 || weight > count - total)
                throw new IllegalArgumentException("Group weights must be positive and their sum must not exceed count");
            total += weight;
            copy.put(key, weight);
        }
        groups = Collections.unmodifiableMap(copy);
    }

    private static void validateGroupKey(String key) {
        if (key.length() > 4096) throw new IllegalArgumentException("Group keys must not exceed 4096 UTF-16 units");
        for (int i = 0; i < key.length(); i++) {
            char unit = key.charAt(i);
            if (Character.isHighSurrogate(unit)) {
                if (i + 1 == key.length() || !Character.isLowSurrogate(key.charAt(i + 1)))
                    throw new IllegalArgumentException("Group keys must contain well-formed UTF-16");
                i++;
            } else if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException("Group keys must contain well-formed UTF-16");
            }
        }
    }
}

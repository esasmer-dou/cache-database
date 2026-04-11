package com.reactor.cachedb.core.plan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class FetchPlan {

    private final Set<String> includes;
    private final Map<String, Integer> relationLimits;

    private FetchPlan(Set<String> includes, Map<String, Integer> relationLimits) {
        this.includes = Collections.unmodifiableSet(includes);
        this.relationLimits = Collections.unmodifiableMap(relationLimits);
    }

    public static FetchPlan empty() {
        return new FetchPlan(new LinkedHashSet<>(), new LinkedHashMap<>());
    }

    public static FetchPlan of(String... relations) {
        LinkedHashSet<String> includes = new LinkedHashSet<>();
        if (relations != null) {
            for (String relation : relations) {
                if (relation == null) {
                    continue;
                }
                String normalized = relation.trim();
                if (!normalized.isEmpty()) {
                    includes.add(normalized);
                }
            }
        }
        return new FetchPlan(includes, new LinkedHashMap<>());
    }

    public Set<String> includes() {
        return includes;
    }

    public Map<String, Integer> relationLimits() {
        return relationLimits;
    }

    public boolean includes(String relationName) {
        return includes.contains(relationName)
                || includes.stream().anyMatch(candidate -> candidate.startsWith(relationName + "."));
    }

    public boolean hasRelationLimit(String relationName) {
        return relationLimits.containsKey(relationName);
    }

    public int relationLimit(String relationName) {
        return relationLimits.getOrDefault(relationName, Integer.MAX_VALUE);
    }

    public FetchPlan withRelationLimit(String relationName, int limit) {
        if (relationName == null || relationName.isBlank()) {
            return this;
        }
        String normalized = relationName.trim();
        if (normalized.isEmpty()) {
            return this;
        }
        LinkedHashSet<String> updatedIncludes = new LinkedHashSet<>(includes);
        updatedIncludes.add(normalized);
        LinkedHashMap<String, Integer> updatedLimits = new LinkedHashMap<>(relationLimits);
        updatedLimits.put(normalized, Math.max(1, limit));
        return new FetchPlan(updatedIncludes, updatedLimits);
    }

    public FetchPlan nestedUnder(String relationName) {
        String prefix = relationName + ".";
        LinkedHashSet<String> nested = includes.stream()
                .filter(candidate -> candidate.startsWith(prefix))
                .map(candidate -> candidate.substring(prefix.length()))
                .filter(candidate -> !candidate.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        LinkedHashMap<String, Integer> nestedLimits = relationLimits.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .collect(Collectors.toMap(
                        entry -> entry.getKey().substring(prefix.length()),
                        Map.Entry::getValue,
                        (left, right) -> right,
                        LinkedHashMap::new
                ));
        return nested.isEmpty() && nestedLimits.isEmpty() ? empty() : new FetchPlan(nested, nestedLimits);
    }

    public int maxDepth() {
        return includes.stream()
                .filter(Objects::nonNull)
                .mapToInt(FetchPlan::relationDepth)
                .max()
                .orElse(0);
    }

    public boolean exceedsDepth(int maxDepth) {
        return maxDepth > 0 && maxDepth() > maxDepth;
    }

    public static int relationDepth(String relationPath) {
        if (relationPath == null || relationPath.isBlank()) {
            return 0;
        }
        return (int) java.util.Arrays.stream(relationPath.split("\\."))
                .filter(segment -> !segment.isBlank())
                .count();
    }
}

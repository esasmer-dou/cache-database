package com.reactor.cachedb.core.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Explicit, bounded SQL read that never admits rows to Redis. */
public record SourceSqlQuery(
        String sql,
        List<?> parameters,
        int maxRows,
        int queryTimeoutSeconds
) {
    public SourceSqlQuery {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sql must not be blank");
        }
        sql = SourceSqlValidator.requireReadOnly(sql);
        parameters = parameters == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(parameters));
        if (maxRows <= 0 || maxRows > 10_000) {
            throw new IllegalArgumentException("maxRows must be between 1 and 10000");
        }
        if (queryTimeoutSeconds <= 0 || queryTimeoutSeconds > 300) {
            throw new IllegalArgumentException("queryTimeoutSeconds must be between 1 and 300");
        }
        int placeholders = SourceSqlValidator.placeholderCount(sql);
        if (placeholders != parameters.size()) {
            throw new IllegalArgumentException("Source SQL placeholder count=" + placeholders
                    + " but parameters=" + parameters.size());
        }
    }

    public static SourceSqlQuery of(String sql, List<?> parameters, int maxRows) {
        return new SourceSqlQuery(sql, parameters, maxRows, 30);
    }
}

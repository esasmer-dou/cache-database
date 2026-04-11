package com.reactor.cachedb.core.projection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class EntityProjection<T, P, ID> {

    private final String name;
    private final ProjectionCodec<P> codec;
    private final Function<P, ID> idAccessor;
    private final List<String> columns;
    private final List<String> rankedColumns;
    private final Function<P, Map<String, Object>> columnExtractor;
    private final Function<T, P> projector;
    private final ProjectionRefreshMode refreshMode;

    private EntityProjection(
            String name,
            ProjectionCodec<P> codec,
            Function<P, ID> idAccessor,
            List<String> columns,
            List<String> rankedColumns,
            Function<P, Map<String, Object>> columnExtractor,
            Function<T, P> projector,
            ProjectionRefreshMode refreshMode
    ) {
        this.name = Objects.requireNonNull(name, "name");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.idAccessor = Objects.requireNonNull(idAccessor, "idAccessor");
        this.columns = List.copyOf(columns);
        this.rankedColumns = List.copyOf(rankedColumns);
        this.columnExtractor = Objects.requireNonNull(columnExtractor, "columnExtractor");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.refreshMode = Objects.requireNonNull(refreshMode, "refreshMode");
    }

    public static <T, P, ID> EntityProjection<T, P, ID> of(
            String name,
            ProjectionCodec<P> codec,
            Function<P, ID> idAccessor,
            List<String> columns,
            Function<P, Map<String, Object>> columnExtractor,
            Function<T, P> projector
    ) {
        String normalizedName = Objects.requireNonNull(name, "name").trim();
        if (normalizedName.isEmpty()) {
            throw new IllegalArgumentException("Projection name cannot be blank");
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("Projection columns cannot be empty");
        }
        return new EntityProjection<>(
                normalizedName,
                codec,
                idAccessor,
                List.copyOf(columns),
                List.of(),
                projection -> new LinkedHashMap<>(columnExtractor.apply(projection)),
                projector,
                ProjectionRefreshMode.SYNC
        );
    }

    public String name() {
        return name;
    }

    public ProjectionCodec<P> codec() {
        return codec;
    }

    public Function<P, ID> idAccessor() {
        return idAccessor;
    }

    public List<String> columns() {
        return columns;
    }

    public List<String> rankedColumns() {
        return rankedColumns;
    }

    public Function<P, Map<String, Object>> columnExtractor() {
        return columnExtractor;
    }

    public Function<T, P> projector() {
        return projector;
    }

    public ProjectionRefreshMode refreshMode() {
        return refreshMode;
    }

    public boolean supportsRankedColumn(String column) {
        if (column == null || column.isBlank()) {
            return false;
        }
        return rankedColumns.stream().anyMatch(candidate -> candidate.equals(column));
    }

    public EntityProjection<T, P, ID> rankedBy(String... columns) {
        if (columns == null || columns.length == 0) {
            return new EntityProjection<>(name, codec, idAccessor, this.columns, List.of(), columnExtractor, projector, refreshMode);
        }
        LinkedHashMap<String, Boolean> deduplicated = new LinkedHashMap<>();
        for (String column : columns) {
            if (column == null || column.isBlank()) {
                continue;
            }
            if (!this.columns.contains(column)) {
                throw new IllegalArgumentException("Ranked column must be part of the projection columns: " + column);
            }
            deduplicated.put(column, Boolean.TRUE);
        }
        return new EntityProjection<>(
                name,
                codec,
                idAccessor,
                this.columns,
                List.copyOf(deduplicated.keySet()),
                columnExtractor,
                projector,
                refreshMode
        );
    }

    public EntityProjection<T, P, ID> asyncRefresh() {
        return new EntityProjection<>(name, codec, idAccessor, columns, rankedColumns, columnExtractor, projector, ProjectionRefreshMode.ASYNC);
    }

    public EntityProjection<T, P, ID> syncRefresh() {
        return new EntityProjection<>(name, codec, idAccessor, columns, rankedColumns, columnExtractor, projector, ProjectionRefreshMode.SYNC);
    }
}

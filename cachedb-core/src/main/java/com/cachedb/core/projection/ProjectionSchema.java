package com.reactor.cachedb.core.projection;

import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Reflection-free projection schema shared by Redis serialization and query indexing.
 */
public final class ProjectionSchema<P> implements ProjectionCodec<P> {

    private final List<Column<P, ?>> columns;
    private final List<String> columnNames;
    private final Function<ProjectionRow, P> decoder;

    private ProjectionSchema(List<Column<P, ?>> columns, Function<ProjectionRow, P> decoder) {
        this.columns = List.copyOf(columns);
        this.columnNames = columns.stream().map(Column::name).toList();
        this.decoder = decoder;
    }

    public static <P> Builder<P> builder() {
        return new Builder<>();
    }

    @Override
    public String toRedisValue(P projection) {
        Objects.requireNonNull(projection, "projection");
        LinkedHashMap<String, String> values = new LinkedHashMap<>(columns.size());
        for (Column<P, ?> column : columns) {
            values.put(column.name(), column.encodedValue(projection));
        }
        return LengthPrefixedPayloadCodec.encode(values);
    }

    @Override
    public P fromRedisValue(String encoded) {
        return decoder.apply(new ProjectionRow(LengthPrefixedPayloadCodec.decode(encoded)));
    }

    public List<String> columns() {
        return columnNames;
    }

    public Map<String, Object> columnValues(P projection) {
        Objects.requireNonNull(projection, "projection");
        LinkedHashMap<String, Object> values = new LinkedHashMap<>(columns.size());
        for (Column<P, ?> column : columns) {
            values.put(column.name(), column.rawValue(projection));
        }
        return values;
    }

    private record Column<P, V>(
            String name,
            Function<P, V> accessor,
            ProjectionValueCodec<V> codec
    ) {
        private Column {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Projection column name must not be blank");
            }
            name = name.trim();
            Objects.requireNonNull(accessor, "accessor");
            Objects.requireNonNull(codec, "codec");
        }

        private String encodedValue(P projection) {
            return codec.encode(accessor.apply(projection));
        }

        private Object rawValue(P projection) {
            return accessor.apply(projection);
        }
    }

    public static final class Builder<P> {
        private final ArrayList<Column<P, ?>> columns = new ArrayList<>();
        private Function<ProjectionRow, P> decoder;

        public <V> Builder<P> column(
                String name,
                Function<P, V> accessor,
                ProjectionValueCodec<V> codec
        ) {
            String normalizedName = name == null ? null : name.trim();
            if (columns.stream().anyMatch(column -> column.name().equals(normalizedName))) {
                throw new IllegalArgumentException("Duplicate projection column: " + normalizedName);
            }
            columns.add(new Column<>(normalizedName, accessor, codec));
            return this;
        }

        public Builder<P> stringColumn(String name, Function<P, String> accessor) {
            return column(name, accessor, ProjectionValueCodecs.STRING);
        }

        public Builder<P> longColumn(String name, Function<P, Long> accessor) {
            return column(name, accessor, ProjectionValueCodecs.LONG);
        }

        public Builder<P> integerColumn(String name, Function<P, Integer> accessor) {
            return column(name, accessor, ProjectionValueCodecs.INTEGER);
        }

        public Builder<P> doubleColumn(String name, Function<P, Double> accessor) {
            return column(name, accessor, ProjectionValueCodecs.DOUBLE);
        }

        public Builder<P> booleanColumn(String name, Function<P, Boolean> accessor) {
            return column(name, accessor, ProjectionValueCodecs.BOOLEAN);
        }

        public Builder<P> decimalColumn(String name, Function<P, BigDecimal> accessor) {
            return column(name, accessor, ProjectionValueCodecs.BIG_DECIMAL);
        }

        public Builder<P> decodeWith(Function<ProjectionRow, P> decoder) {
            this.decoder = Objects.requireNonNull(decoder, "decoder");
            return this;
        }

        public ProjectionSchema<P> build() {
            if (columns.isEmpty()) {
                throw new IllegalStateException("Projection schema requires at least one column");
            }
            if (decoder == null) {
                throw new IllegalStateException("Projection schema requires decodeWith(...)");
            }
            return new ProjectionSchema<>(columns, decoder);
        }
    }
}

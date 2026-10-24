package com.reactor.cachedb.core.projection;

import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Reflection-free projection schema shared by Redis serialization and query indexing.
 */
public final class ProjectionSchema<P> implements ProjectionCodec<P> {

    private final List<Column<P, ?>> columns;
    private final List<String> columnNames;
    private final Function<ProjectionRow, P> decoder;
    private final Map<String, Integer> positions;

    private ProjectionSchema(List<Column<P, ?>> columns, Function<ProjectionRow, P> decoder) {
        this.columns = List.copyOf(columns);
        this.columnNames = columns.stream().map(Column::name).toList();
        this.decoder = decoder;
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) indexes.put(columns.get(i).name(), i);
        this.positions = Map.copyOf(indexes);
    }

    public static <P> Builder<P> builder() {
        return new Builder<>();
    }

    @Override
    public String toRedisValue(P projection) {
        Objects.requireNonNull(projection, "projection");
        StringBuilder payload = new StringBuilder();
        for (Column<P, ?> column : columns) {
            LengthPrefixedPayloadCodec.appendField(payload, column.prefix(), column.encodedValue(projection));
        }
        return payload.toString();
    }

    @Override
    public P fromRedisValue(String encoded) {
        String[] values = new String[columns.size()];
        boolean[] present = new boolean[columns.size()];
        String[] extras = null;
        int extraSize = 0;
        var cursor = LengthPrefixedPayloadCodec.cursor(encoded);
        while (cursor.next()) {
            String name = cursor.name();
            Integer position = name == null ? null : positions.get(name);
            if (position != null) {
                values[position] = cursor.value();
                present[position] = true;
            } else {
                if (extras == null) extras = new String[8];
                else if (extraSize == extras.length) extras = java.util.Arrays.copyOf(extras,extras.length*2);
                extras[extraSize++] = name;
                extras[extraSize++] = cursor.value();
            }
        }
        return decoder.apply(new ProjectionRow(positions, values, present, extras, extraSize));
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
            ProjectionValueCodec<V> codec,
            String prefix
    ) {
        private Column(String name, Function<P, V> accessor, ProjectionValueCodec<V> codec) {
            this(name, accessor, codec, null);
        }
        private Column {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Projection column name must not be blank");
            }
            name = name.trim();
            prefix = LengthPrefixedPayloadCodec.fieldPrefix(name);
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
            if (columns.size() >= LengthPrefixedPayloadCodec.MAX_FIELDS)
                throw new IllegalArgumentException("Projection schema supports at most " + LengthPrefixedPayloadCodec.MAX_FIELDS + " columns");
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

        public Builder<P> shortColumn(String name, Function<P, Short> accessor) {
            return column(name, accessor, ProjectionValueCodecs.SHORT);
        }

        public Builder<P> doubleColumn(String name, Function<P, Double> accessor) {
            return column(name, accessor, ProjectionValueCodecs.DOUBLE);
        }

        public Builder<P> floatColumn(String name, Function<P, Float> accessor) {
            return column(name, accessor, ProjectionValueCodecs.FLOAT);
        }

        public Builder<P> booleanColumn(String name, Function<P, Boolean> accessor) {
            return column(name, accessor, ProjectionValueCodecs.BOOLEAN);
        }

        public Builder<P> decimalColumn(String name, Function<P, BigDecimal> accessor) {
            return column(name, accessor, ProjectionValueCodecs.BIG_DECIMAL);
        }

        public Builder<P> uuidColumn(String name, Function<P, UUID> accessor) {
            return column(name, accessor, ProjectionValueCodecs.UUID_VALUE);
        }

        public Builder<P> instantColumn(String name, Function<P, Instant> accessor) {
            return column(name, accessor, ProjectionValueCodecs.INSTANT);
        }

        public Builder<P> localDateColumn(String name, Function<P, LocalDate> accessor) {
            return column(name, accessor, ProjectionValueCodecs.LOCAL_DATE);
        }

        public Builder<P> localDateTimeColumn(String name, Function<P, LocalDateTime> accessor) {
            return column(name, accessor, ProjectionValueCodecs.LOCAL_DATE_TIME);
        }

        public Builder<P> localTimeColumn(String name, Function<P, LocalTime> accessor) {
            return column(name, accessor, ProjectionValueCodecs.LOCAL_TIME);
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

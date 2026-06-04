package com.reactor.cachedb.core.cache;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record EntityHotPolicy(
        EntityHotPolicyMode mode,
        String timeColumn,
        long hotForSeconds,
        String stateColumn,
        List<String> stateValues,
        boolean admitOnWrite,
        boolean admitOnRead,
        boolean admitOnWarm,
        boolean evictWhenRejected,
        CacheAdmissionPredicate customPredicate,
        EntityHotPolicyCompositeOperator compositeOperator,
        List<EntityHotPolicy> children
) {
    public EntityHotPolicy(
            EntityHotPolicyMode mode,
            String timeColumn,
            long hotForSeconds,
            String stateColumn,
            List<String> stateValues,
            boolean admitOnWrite,
            boolean admitOnRead,
            boolean admitOnWarm,
            boolean evictWhenRejected,
            CacheAdmissionPredicate customPredicate
    ) {
        this(
                mode,
                timeColumn,
                hotForSeconds,
                stateColumn,
                stateValues,
                admitOnWrite,
                admitOnRead,
                admitOnWarm,
                evictWhenRejected,
                customPredicate,
                EntityHotPolicyCompositeOperator.ALL,
                List.of()
        );
    }

    public EntityHotPolicy {
        mode = mode == null ? EntityHotPolicyMode.COUNT_WINDOW : mode;
        timeColumn = blankToNull(timeColumn);
        stateColumn = blankToNull(stateColumn);
        stateValues = stateValues == null ? List.of() : List.copyOf(stateValues);
        compositeOperator = compositeOperator == null ? EntityHotPolicyCompositeOperator.ALL : compositeOperator;
        children = children == null
                ? List.of()
                : children.stream()
                .filter(Objects::nonNull)
                .toList();
    }

    public static EntityHotPolicy countWindow() {
        return builder().mode(EntityHotPolicyMode.COUNT_WINDOW).build();
    }

    public static EntityHotPolicy timeWindow(String timeColumn, long hotForSeconds) {
        return builder()
                .mode(EntityHotPolicyMode.TIME_WINDOW)
                .timeColumn(timeColumn)
                .hotForSeconds(hotForSeconds)
                .build();
    }

    public static EntityHotPolicy stateWindow(String stateColumn, Collection<String> stateValues) {
        return builder()
                .mode(EntityHotPolicyMode.STATE_WINDOW)
                .stateColumn(stateColumn)
                .stateValues(stateValues)
                .build();
    }

    public static EntityHotPolicy customPredicate(CacheAdmissionPredicate predicate) {
        return builder()
                .mode(EntityHotPolicyMode.CUSTOM_PREDICATE)
                .customPredicate(predicate)
                .build();
    }

    public static EntityHotPolicy allOf(Collection<EntityHotPolicy> children) {
        return builder()
                .mode(EntityHotPolicyMode.COMPOSITE)
                .compositeOperator(EntityHotPolicyCompositeOperator.ALL)
                .children(children)
                .build();
    }

    public static EntityHotPolicy anyOf(Collection<EntityHotPolicy> children) {
        return builder()
                .mode(EntityHotPolicyMode.COMPOSITE)
                .compositeOperator(EntityHotPolicyCompositeOperator.ANY)
                .children(children)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean shouldAdmit(Map<String, Object> columns, CacheAdmissionSource source) {
        return shouldAdmit(columns, source, Instant.now());
    }

    public boolean shouldAdmit(Map<String, Object> columns, CacheAdmissionSource source, Instant now) {
        if (!sourceAllowed(source)) {
            return false;
        }
        Map<String, Object> safeColumns = columns == null ? Map.of() : columns;
        return switch (mode) {
            case COUNT_WINDOW -> true;
            case TIME_WINDOW -> withinTimeWindow(safeColumns, now == null ? Instant.now() : now);
            case STATE_WINDOW -> withinStateWindow(safeColumns);
            case CUSTOM_PREDICATE -> customPredicate != null && customPredicate.admit(safeColumns);
            case COMPOSITE -> withinCompositePolicy(safeColumns, source, now == null ? Instant.now() : now);
        };
    }

    private boolean withinCompositePolicy(Map<String, Object> columns, CacheAdmissionSource source, Instant now) {
        if (children.isEmpty()) {
            return false;
        }
        if (compositeOperator == EntityHotPolicyCompositeOperator.ANY) {
            for (EntityHotPolicy child : children) {
                if (child.shouldAdmit(columns, source, now)) {
                    return true;
                }
            }
            return false;
        }
        for (EntityHotPolicy child : children) {
            if (!child.shouldAdmit(columns, source, now)) {
                return false;
            }
        }
        return true;
    }

    private boolean sourceAllowed(CacheAdmissionSource source) {
        CacheAdmissionSource normalized = source == null ? CacheAdmissionSource.READ : source;
        return switch (normalized) {
            case WRITE -> admitOnWrite;
            case READ -> admitOnRead;
            case WARM -> admitOnWarm;
            case SERVE -> true;
        };
    }

    private boolean withinTimeWindow(Map<String, Object> columns, Instant now) {
        if (timeColumn == null || hotForSeconds <= 0L) {
            return false;
        }
        Instant value = toInstant(columnValue(columns, timeColumn));
        if (value == null) {
            return false;
        }
        return !value.isBefore(now.minusSeconds(hotForSeconds));
    }

    private boolean withinStateWindow(Map<String, Object> columns) {
        if (stateColumn == null || stateValues.isEmpty()) {
            return false;
        }
        Object value = columnValue(columns, stateColumn);
        if (value == null) {
            return false;
        }
        String normalized = String.valueOf(value);
        for (String stateValue : stateValues) {
            if (normalized.equals(stateValue)) {
                return true;
            }
        }
        return false;
    }

    private Object columnValue(Map<String, Object> columns, String columnName) {
        if (columns.containsKey(columnName)) {
            return columns.get(columnName);
        }
        for (Map.Entry<String, Object> entry : columns.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.equalsIgnoreCase(columnName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return zonedDateTime.toInstant();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toInstant(ZoneOffset.UTC);
        }
        if (value instanceof LocalDate localDate) {
            return localDate.atStartOfDay().toInstant(ZoneOffset.UTC);
        }
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof Number number) {
            long epoch = number.longValue();
            return epoch > 9_999_999_999L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(raw).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static final class Builder {
        private EntityHotPolicyMode mode = EntityHotPolicyMode.COUNT_WINDOW;
        private String timeColumn;
        private long hotForSeconds;
        private String stateColumn;
        private List<String> stateValues = List.of();
        private boolean admitOnWrite = true;
        private boolean admitOnRead = true;
        private boolean admitOnWarm = true;
        private boolean evictWhenRejected = true;
        private CacheAdmissionPredicate customPredicate;
        private EntityHotPolicyCompositeOperator compositeOperator = EntityHotPolicyCompositeOperator.ALL;
        private List<EntityHotPolicy> children = List.of();

        public Builder mode(EntityHotPolicyMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder timeColumn(String timeColumn) {
            this.timeColumn = timeColumn;
            return this;
        }

        public Builder hotForSeconds(long hotForSeconds) {
            this.hotForSeconds = hotForSeconds;
            return this;
        }

        public Builder hotForDays(long hotForDays) {
            this.hotForSeconds = Math.max(0L, hotForDays) * 86_400L;
            return this;
        }

        public Builder stateColumn(String stateColumn) {
            this.stateColumn = stateColumn;
            return this;
        }

        public Builder stateValues(Collection<String> stateValues) {
            this.stateValues = stateValues == null ? List.of() : stateValues.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .toList();
            return this;
        }

        public Builder stateValues(String... stateValues) {
            return stateValues(stateValues == null ? List.of() : java.util.Arrays.asList(stateValues));
        }

        public Builder admitOnWrite(boolean admitOnWrite) {
            this.admitOnWrite = admitOnWrite;
            return this;
        }

        public Builder admitOnRead(boolean admitOnRead) {
            this.admitOnRead = admitOnRead;
            return this;
        }

        public Builder admitOnWarm(boolean admitOnWarm) {
            this.admitOnWarm = admitOnWarm;
            return this;
        }

        public Builder evictWhenRejected(boolean evictWhenRejected) {
            this.evictWhenRejected = evictWhenRejected;
            return this;
        }

        public Builder customPredicate(CacheAdmissionPredicate customPredicate) {
            this.customPredicate = customPredicate;
            return this;
        }

        public Builder compositeOperator(EntityHotPolicyCompositeOperator compositeOperator) {
            this.compositeOperator = compositeOperator;
            return this;
        }

        public Builder children(Collection<EntityHotPolicy> children) {
            this.children = children == null
                    ? List.of()
                    : children.stream()
                    .filter(Objects::nonNull)
                    .toList();
            return this;
        }

        public Builder child(EntityHotPolicy child) {
            if (child == null) {
                return this;
            }
            java.util.ArrayList<EntityHotPolicy> next = new java.util.ArrayList<>(children);
            next.add(child);
            this.children = List.copyOf(next);
            return this;
        }

        public Builder allOf(Collection<EntityHotPolicy> children) {
            this.mode = EntityHotPolicyMode.COMPOSITE;
            this.compositeOperator = EntityHotPolicyCompositeOperator.ALL;
            return children(children);
        }

        public Builder anyOf(Collection<EntityHotPolicy> children) {
            this.mode = EntityHotPolicyMode.COMPOSITE;
            this.compositeOperator = EntityHotPolicyCompositeOperator.ANY;
            return children(children);
        }

        public EntityHotPolicy build() {
            return new EntityHotPolicy(
                    mode,
                    timeColumn,
                    hotForSeconds,
                    stateColumn,
                    stateValues,
                    admitOnWrite,
                    admitOnRead,
                    admitOnWarm,
                    evictWhenRejected,
                    customPredicate,
                    compositeOperator,
                    children
            );
        }
    }
}

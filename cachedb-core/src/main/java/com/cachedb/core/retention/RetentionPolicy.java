package com.reactor.cachedb.core.retention;

import com.reactor.cachedb.core.model.EntityMetadata;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

public record RetentionPolicy(String field, int days, Action action, ScoreUnit scoreUnit) {
    public enum Action { EVICT_CACHE, DELETE }
    public enum ScoreUnit { EPOCH_MILLIS, EPOCH_DAY }
    public RetentionPolicy {
        Objects.requireNonNull(action); Objects.requireNonNull(scoreUnit);
        if (field == null || !field.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") || days < 1 || days > 36_500)
            throw new IllegalArgumentException("Invalid retention policy");
    }
    public void validate(EntityMetadata<?, ?> metadata) {
        if (!metadata.columns().contains(field) || !metadata.indexedColumns().contains(field))
            throw new IllegalArgumentException("Retention requires a persisted indexed field");
        String type = metadata.columnTypes().get(field);
        ScoreUnit actual = switch (type == null ? "" : type) {
            case "java.time.LocalDate" -> ScoreUnit.EPOCH_DAY;
            case "java.time.Instant", "java.time.LocalDateTime", "java.time.OffsetDateTime" -> ScoreUnit.EPOCH_MILLIS;
            default -> throw new IllegalArgumentException("Retention requires supported temporal metadata");
        };
        if (scoreUnit != actual) throw new IllegalArgumentException("Retention score unit disagrees with metadata");
    }
    public long cutoff(Clock clock) {
        return scoreUnit == ScoreUnit.EPOCH_DAY
                ? Math.subtractExact(LocalDate.now(clock.withZone(ZoneOffset.UTC)).toEpochDay(), days)
                : Math.subtractExact(clock.millis(), Math.multiplyExact((long) days, 86_400_000L));
    }
}

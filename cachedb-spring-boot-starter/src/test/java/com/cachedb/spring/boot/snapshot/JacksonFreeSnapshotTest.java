package com.reactor.cachedb.spring.boot.snapshot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.google.gson.stream.*;
import com.reactor.cachedb.core.model.SourceMapping;
import com.reactor.cachedb.core.repository.WindowRequest;
import com.reactor.cachedb.json.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

class JacksonFreeSnapshotTest {
    public record Summary(String title) {}

    public record LegacySummary(String displayName) {}

    static final class SummaryCodec extends StreamingJsonCodec<Summary> {
        public Class<Summary> valueType() {
            return Summary.class;
        }

        public Summary readValue(JsonReader reader, JsonCodecLimits limits) throws IOException {
            if (JsonValues.isNull(reader)) return null;
            String title = null;
            reader.beginObject();
            while (reader.hasNext()) {
                if (!reader.nextName().equals("title")) throw new IOException("Unknown field");
                title = JsonValues.string(reader, limits);
            }
            reader.endObject();
            return new Summary(title);
        }

        public void writeValue(JsonWriter writer, Summary value, JsonCodecLimits limits)
                throws IOException {
            if (value == null) {
                writer.nullValue();
                return;
            }
            writer.beginObject();
            writer.name("title");
            JsonValues.string(writer, value.title(), limits);
            writer.endObject();
        }
    }

    @TempDir Path directory;

    static SnapshotSource<String> source() {
        return SnapshotSource.entity(
                "roots",
                new SourceMapping<>("roots", List.of("id"), row -> (String) row.get("id")));
    }

    @Test
    void fourStatesAndTypedWindowsDoNotRequireAnObjectMapper() {
        class MutableClock extends Clock {
            Instant now = Instant.EPOCH;

            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            public Clock withZone(ZoneId zone) {
                return this;
            }

            public Instant instant() {
                return now;
            }
        }
        var clock = new MutableClock();
        var raw = new InMemorySnapshotRepository(clock, Duration.ofMinutes(1));
        var typed = raw.typedRepository(new SummaryCodec());
        assertEquals(SnapshotStatus.SNAPSHOT_UNAVAILABLE, typed.lookup("1").status());
        raw.putSnapshot("1", "[{\"title\":\"hello\"}]", "p", 1);
        raw.putSnapshot("2", "[]", "p", 2);
        assertEquals("hello", typed.lookup("1").values().get(0).title());
        assertEquals(SnapshotStatus.ABSENT_IN_GENERATION, typed.lookup("missing").status());
        assertTrue(typed.findValuesById("2").orElseThrow().isEmpty());
        clock.now = clock.now.plusSeconds(60);
        assertEquals(SnapshotStatus.STALE_WARNING, typed.lookup("1").status());
        var page = typed.findTypedPage("p", WindowRequest.first(1));
        assertEquals(
                "2",
                typed.findTypedPage("p", page.nextRequest(1).orElseThrow()).items().get(0).id());
        assertEquals(2, typed.lookupAll(List.of("1", "missing", "1")).size());
        raw.expire();
        assertFalse(typed.lookup("1").isSnapshotAvailable());
    }

    @Test
    void codecSelectionPreservesPlanShapeStreamingIndexesScopesAndMapping() {
        var roots = source();
        var codec = new SummaryCodec();
        var original =
                SnapshotPlan.streaming(
                        "catalog",
                        roots,
                        value -> value,
                        Summary.class,
                        value -> List.of(new Summary(value)));
        var plan =
                original.withJsonCodec(codec)
                        .indexed(SnapshotIndexSpec.of(value -> "p", value -> 1))
                        .scoped(
                                SnapshotRootScope.builder()
                                        .source(roots, ids -> SnapshotPredicate.inValues("id", ids))
                                        .build())
                        .withContentReuse();
        assertTrue(plan.streaming());
        assertSame(codec, plan.jsonCodec());
        assertTrue(plan.contentReuse());
        assertNotNull(plan.indexSpec());
        assertNotNull(plan.rootScope());
        assertSame(codec, plan.withJsonCodec(codec).jsonCodec());
        var mapped = plan.map("names", String.class, (root, values) -> List.of(root));
        assertTrue(mapped.streaming());
        assertNull(mapped.jsonCodec());
        assertEquals(List.of("a"), mapped.projection().apply(null).apply("a"));
        @SuppressWarnings({"rawtypes", "unchecked"})
        com.reactor.cachedb.core.codec.JsonArrayCodec<Summary> wrong =
                (com.reactor.cachedb.core.codec.JsonArrayCodec)
                        new JacksonJsonArrayCodec<>(new ObjectMapper(), String.class);
        assertThrows(IllegalArgumentException.class, () -> plan.withJsonCodec(wrong));
    }

    @Test
    void multipleRootsHaveExactUtf8SpoolOffsetsAndJacksonEquivalentPayloads() throws Exception {
        var roots = source();
        var plan =
                new SnapshotPlan<>(
                        "catalog",
                        roots,
                        value -> value,
                        List.of(roots),
                        Summary.class,
                        rows -> id -> id.equals("empty") ? List.of() : List.of(new Summary(id)));
        var rows =
                new SnapshotRows(
                        Map.of(roots, List.of("\u0130\u011f\ud83d\ude80", "next", "empty")));
        ObjectMapper mapper = new ObjectMapper();
        try (var legacy = new SnapshotSpool(directory);
                var fast = new SnapshotSpool(directory)) {
            legacy.prepare(plan, rows, mapper, () -> {});
            fast.prepare(
                    plan.withJsonCodec(new SummaryCodec()), rows, (ObjectMapper) null, () -> {});
            assertEquals(legacy.entries().size(), fast.entries().size());
            for (int i = 0; i < legacy.entries().size(); i++) {
                String before = legacy.read(legacy.entries().get(i), () -> {});
                String after = fast.read(fast.entries().get(i), () -> {});
                assertEquals(mapper.readTree(before), mapper.readTree(after));
                assertEquals(
                        after.getBytes(StandardCharsets.UTF_8).length,
                        fast.entries().get(i).bytes());
            }
        }
    }

    @Test
    void legacyMapperNamingConfigurationAndFactoryBehaviorRemainUntouched() throws Exception {
        ObjectMapper mapper =
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var raw = new InMemorySnapshotRepository();
        var typed = raw.typedRepository(mapper, LegacySummary.class);
        raw.putSnapshot("a", "[{\"display_name\":\"legacy\"}]");
        assertEquals("legacy", typed.lookup("a").values().get(0).displayName());
        var roots = source();
        var plan =
                new SnapshotPlan<>(
                        "legacy",
                        roots,
                        value -> value,
                        List.of(roots),
                        LegacySummary.class,
                        rows -> id -> List.of(new LegacySummary(id)));
        try (var spool = new SnapshotSpool(directory)) {
            spool.prepare(plan, new SnapshotRows(Map.of(roots, List.of("name"))), mapper, () -> {});
            assertEquals(
                    "[{\"display_name\":\"name\"}]", spool.read(spool.entries().get(0), () -> {}));
        }
    }
}

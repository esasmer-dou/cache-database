package com.reactor.cachedb.processor;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.codec.JsonArrayCodec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import javax.tools.*;

class CacheJsonRecordProcessorTest {
    @TempDir Path temp;

    record Compilation(boolean success, Path generated, Path classes, String diagnostics) {}

    private Compilation compile(Map<String, String> sources) throws IOException {
        Path root = Files.createDirectories(temp.resolve("src-" + System.nanoTime()));
        Path classes = Files.createDirectories(temp.resolve("classes-" + System.nanoTime()));
        Path generated = Files.createDirectories(temp.resolve("generated-" + System.nanoTime()));
        List<Path> paths = new ArrayList<>();
        for (var source : sources.entrySet()) {
            Path path = root.resolve(source.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, source.getValue());
            paths.add(path);
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task =
                    compiler.getTask(
                            null,
                            files,
                            diagnostics,
                            List.of(
                                    "--release",
                                    "17",
                                    "-classpath",
                                    System.getProperty("java.class.path"),
                                    "-s",
                                    generated.toString(),
                                    "-d",
                                    classes.toString()),
                            null,
                            files.getJavaFileObjectsFromPaths(paths));
            task.setProcessors(List.of(new CacheJsonRecordProcessor()));
            return new Compilation(
                    Boolean.TRUE.equals(task.call()),
                    generated,
                    classes,
                    diagnostics.getDiagnostics().toString());
        }
    }

    @SuppressWarnings("unchecked")
    private JsonArrayCodec<Object> codec(URLClassLoader loader, String name) throws Exception {
        return (JsonArrayCodec<Object>) loader.loadClass(name).getField("INSTANCE").get(null);
    }

    @Test
    void generatesNestedCollectionsTemporalEnumsAndDirectConstructorCalls() throws Exception {
        var compiled =
                compile(
                        Map.of(
                                "sample/Detail.java",
                                        """
                                        package sample;
                                        @com.reactor.cachedb.annotations.CacheJsonRecord
                                        public record Detail(String title, long quantity) {}
                                        """,
                                "sample/State.java",
                                        "package sample; public enum State { OPEN, CLOSED }",
                                "sample/Order.java",
                                        """
package sample;
import java.util.*; import java.math.*; import java.time.*;
@com.reactor.cachedb.annotations.CacheJsonRecord
public record Order(long id, String name, BigDecimal amount, BigInteger total,
    UUID key, Instant at, LocalDate day, LocalTime time, LocalDateTime local,
    OffsetDateTime offset, ZonedDateTime zone, State state, boolean active,
    byte small, Short shortValue, int count, Integer optionalCount, Float ratio,
    double score, char code, Character optionalCode,
    Detail detail, List<Detail> items, Map<String, List<Integer>> groups) {}
"""));
        assertTrue(compiled.success, compiled.diagnostics);
        String source = Files.readString(compiled.generated.resolve("sample/OrderJsonCodec.java"));
        assertTrue(source.contains("return new sample.Order("));
        assertFalse(source.contains("reflect"));
        assertFalse(source.contains("ObjectMapper"));
        assertFalse(source.contains("new com.google.gson.Gson"));
        try (var loader =
                new URLClassLoader(
                        new java.net.URL[] {compiled.classes.toUri().toURL()},
                        getClass().getClassLoader())) {
            var codec = codec(loader, "sample.OrderJsonCodec");
            String json =
                    """
[{"id":9223372036854775807,"name":"text","amount":12.30,"total":999999999999999999999,
"key":"123e4567-e89b-12d3-a456-426614174000","at":"2026-10-21T00:00:00Z",
"day":"2026-10-21","time":"12:30:01","local":"2026-10-21T12:30:01",
"offset":"2026-10-21T12:30:01+03:00","zone":"2026-10-21T12:30:01+03:00[Europe/Istanbul]",
"state":"OPEN","active":true,"small":12,"shortValue":24,"count":45,"optionalCount":null,
"ratio":0.25,"score":1.125,"code":"A","optionalCode":null,
"detail":{"title":"nested","quantity":3},"items":[null,{"title":"line","quantity":4}],
"groups":{"a":[1,null,2]}}]
""";
            var values = codec.decodeArray(json);
            var encoded = new ByteArrayOutputStream();
            codec.encodeArray(values, encoded);
            assertEquals(values, codec.decodeArray(encoded.toString(StandardCharsets.UTF_8)));
            assertThrows(IOException.class, () -> codec.decodeArray("[{\"state\":\"UNKNOWN\"}]"));
            assertThrows(IOException.class, () -> codec.decodeArray("[{\"small\":128}]"));
            assertEquals(1, codec.decodeArray("[{}]").size());
        }
    }

    @Test
    void supportsPublicNestedRecordsAndExplicitUnknownFieldOptIn() throws Exception {
        var compiled =
                compile(
                        Map.of(
                                "nested/Outer.java",
                                """
package nested;
public class Outer {
    @com.reactor.cachedb.annotations.CacheJsonRecord(ignoreUnknown = true)
    public record Summary(String title) {}
}
"""));
        assertTrue(compiled.success, compiled.diagnostics);
        try (var loader =
                new URLClassLoader(
                        new java.net.URL[] {compiled.classes.toUri().toURL()},
                        getClass().getClassLoader())) {
            var codec = codec(loader, "nested.Outer_SummaryJsonCodec");
            var values = codec.decodeArray("[{\"title\":\"ok\",\"ignored\":{\"nested\":[1,2]}}]");
            var out = new ByteArrayOutputStream();
            codec.encodeArray(values, out);
            assertEquals("[{\"title\":\"ok\"}]", out.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void recursiveCodecsAreBoundedWithoutRecursiveConstruction() throws Exception {
        var compiled =
                compile(
                        Map.of(
                                "recursive/Node.java",
                                """
                                package recursive;
                                @com.reactor.cachedb.annotations.CacheJsonRecord
                                public record Node(String title, java.util.List<Node> children) {}
                                """));
        assertTrue(compiled.success, compiled.diagnostics);
        try (var loader =
                new URLClassLoader(
                        new java.net.URL[] {compiled.classes.toUri().toURL()},
                        getClass().getClassLoader())) {
            var codec = codec(loader, "recursive.NodeJsonCodec");
            String node = "{\"title\":\"leaf\",\"children\":[]}";
            assertEquals(1, codec.decodeArray("[" + node + "]").size());
            for (int i = 0; i < 70; i++) node = "{\"children\":[" + node + "]}";
            String oversized = "[" + node + "]";
            assertThrows(IOException.class, () -> codec.decodeArray(oversized));
        }
    }

    @Test
    void rejectsUnsupportedTypesAndForeignMappingInsteadOfChangingTheirMeaning() throws Exception {
        for (String fields :
                List.of(
                        "Object value",
                        "java.util.Set<String> values",
                        "java.util.Map<Long,String> values",
                        "java.util.List<?> values",
                        "@com.fasterxml.jackson.annotation.JsonProperty(\"renamed\") String"
                            + " value")) {
            var compiled =
                    compile(
                            Map.of(
                                    "invalid/Value.java",
                                    "package invalid;"
                                        + " @com.reactor.cachedb.annotations.CacheJsonRecord public"
                                        + " record Value("
                                            + fields
                                            + ") {}"));
            assertFalse(compiled.success, fields);
        }
    }
}

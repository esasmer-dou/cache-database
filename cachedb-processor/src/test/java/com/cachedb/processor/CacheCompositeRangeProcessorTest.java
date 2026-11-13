package com.reactor.cachedb.processor;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheCompositeRangeProcessorTest {
    @TempDir Path temp;
    record Compilation(boolean success, String diagnostics, String binding) {}

    private Compilation compile(String sortBy, String type) throws IOException {
        return compile(sortBy, type, "\"tenant\", \"status\"");
    }

    private Compilation compile(String sortBy, String type, String prefix) throws IOException {
        Path root = Files.createDirectory(temp.resolve("case-" + UUID.randomUUID()));
        Path source = root.resolve("Row.java");
        Path generated = Files.createDirectory(root.resolve("generated"));
        Path classes = Files.createDirectory(root.resolve("classes"));
        Files.writeString(source, """
                package f1;
                import com.reactor.cachedb.annotations.*;
                @CacheEntity(table="rows", redisNamespace="rows", indexedColumns={"status"})
                @CacheCompositeIndex(columns={%s}, sortBy="%s")
                public record Row(@CacheId String id, @CacheColumn("tenant") String tenant,
                        @CacheColumn("status") String status, @CacheColumn("sort_score") %s createdScore) {}
                """.formatted(prefix, sortBy, type));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            var task = compiler.getTask(null, files, diagnostics,
                    List.of("--release", "21", "-proc:full", "-classpath", System.getProperty("java.class.path"),
                            "-s", generated.toString(), "-d", classes.toString()), null,
                    files.getJavaFileObjectsFromPaths(List.of(source)));
            task.setProcessors(List.of(new CacheEntityProcessor()));
            boolean success = Boolean.TRUE.equals(task.call());
            String text = diagnostics.getDiagnostics().stream().map(d -> d.getMessage(Locale.ROOT))
                    .collect(java.util.stream.Collectors.joining("\n"));
            Path binding = generated.resolve("f1/RowCacheBinding.java");
            return new Compilation(success, text, Files.exists(binding) ? Files.readString(binding) : "");
        }
    }

    @Test void sortByAliasesResolveToPersistedColumnsAndAreAutoIndexed() throws IOException {
        for (String alias : List.of("createdScore", "sort_score")) {
            var result = compile(alias, "java.time.Instant");
            assertTrue(result.success(), result.diagnostics());
            assertTrue(result.binding().contains("compositeSortColumns()"), result.binding());
            assertTrue(result.binding().contains("java.util.Map.entry(\"tenant_status\", \"sort_score\")"));
            String indexed = result.binding().lines().filter(line -> line.contains("INDEXED_COLUMNS =")).findFirst().orElseThrow();
            assertTrue(indexed.contains("\"sort_score\"") && indexed.contains("\"tenant\"") && indexed.contains("\"status\""), indexed);
        }
    }

    @Test void oneEqualityPrefixRequiresADistinctSortColumn() throws IOException {
        var sorted = compile("createdScore", "long", "\"tenant\"");
        assertTrue(sorted.success(), sorted.diagnostics());
        assertTrue(sorted.binding().contains("java.util.Map.entry(\"tenant\", \"sort_score\")"));
        assertFalse(compile("", "long", "\"tenant\"").success());
        assertFalse(compile("tenant", "long", "\"tenant\"").success());
    }

    @Test void unsupportedUnknownAndPrefixSortFieldsFailCompilation() throws IOException {
        for (String sort : List.of("unknown", "tenant", "status")) {
            var result = compile(sort, "long");
            assertFalse(result.success(), result.binding());
            assertTrue(result.diagnostics().contains("composite") || result.diagnostics().contains("Composite"),
                    result.diagnostics());
        }
        for (String type : List.of("String", "boolean", "java.math.BigDecimal", "java.time.LocalTime")) {
            var result = compile("createdScore", type);
            assertFalse(result.success());
            assertTrue(result.diagnostics().contains("Unsupported composite sortBy type"), result.diagnostics());
        }
    }

    @Test void everyExistingScoreTypeCompilesAndLegacyBindingsDoNotEmitSortMetadata() throws IOException {
        for (String type : List.of("int", "long", "double", "float", "short", "byte", "java.lang.Integer",
                "java.lang.Long", "java.lang.Double", "java.lang.Float", "java.lang.Short", "java.lang.Byte",
                "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime", "java.time.OffsetDateTime")) {
            var result = compile("createdScore", type);
            assertTrue(result.success(), type + ": " + result.diagnostics());
        }
        var legacy = compile("", "long");
        assertTrue(legacy.success(), legacy.diagnostics());
        assertTrue(legacy.binding().contains("compositeExactIndexes()"));
        assertFalse(legacy.binding().contains("compositeSortColumns()"));
    }
}

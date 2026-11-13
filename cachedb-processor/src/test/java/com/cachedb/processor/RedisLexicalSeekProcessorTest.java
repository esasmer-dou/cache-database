package com.reactor.cachedb.processor;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RedisLexicalSeekProcessorTest {
    @TempDir Path temp;

    @Test void explicitCursorRouteUsesNativeCapabilityWithoutTypedReslicing() throws Exception {
        Result result = compile("Integer", "", "", "");
        assertTrue(result.success(), result.diagnostics());
        String generated = Files.readString(result.generated().resolve("sample/RowsCacheDbImplementation.java"));
        assertTrue(generated.contains("RedisLexicalWindowRepository.read("), generated);
        assertTrue(generated.contains("\"status_score\""), generated);
        assertTrue(generated.contains("cachedb$page.nextCursor()"), generated);
        assertFalse(generated.contains("KeysetPagination.apply("), generated);
        assertFalse(generated.contains("KeysetPagination.hotWindow("), generated);
        assertTrue(generated.contains(".limitTo(window.limit())"), generated);
    }

    @Test void rawSeekUsesDoubleScoreAndStringIdEvenForANumericEntityId() throws Exception {
        String seek = ", seek={@CacheSeek(field=\"score\",param=\"afterScore\",direction=CachePredicate.Operator.GT),"
                + "@CacheSeek(field=\"id\",param=\"afterId\",direction=CachePredicate.Operator.GT)}";
        Result result = compile("Integer", seek, ", Double afterScore, String afterId", "");
        assertTrue(result.success(), result.diagnostics());
        String generated = Files.readString(result.generated().resolve("sample/RowsCacheDbImplementation.java"));
        assertTrue(generated.contains("afterScore, afterId)"), generated);
        assertFalse(generated.contains("KeysetPagination.apply("), generated);
        Result typedId = compile("Integer", seek, ", Double afterScore, Long afterId", "");
        assertFalse(typedId.success());
        assertTrue(typedId.diagnostics().contains("nullable Double score and String ID"), typedId.diagnostics());
    }

    @Test void unsafeScoreDomainsHaveAnExplicitCompileTimeFailure() throws Exception {
        for (String type : List.of("Double", "Float", "java.time.Instant", "java.time.LocalDateTime",
                "java.time.OffsetDateTime")) {
            Result result = compile(type, "", "", "");
            assertFalse(result.success(), type);
            assertTrue(result.diagnostics().contains("lossless score type"), result.diagnostics());
        }
    }

    @Test void unindexedAndUnsupportedTypedOperatorsDoNotBecomeScans() throws Exception {
        Result unindexed = compile("Integer", "", "", ", @CachePredicate(field=\"note\", constants=\"x\")");
        assertFalse(unindexed.success());
        assertTrue(unindexed.diagnostics().contains("predicate is not indexed: note"), unindexed.diagnostics());
        Result notEqual = compile("Integer", "", "",
                ", @CachePredicate(field=\"score\",operator=CachePredicate.Operator.NE,constants=\"3\","
                        + "constantType=CachePredicate.ConstantType.INTEGER)");
        assertFalse(notEqual.success());
        assertTrue(notEqual.diagnostics().contains("typed ranges on sortBy only"), notEqual.diagnostics());
    }

    @Test void missingOptInAndPrefixAndSourceRoutesAreRejected() throws Exception {
        Result pair = compileRepository(entity("Integer"), repository("", "", "")
                .replace("redisLexicalIdTie=true,", ""));
        assertFalse(pair.success());
        assertTrue(pair.diagnostics().contains("must be declared together"), pair.diagnostics());
        Result prefix = compileRepository(entity("Integer"), repository("", "", "")
                .replace("@CachePredicate(field=\"status\")", "@CachePredicate(field=\"score\",constants=\"3\","
                        + "constantType=CachePredicate.ConstantType.INTEGER)"));
        assertFalse(prefix.success());
        assertTrue(prefix.diagnostics().contains("EQ for every composite prefix"), prefix.diagnostics());
        Result source = compileRepository(entity("Integer"), repository("", "", "")
                .replace("@HotRoute(value=\"by-status\",coverageScopeParameter=\"status\")", "@SourceRoute(\"by-status\")")
                .replace("HotWindow<Row>", "SourceWindow<Row>"));
        assertFalse(source.success());
        assertTrue(source.diagnostics().contains("only for entity @HotRoute"), source.diagnostics());
    }

    private Result compile(String type, String seek, String parameters, String predicates) throws Exception {
        return compileRepository(entity(type), repository(seek, parameters, predicates));
    }
    private static String entity(String type) {
        return """
                package sample;
                import com.reactor.cachedb.annotations.*;
                @CacheEntity(table="rows",redisNamespace="rows",indexedColumns={"status","score"})
                @CacheCompositeIndex(name="status_score",columns="status",sortBy="score")
                public record Row(@CacheId(column="id") Long id,
                        @CacheColumn("status") String status,
                        @CacheColumn("score") %s score,
                        @CacheColumn("note") String note) {}
                """.formatted(type);
    }
    private static String repository(String seek, String parameters, String predicates) {
        return """
                package sample;
                import com.reactor.cachedb.annotations.*;
                import com.reactor.cachedb.core.repository.*;
                @CacheRepository(entity=Row.class,springBean=false)
                public interface Rows extends CacheDbRepository<Row,Long> {
                    @HotRoute(value="by-status",coverageScopeParameter="status")
                    @CacheRouteQuery(redisLexicalIdTie=true,redisCompositeIndex="status_score",
                        predicates={@CachePredicate(field="status")%s},
                        orderBy=@CacheOrder(field="score",direction=CacheOrder.Direction.ASC)%s)
                    HotWindow<Row> window(String status, WindowRequest window%s);
                }
                """.formatted(predicates, seek, parameters);
    }

    private Result compileRepository(String entity, String repository) throws Exception {
        Path root = Files.createTempDirectory(temp, "lexical-");
        Path sources = Files.createDirectories(root.resolve("sources/sample"));
        Path generated = Files.createDirectories(root.resolve("generated"));
        Path classes = Files.createDirectories(root.resolve("classes"));
        Path row = sources.resolve("Row.java"), rows = sources.resolve("Rows.java");
        Files.writeString(row, entity); Files.writeString(rows, repository);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            var task = compiler.getTask(null, files, diagnostics,
                    List.of("--release", "21", "-proc:full", "-classpath", System.getProperty("java.class.path"),
                            "-s", generated.toString(), "-d", classes.toString()), null,
                    files.getJavaFileObjects(row.toFile(), rows.toFile()));
            task.setProcessors(List.of(new CacheEntityProcessor(), new CacheRepositoryProcessor()));
            boolean success = Boolean.TRUE.equals(task.call());
            String text = diagnostics.getDiagnostics().stream().map(item -> item.getMessage(Locale.ROOT))
                    .reduce("", (left, right) -> left + "\n" + right);
            return new Result(success, generated, text);
        }
    }
    private record Result(boolean success, Path generated, String diagnostics) {}
}

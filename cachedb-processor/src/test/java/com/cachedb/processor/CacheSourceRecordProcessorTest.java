package com.reactor.cachedb.processor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import javax.tools.*;

class CacheSourceRecordProcessorTest {
    @TempDir Path temp;

    @Test
    void sourceRecordAndConsumerCompileInOnePass() throws Exception {
        var result =
                compile(
                        """
@CacheSourceRecord(table="public.Conditions")
public record Row(String campaignId, @CacheColumn("isDiscount") boolean discount,
        Double value, int day) {}
""");
        assertTrue(result.success, result.diagnostics);
        String binding = Files.readString(result.generated.resolve("sample/RowSourceBinding.java"));
        assertTrue(binding.contains("new Row("));
        assertTrue(binding.contains("Boolean.TRUE.equals("));
        assertFalse(binding.contains("reflect"));
        assertTrue(
                Files.readString(result.generated.resolve("sample/RowFields.java"))
                        .contains("\"isDiscount\""));
    }

    @Test
    void invalidShapeTypeAndDuplicateColumnsFailAtCompileTime() throws Exception {
        for (String source :
                List.of(
                        "@CacheSourceRecord(table=\"x\") public class Row {}",
                        "@CacheSourceRecord(table=\"x; DROP TABLE x\") public record Row(String id)"
                            + " {}",
                        "@CacheSourceRecord(table=\"x\") public record Row(java.util.List<String>"
                            + " ids) {}",
                        "@CacheSourceRecord(table=\"x\") public record Row(@CacheColumn(\"id\")"
                            + " String a, @CacheColumn(\"id\") String b) {}"))
            assertFalse(compile(source).success, source);
    }

    private Result compile(String declaration) throws Exception {
        Path root = Files.createDirectories(temp.resolve(UUID.randomUUID().toString()));
        Path generated = Files.createDirectory(root.resolve("generated"));
        Path classes = Files.createDirectory(root.resolve("classes"));
        Path source = root.resolve("Row.java");
        Files.writeString(
                source,
                "package sample; import com.reactor.cachedb.annotations.*;\n" + declaration);
        Path consumer = root.resolve("Use.java");
        Files.writeString(
                consumer,
                "package sample; class Use { Object mapping = RowSourceBinding.SOURCE; }");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
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
                            files.getJavaFileObjects(source, consumer));
            task.setProcessors(List.of(new CacheSourceRecordProcessor()));
            boolean success = task.call();
            return new Result(success, generated, diagnostics.getDiagnostics().toString());
        }
    }

    private record Result(boolean success, Path generated, String diagnostics) {}
}

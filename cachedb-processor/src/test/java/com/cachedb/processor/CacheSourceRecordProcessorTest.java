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
        assertTrue(binding.contains("Null source column: isDiscount"));
        assertFalse(binding.contains("Boolean.TRUE.equals("));
        assertFalse(binding.contains("reflect"));
        assertTrue(
                Files.readString(result.generated.resolve("sample/RowFields.java"))
                        .contains("\"isDiscount\""));
    }

    @Test
    void extendedScalarsAndEnumsGenerateDirectConversions() throws Exception {
        var result =
                compile(
                        """
@CacheSourceRecord(table="records")
public record Row(java.time.OffsetDateTime at, java.time.LocalTime time,
        java.math.BigInteger big, float ratio, short count, byte code, State state) {
    public enum State { ACTIVE, PENDING }
}
""");
        assertTrue(result.success, result.diagnostics);
        String binding = Files.readString(result.generated.resolve("sample/RowSourceBinding.java"));
        assertTrue(binding.contains("sample.Row.State::valueOf"));
        assertTrue(binding.contains("java.lang.Float.class"));
        assertTrue(binding.contains("Null source column: code"));
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
        return compile(
                declaration,
                "package sample; class Use { Object mapping = RowSourceBinding.SOURCE; }");
    }

    @Test
    void nullCannotConstructPrimitiveAndSqlNullFailsExplicitly() throws Exception {
        String declaration =
                "@CacheSourceRecord(table=\"x\") public record Row(boolean enabled) {}";
        assertFalse(
                compile(declaration, "package sample; class Use { Row invalid = new Row(null); }")
                        .success);
        var result =
                compile(
                        declaration,
                        """
package sample;
class Use {
    public static void main(String[] args) {
        var values=new java.util.HashMap<String,Object>();
        values.put("enabled",null);
        try { RowSourceBinding.SOURCE.decoder().apply(values); }
        catch(NullPointerException expected) {
            if(expected.getMessage().contains("enabled"))return;
            throw expected;
        }
        throw new AssertionError("SQL NULL was silently converted to false");
    }
}
""");
        assertTrue(result.success, result.diagnostics);
        var javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process =
                new ProcessBuilder(
                                javaExecutable,
                                "-cp",
                                result.classes
                                        + java.io.File.pathSeparator
                                        + System.getProperty("java.class.path"),
                                "sample.Use")
                        .redirectErrorStream(true)
                        .start();
        assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(
                0,
                process.exitValue(),
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
    }

    private Result compile(String declaration, String consumerCode) throws Exception {
        Path root = Files.createDirectories(temp.resolve(UUID.randomUUID().toString()));
        Path generated = Files.createDirectory(root.resolve("generated"));
        Path classes = Files.createDirectory(root.resolve("classes"));
        Path source = root.resolve("Row.java");
        Files.writeString(
                source,
                "package sample; import com.reactor.cachedb.annotations.*;\n" + declaration);
        Path consumer = root.resolve("Use.java");
        Files.writeString(consumer, consumerCode);
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
            return new Result(success, generated, classes, diagnostics.getDiagnostics().toString());
        }
    }

    private record Result(boolean success, Path generated, Path classes, String diagnostics) {}
}

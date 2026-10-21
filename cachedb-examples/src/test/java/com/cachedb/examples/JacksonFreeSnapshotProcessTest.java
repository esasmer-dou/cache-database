package com.reactor.cachedb.examples;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.model.SourceMapping;
import com.reactor.cachedb.examples.snapshotjson.CatalogSummary;
import com.reactor.cachedb.examples.snapshotjson.CatalogSummaryJsonCodec;
import com.reactor.cachedb.redis.JedisPooledOperations;
import com.reactor.cachedb.spring.boot.snapshot.*;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;

import java.io.File;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class JacksonFreeSnapshotProcessTest {
    @TempDir Path directory;

    @Test
    void generatedWarmAndReadWorkWhenJacksonJarsArePhysicallyAbsent() throws Exception {
        String original = System.getProperty("java.class.path");
        String classpath =
                String.join(
                        File.pathSeparator,
                        Arrays.stream(
                                        original.split(
                                                java.util.regex.Pattern.quote(File.pathSeparator)))
                                .filter(
                                        path ->
                                                !path.replace('\\', '/')
                                                        .matches(".*/jackson-[^/]*\\.jar$"))
                                .toList());
        assertNotEquals(original, classpath, "The probe must actually exclude Jackson JARs");
        Path output = directory.resolve("process.log");
        var process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                classpath,
                                Probe.class.getName(),
                                Integer.toString(
                                        Integer.getInteger("cachedb.snapshot.redis.port", 16383)))
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
        try {
            assertTrue(
                    process.waitFor(45, TimeUnit.SECONDS), "Jackson-free warm process timed out");
            assertEquals(
                    0,
                    process.exitValue(),
                    () -> {
                        try {
                            return Files.readString(output);
                        } catch (Exception failure) {
                            return failure.toString();
                        }
                    });
            assertTrue(Files.readString(output).contains("JACKSON_FREE_WARM_OK"));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    /**
     * Separate JVM entry point: deliberately no Jackson imports, mapper construction or reflection.
     */
    public static final class Probe {
        public record Product(String id, String title) {}

        public static void main(String[] args) throws Exception {
            String namespace = "no-jackson-process-" + UUID.randomUUID();
            var sql = new JdbcDataSource();
            sql.setURL("jdbc:h2:mem:" + namespace);
            try (var connection = sql.getConnection();
                    var statement = connection.createStatement();
                    var redis = new JedisPooled("redis://127.0.0.1:" + args[0])) {
                statement.execute(
                        "CREATE TABLE \"products\"(\"id\" VARCHAR PRIMARY KEY, \"title\" VARCHAR)");
                statement.execute(
                        "INSERT INTO \"products\" VALUES ('42','\u0130\u011f\ud83d\ude80')");
                var source =
                        SnapshotSource.entity(
                                "products",
                                new SourceMapping<Product>(
                                        "products",
                                        List.of("id", "title"),
                                        row ->
                                                new Product(
                                                        (String) row.get("id"),
                                                        (String) row.get("title"))));
                var plan =
                        SnapshotPlan.streaming(
                                        "catalog",
                                        source,
                                        Product::id,
                                        CatalogSummary.class,
                                        product -> List.of(new CatalogSummary(product.title())))
                                .withJsonCodec(CatalogSummaryJsonCodec.INSTANCE);
                var operations = JedisPooledOperations.of(redis);
                try (var jobs =
                        SnapshotJobs.jacksonFree(
                                sql,
                                operations,
                                operations,
                                Clock.systemUTC(),
                                namespace,
                                List.of(plan),
                                Map.of())) {
                    jobs.refresh("catalog", true);
                    statement.execute("DROP TABLE \"products\"");
                    TypedSnapshotRepository<String, CatalogSummary> typed =
                            jobs.typedRepository("catalog");
                    if (!typed.lookup("42")
                            .values()
                            .get(0)
                            .title()
                            .equals("\u0130\u011f\ud83d\ude80"))
                        throw new AssertionError("Generated JSON contract mismatch");
                } finally {
                    String cursor = "0";
                    do {
                        var batch =
                                redis.scan(
                                        cursor,
                                        new ScanParams().match(namespace + ":*").count(100));
                        cursor = batch.getCursor();
                        for (String key : batch.getResult()) redis.unlink(key);
                    } while (!cursor.equals("0"));
                }
            }
            System.out.println("JACKSON_FREE_WARM_OK");
        }
    }
}

package com.reactor.cachedb.processor;

import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import javax.tools.*;

/** Test harness only. Compilation and reflective fixture loading run only in the parent environment. */
public final class EncryptionCompilerFixture {
    public static final String SECRET = "PII_EMAIL_MARKER_6DD42";
    public static final String HIDDEN = "PII_HIDDEN_MARKER_F72";
    private EncryptionCompilerFixture() {}

    public record Compilation(boolean success, Path generated, Path classes, String diagnostics) {
        public String source(String name) throws IOException { return Files.readString(generated.resolve(name)); }
    }

    public static Compilation compile(Path temp, Map<String, String> sources, javax.annotation.processing.Processor... auditors) throws IOException {
        Path root = Files.createTempDirectory(temp, "source-");
        Path generated = Files.createTempDirectory(temp, "generated-");
        Path classes = Files.createTempDirectory(temp, "classes-");
        List<Path> files = new ArrayList<>();
        for (var source : sources.entrySet()) {
            Path file = root.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
            files.add(file);
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Parent test environment requires JDK 21");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task = compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "21", "-proc:full", "-classpath", System.getProperty("java.class.path"),
                            "-d", classes.toString(), "-s", generated.toString()), null,
                    manager.getJavaFileObjectsFromPaths(files));
            var processors=new ArrayList<javax.annotation.processing.Processor>(List.of(new CacheEntityProcessor(),
                    new CacheRepositoryProcessor(),new CacheJsonRecordProcessor(),new CacheProjectionRecordProcessor()));
            processors.addAll(List.of(auditors));
            task.setProcessors(processors);
            return new Compilation(Boolean.TRUE.equals(task.call()), generated, classes, diagnostics.getDiagnostics().toString());
        }
    }

    public static Map<String, String> recordSources(boolean tenantScoped) {
        String row = """
                package sample;
                import com.reactor.cachedb.annotations.*;
                @CacheEntity(table="protected_rows", redisNamespace="protected_rows", indexedColumns={"id","tenant_id","status"})
                public record ProtectedRow(@CacheId(column="id") Long id,
                    %s @CacheColumn("tenant_id") String tenant,
                    @CacheColumn("status") String status,
                    @CacheEncrypted(blindIndex=true) @CacheColumn("contact") String contact,
                    @CacheEncrypted(scope=EncryptionScope.REDIS_AND_SOURCE,blindIndex=true)
                    @CacheColumn("amount") java.math.BigDecimal amount,
                    @CacheEncrypted @CacheColumn("hidden") String hidden) {}
                """.formatted(tenantScoped ? "@CacheTenantId" : "");
        return Map.of("sample/ProtectedRow.java", row, "sample/Scenario.java", """
                package sample;
                public final class Scenario implements java.util.function.Supplier<Object[]> {
                    public Object[] get() {
                        return new Object[] {new ProtectedRow(1L,"tenant-a","OPEN","%s",
                            new java.math.BigDecimal("98765.4321"),"%s"),
                            ProtectedRowCacheBinding.METADATA, ProtectedRowCacheBinding.CODEC};
                    }
                }
                """.formatted(SECRET, HIDDEN));
    }

    public static KeyMaterial key(String id, int seed) {
        byte[] aes = new byte[32], blind = new byte[32];
        Arrays.fill(aes, (byte) seed);
        Arrays.fill(blind, (byte) 0x72);
        return new KeyMaterial(id, aes, blind);
    }

    public static CacheKeyProvider keys(String current, KeyMaterial... materials) {
        return new StaticKeyProvider(Map.of("default", List.of(materials)), Map.of("default", current));
    }

    public static Loaded load(Compilation result) throws Exception {
        if (!result.success()) throw new AssertionError(result.diagnostics());
        var loader = new URLClassLoader(new java.net.URL[] {result.classes().toUri().toURL()}, EncryptionCompilerFixture.class.getClassLoader());
        try {
            @SuppressWarnings("unchecked")
            Supplier<Object[]> scenario = (Supplier<Object[]>) loader.loadClass("sample.Scenario").getConstructor().newInstance();
            Object[] data = scenario.get();
            @SuppressWarnings("unchecked") EntityMetadata<Object, Object> metadata = (EntityMetadata<Object, Object>) data[1];
            @SuppressWarnings("unchecked") EntityCodec<Object> codec = (EntityCodec<Object>) data[2];
            return new Loaded(loader, data[0], metadata, codec);
        } catch (Throwable failure) { loader.close(); throw failure; }
    }

    public record Loaded(URLClassLoader loader, Object entity, EntityMetadata<Object, Object> metadata,
            EntityCodec<Object> unbound) implements AutoCloseable {
        public EntityCodec<Object> codec() { return unbound.bindEncryption(keys("k1", key("k1", 1))); }
        @Override public void close() throws IOException { loader.close(); }
    }
}

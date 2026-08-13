package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.page.EntityPageLoader;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.projection.EntityProjectionBinding;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;
import com.reactor.cachedb.core.relation.RelationBatchLoader;
import com.reactor.cachedb.processor.CacheEntityProcessor;
import com.reactor.cachedb.processor.CacheProjectionRecordProcessor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationScaffoldCompilationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void generatedMigrationProjectionAndRelationLoaderShouldCompile() throws Exception {
        DataSource dataSource = schema();
        MigrationScaffoldGenerator generator = new MigrationScaffoldGenerator(
                new MigrationSchemaDiscovery(dataSource, emptyRegistry())
        );
        MigrationScaffoldGenerator.Result result = generator.generate(new MigrationScaffoldGenerator.Request(
                new MigrationPlanner.Request(
                        "customer-orders",
                        "customer_account",
                        "customer_id",
                        "customer_order",
                        "order_id",
                        "customer_id",
                        "order_date",
                        "DESC",
                        100L,
                        5_000L,
                        40L,
                        2_000L,
                        100,
                        1_000,
                        true,
                        false,
                        false,
                        false,
                        true,
                        false,
                        true,
                        true,
                        true
                ),
                "com.acme.cachedb.migration",
                "",
                "",
                "",
                "CustomerOrderSummary",
                true,
                true,
                List.of("order_amount", "currency_code", "order_type")
        ));

        List<Path> javaFiles = writeGeneratedSources(result.files());
        Path generatedSources = compile(javaFiles);

        assertEquals(5, javaFiles.size());
        assertTrue(Files.isRegularFile(generatedSources.resolve(
                "com/acme/cachedb/migration/CustomerOrderSummaryProjection.java"
        )));
        assertTrue(Files.isRegularFile(generatedSources.resolve(
                "com/acme/cachedb/migration/CustomerOrderSummaryProjectionSchema.java"
        )));
    }

    private List<Path> writeGeneratedSources(List<MigrationScaffoldGenerator.GeneratedFile> files) throws Exception {
        Path sourceRoot = temporaryDirectory.resolve("sources");
        ArrayList<Path> javaFiles = new ArrayList<>();
        for (MigrationScaffoldGenerator.GeneratedFile file : files) {
            if (!"java".equals(file.language())) {
                continue;
            }
            Path target = sourceRoot.resolve(file.relativePath());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content());
            javaFiles.add(target);
        }
        return List.copyOf(javaFiles);
    }

    private Path compile(List<Path> javaFiles) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path classes = temporaryDirectory.resolve("classes");
        Path generated = temporaryDirectory.resolve("generated");
        Files.createDirectories(classes);
        Files.createDirectories(generated);
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    List.of(
                            "-classpath", System.getProperty("java.class.path"),
                            "-d", classes.toString(),
                            "-s", generated.toString()
                    ),
                    null,
                    fileManager.getJavaFileObjectsFromPaths(javaFiles)
            );
            task.setProcessors(List.of(new CacheEntityProcessor(), new CacheProjectionRecordProcessor()));
            assertTrue(task.call(), () -> diagnostics.getDiagnostics().toString());
        }
        return generated;
    }

    private DataSource schema() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:migration-scaffold-compilation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE customer_account (
                        customer_id BIGINT PRIMARY KEY,
                        tax_number VARCHAR(32),
                        customer_type VARCHAR(32)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE customer_order (
                        order_id BIGINT PRIMARY KEY,
                        customer_id BIGINT NOT NULL,
                        order_date TIMESTAMP NOT NULL,
                        order_amount DECIMAL(18, 2),
                        currency_code VARCHAR(3),
                        order_type VARCHAR(32),
                        CONSTRAINT fk_order_customer FOREIGN KEY (customer_id)
                            REFERENCES customer_account(customer_id)
                    )
                    """);
        }
        return dataSource;
    }

    private EntityRegistry emptyRegistry() {
        return new EntityRegistry() {
            @Override
            public <T, ID> EntityBinding<T, ID> register(
                    EntityMetadata<T, ID> metadata,
                    EntityCodec<T> codec,
                    CachePolicy cachePolicy,
                    RelationBatchLoader<T> relationBatchLoader,
                    EntityPageLoader<T> pageLoader
            ) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T, ID, P> EntityProjectionBinding<T, P, ID> registerProjection(
                    EntityMetadata<T, ID> metadata,
                    EntityProjection<T, P, ID> projection
            ) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<EntityBinding<?, ?>> find(String entityName) {
                return Optional.empty();
            }

            @Override
            public Optional<EntityProjectionBinding<?, ?, ?>> findProjection(String entityName, String projectionName) {
                return Optional.empty();
            }

            @Override
            public Collection<EntityProjectionBinding<?, ?, ?>> projections(String entityName) {
                return List.of();
            }

            @Override
            public Collection<EntityBinding<?, ?>> all() {
                return List.of();
            }
        };
    }
}

package com.reactor.cachedb.spring.data;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.processor.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpringDataProcessorContractTest {
    @TempDir Path temp;
    private record Result(boolean success, Path generated, String diagnostics) {}

    private Result compile(String methods, String bases, String options, String idType) throws IOException {
        Path root = Files.createDirectory(temp.resolve("case-" + UUID.randomUUID()));
        Path generated = Files.createDirectory(root.resolve("generated")), classes = Files.createDirectory(root.resolve("classes"));
        Path row = root.resolve("Row.java"), repo = root.resolve("Rows.java");
        Files.writeString(row, """
                package contract;
                import com.reactor.cachedb.annotations.*;
                @CacheEntity(table="f2_contract")
                public record Row(@CacheId(column="id") Long id, @CacheColumn("name_col") String name,
                  @CacheColumn("age") Integer age, @CacheColumn("active") Boolean active,
                  @CacheColumn("state") State state) {}
                enum State { OPEN, CLOSED }
                """);
        Files.writeString(repo, """
                package contract;
                import java.util.*;
                import java.util.stream.Stream;
                import org.springframework.data.domain.*;
                import com.reactor.cachedb.annotations.*;
                import com.reactor.cachedb.spring.data.*;
                interface Parent<T,ID> extends CacheDbSpringDataRepository<T,ID> {}
                interface Names<T> { List<T> findByName(String value); }
                @CacheRepository(entity=Row.class, springBean=false)
                %s
                public interface Rows extends %s { %s }
                """.formatted(options, bases.replace("$ID", idType), methods));
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Parent validation must use a full JDK 21");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            var task = compiler.getTask(null, files, diagnostics, List.of("--release", "21", "-proc:full",
                    "-classpath", System.getProperty("java.class.path"), "-s", generated.toString(), "-d", classes.toString()),
                    null, files.getJavaFileObjectsFromPaths(List.of(row, repo)));
            task.setProcessors(List.of(new CacheEntityProcessor(), new CacheRepositoryProcessor(), new CacheJsonRecordProcessor()));
            boolean success = Boolean.TRUE.equals(task.call());
            return new Result(success, generated, diagnostics.getDiagnostics().stream()
                    .map(d -> d.getKind() + ":" + d.getMessage(Locale.ROOT)).reduce("", (a, b) -> a + "\n" + b));
        }
    }

    @Test void resolvedGenericParentsFragmentsAndCrudRedeclarationsCompileToDirectCalls() throws IOException {
        var result = compile("""
                @Override <S extends Row> List<S> saveAll(Iterable<S> rows);
                List<Row> findByAgeBetweenAndActiveTrueOrderByAgeDescNameAsc(Integer lower, Integer upper);
                Window<Row> findFirst10ByAgeGreaterThanOrderByAgeAsc(Integer age, KeysetScrollPosition position);
                long deleteByActiveFalse();
                List<Row> queryByAgeGreaterThanEqual(Integer age);
                """, "Parent<Row,$ID>, Names<Row>", "", "Long");
        assertTrue(result.success(), result.diagnostics());
        String source = Files.readString(result.generated().resolve("contract/RowsCacheDbImplementation.java"));
        assertTrue(source.contains("extends com.reactor.cachedb.spring.data.AbstractCacheDbSpringDataRepository<contract.Row, java.lang.Long>"));
        assertTrue(source.contains("QueryFilter.eq(\"name_col\", p0)"));
        assertTrue(source.contains("SpringDataQueries.between(\"age\", p0, p1)"));
        assertTrue(source.contains("QueryFilter.eq(\"active\", true)"));
        assertTrue(source.contains("QuerySort.desc(\"age\")"));
        assertTrue(source.contains("QueryFilter.gte(\"age\", p0)"));
        assertTrue(source.contains("window(query, p1"));
        assertFalse(source.contains("java.lang.reflect"));
        assertFalse(source.contains("Proxy"));
        assertFalse(source.contains("PartTree"));
    }

    @Test void unsupportedQueriesFailCompilationWithActionableDiagnostics() throws IOException {
        var invalid = new LinkedHashMap<String, String>();
        invalid.put("List<Row> findByNameOrName(String a,String b);", "Or");
        invalid.put("List<Row> findByNameLike(String a);", "Unsupported keyword");
        invalid.put("Stream<Row> findByName(String a);", "Unsupported return type");
        invalid.put("List<Row> findByMissing(String a);", "Unknown derived property");
        invalid.put("List<Row> findByAgeIn(String a);", "In/NotIn");
        invalid.put("List<Row> findByAge(Long a);", "Operand type differs");
        invalid.put("List<Row> findByNameStartingWith(String a);", "StartingWith requires");
        invalid.put("Page<Row> findByAge(Integer a);", "Page/Slice requires");
        invalid.put("Window<Row> findByAge(Integer a);", "Window requires");
        invalid.put("List<Row> findByAge(Integer a,Pageable p,Sort s);", "Pageable cannot");
        invalid.put("List<Row> findTop2ByAge(Integer a,Limit limit);", "Top/First cannot");
        invalid.put("long deleteByAge(Integer a,Limit limit);", "Count/exists/delete");
        invalid.put("List<Row> findByAgeBetween(Integer a);", "Not enough operands");
        invalid.put("List<Row> findByActiveTrue(String unused);", "Every non-special parameter");
        invalid.put("long findByAge(Integer a);", "Read return type");
        invalid.put("List<Row> findTop0ByAge(Integer a);", "Top/First limit");
        invalid.put("Optional<Row> findTop2ByAge(Integer a);", "Single results");
        invalid.put("List<Row> findByName(String a, Sort first, Sort second);", "Duplicate special");
        invalid.put("List<Row> findByAge(Integer[] ages);", "Operand type differs");
        for (var entry : invalid.entrySet()) {
            var result = compile(entry.getKey(), "Parent<Row,$ID>", "", "Long");
            assertFalse(result.success(), "Unexpected success: " + entry.getKey());
            assertTrue(result.diagnostics().contains(entry.getValue()), entry.getKey() + "\n" + result.diagnostics());
        }
    }

    @Test void prefixIndexAndIdContractsAreCheckedAtCompileTime() throws IOException {
        var valid = compile("List<Row> findByNameStartingWith(String prefix);", "Parent<Row,$ID>",
                "@CacheDbSpringDataOptions(prefixIndexedColumns={\"name\"})", "Long");
        assertTrue(valid.success(), valid.diagnostics());
        String source = Files.readString(valid.generated().resolve("contract/RowsCacheDbImplementation.java"));
        assertTrue(source.contains("requirePrefixIndexes(session, prefixIndexesEnabled)"));
        assertTrue(source.contains("QueryFilter.startsWith(\"name_col\", p0)"));
        var wrongPrefix = compile("List<Row> findByAge(Integer age);", "Parent<Row,$ID>",
                "@CacheDbSpringDataOptions(prefixIndexedColumns={\"age\"})", "Long");
        assertFalse(wrongPrefix.success()); assertTrue(wrongPrefix.diagnostics().contains("indexed String property"));
        var wrongId = compile("", "Parent<Row,$ID>", "", "String");
        assertFalse(wrongId.success()); assertTrue(wrongId.diagnostics().contains("boxed @CacheId"));
    }

    @Test void allSpecialParameterReturnShapesAndNotInHaveNoRuntimeDerivation() throws IOException {
        var result = compile("""
                List<Row> findByAgeNotIn(Iterable<? extends Integer> values);
                List<Row> findByState(State state);
                List<Row> findByStateIn(List<State> states);
                Page<Row> findByActive(Boolean active,Pageable p);
                Slice<Row> readByActive(Boolean active,Pageable p);
                List<Row> getByActive(Boolean active,Limit limit);
                Optional<Row> findFirstByName(String name);
                Row getByAge(Integer age,Sort sort);
                boolean existsByActiveTrue();
                long countByAgeIsNotNull();
                """, "Parent<Row,$ID>", "", "Long");
        assertTrue(result.success(), result.diagnostics());
        String source = Files.readString(result.generated().resolve("contract/RowsCacheDbImplementation.java"));
        assertTrue(source.contains("SpringDataQueries.notIn(\"age\", p0)"));
        assertTrue(source.contains("p0 == null ? null : p0.name()"));
        assertTrue(source.contains("SpringDataQueries.enumValues(p0)"));
        assertTrue(source.contains("page(query, p1"));
        assertTrue(source.contains("slice(query, p1"));
        assertTrue(source.contains("existsMatching(query)"));
        assertTrue(source.contains("countMatching(query)"));
    }
}

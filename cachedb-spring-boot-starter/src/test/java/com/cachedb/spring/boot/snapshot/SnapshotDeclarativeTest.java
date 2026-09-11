package com.reactor.cachedb.spring.boot.snapshot;

import static org.assertj.core.api.Assertions.*;

import com.reactor.cachedb.core.model.SourceMapping;
import com.reactor.cachedb.core.model.SourceValues;
import com.reactor.cachedb.core.query.CacheField;

import org.junit.jupiter.api.Test;

import java.util.*;

class SnapshotDeclarativeTest {
    private SnapshotSource<String> records() {
        return SnapshotSource.entity(
                "records",
                new SourceMapping<>(
                        "records",
                        List.of("id", "active"),
                        row -> SourceValues.read(row, "id", String.class)));
    }

    @Test
    void valuesRemainParametersAndUnknownColumnsFail() {
        var source = records().where(SnapshotPredicate.eq("id", "x' OR 1=1 --"));
        assertThat(source.sql())
                .isEqualTo("SELECT \"id\", \"active\" FROM \"records\" WHERE \"id\" = ?");
        assertThat(source.command("PostgreSQL").parameters()).containsExactly("x' OR 1=1 --");
        assertThatThrownBy(() -> records().where(SnapshotPredicate.eq("typo", 1)))
                .hasMessageContaining("Undeclared source column");
        assertThatThrownBy(() -> records().select("id; DELETE FROM records"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void repeatedWhereUsesAndAndDoesNotChangeOriginal() {
        var original =
                records()
                        .where(
                                new CacheField<String, Boolean>("active", "active", Boolean.class),
                                true);
        var narrowed = original.where(SnapshotPredicate.eq("id", "x")).withoutPerSourceLimit();
        assertThat(original.command("PostgreSQL").parameters()).containsExactly(true);
        assertThat(narrowed.command("PostgreSQL").parameters()).containsExactly(true, "x");
        assertThat(narrowed.sql()).contains(" AND ");
        assertThat(narrowed.bounded()).isFalse();
        assertThat(original.bounded()).isTrue();
    }

    @Test
    void subqueriesKeepSqlSemanticsAndBindOrderForAllProviders() {
        var active = records().where(SnapshotPredicate.eq("active", true));
        var relation =
                SnapshotRelation.strings("links", "links", "ownerId", "targetId")
                        .where(SnapshotPredicate.in("ownerId", active.select("id")));
        var selected =
                records()
                        .where(
                                SnapshotPredicate.in("id", relation.toIds())
                                        .or(SnapshotPredicate.eq("id", "fallback")));
        for (String product : List.of("PostgreSQL", "Microsoft SQL Server", "Oracle")) {
            var command = selected.command(product);
            assertThat(command.parameters()).containsExactly(true, "fallback");
            assertThat(command.sql()).contains(" IN (SELECT ", " OR ");
            assertThat(command.sql()).doesNotContain("true", "fallback");
        }
        assertThat(selected.command("Oracle").sql())
                .contains("\"OWNERID\"", "\"ACTIVE\"", "AS \"id\"");
    }

    @Test
    void nullPredicateIsExplicitAndRawSqlHasNoTypedFilterEscapeHatch() {
        var source = records().where(SnapshotPredicate.eq("id", null));
        assertThat(source.sql()).endsWith("\"id\" IS NULL");
        assertThat(source.command("PostgreSQL").parameters()).isEmpty();
        assertThatThrownBy(
                        () ->
                                new SnapshotSource<>("raw", "SELECT id FROM records", row -> row)
                                        .where(SnapshotPredicate.eq("id", "x")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void relationsPreserveDuplicatesOrderingAndReverseWithoutAnotherRead() {
        var links = SnapshotRelation.strings("links", "links", "parent", "child");
        var rows =
                new SnapshotRows(
                        Map.of(
                                links.source(),
                                List.of(
                                        new SnapshotRelation.Edge("a", "z"),
                                        new SnapshotRelation.Edge("a", "b"),
                                        new SnapshotRelation.Edge("a", "b"))));
        assertThat(rows.lists(links).get("a")).containsExactly("b", "b", "z");
        assertThat(rows.lists(links).get("missing")).isEmpty();
        assertThat(rows.lists(links.reverse()).get("b")).containsExactly("a", "a");
        assertThat(links.reverse().source()).isSameAs(links.source());
        assertThatThrownBy(() -> rows.lists(links).get("a").add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void membershipDeduplicatesOnlyMembershipAndEmptyAllIsTrue() {
        var links = SnapshotRelation.strings("links", "links", "parent", "child");
        var rows =
                new SnapshotRows(
                        Map.of(
                                links.source(),
                                List.of(
                                        new SnapshotRelation.Edge("a", "x"),
                                        new SnapshotRelation.Edge("a", "x"))));
        var members = rows.membership(links);
        assertThat(members.containsAny("a", List.of("missing", "x"))).isTrue();
        assertThat(members.containsAll("a", List.of("x", "x"))).isTrue();
        assertThat(members.containsAll("absent", List.of())).isTrue();
        assertThat(members.containsAny("absent", List.of())).isFalse();
        assertThat(members.containsAll("a", List.of("x", "y"))).isFalse();
        assertThat(rows.lists(links).get("a")).hasSize(2);
    }

    @Test
    void nullAndDuplicateIdentitiesStillFailAndUnregisteredSourcesAreNotLoaded() {
        var source = records();
        var duplicate = new SnapshotRows(Map.of(source, List.of("a", "a")));
        assertThatThrownBy(() -> duplicate.unique(source, x -> x))
                .hasMessageContaining("Duplicate identity");
        var links = SnapshotRelation.strings("links", "links", "parent", "child");
        assertThatThrownBy(() -> duplicate.lists(links))
                .hasMessageContaining("Undeclared snapshot source");
        var missing =
                new SnapshotRows(
                        Map.of(links.source(), List.of(new SnapshotRelation.Edge("a", null))));
        assertThatThrownBy(() -> missing.membership(links))
                .hasMessageContaining("Null snapshot relation target");
        assertThatThrownBy(() -> missing.lists(links))
                .hasMessageContaining("Null snapshot relation target");
    }

    @Test
    void distributeVisitsEachValueOnceAndDeduplicatesOnlyDestinationKeys() {
        record Value(String id, List<String> branches) {}
        var first = new Value("1", List.of("b", "a", "a"));
        var second = new Value("2", List.of("a"));
        var result = SnapshotLists.distribute(List.of(first, second), Value::branches);
        assertThat(result.get("a")).containsExactly(first, second);
        assertThat(result.get("b")).containsExactly(first);
        assertThat(first.branches()).containsExactly("b", "a", "a");
        assertThat(result.get("empty")).isEmpty();
    }

    @Test
    void generatedScalarConversionsPreserveNullAndRejectDataLoss() {
        Map<String, Object> row = new HashMap<>();
        row.put("optional", null);
        row.put("number", new java.math.BigDecimal("2"));
        row.put("flag", new java.math.BigDecimal("1"));
        assertThat(SourceValues.read(row, "optional", Double.class)).isNull();
        assertThat(SourceValues.read(row, "number", Integer.class)).isEqualTo(2);
        assertThat(SourceValues.read(row, "flag", Boolean.class)).isTrue();
        assertThatThrownBy(() -> SourceValues.read(row, "absent", String.class))
                .hasMessageContaining("Missing source column");
        assertThatThrownBy(() -> SourceValues.read(row, "number", Boolean.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceValues.read(Map.of("n", 2.5), "n", Integer.class))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void realJdbcExecutesTypedSemijoinAndNeverLoadsIdsIntoAnotherQuery() throws Exception {
        var data = new org.h2.jdbcx.JdbcDataSource();
        data.setURL("jdbc:h2:mem:typed_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = data.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE \"records\"(\"id\" VARCHAR, \"active\" BOOLEAN)");
            statement.execute("CREATE TABLE \"links\"(\"owner\" VARCHAR, \"target\" VARCHAR)");
            statement.execute("INSERT INTO \"records\" VALUES ('a', true), ('b', false)");
            statement.execute("INSERT INTO \"links\" VALUES ('a','x'), ('b','y'), ('a','x')");
        }
        var active = records().where(SnapshotPredicate.eq("active", true));
        var links =
                SnapshotRelation.strings("links", "links", "owner", "target")
                        .where(SnapshotPredicate.in("owner", active.select("id")));
        var plan =
                new SnapshotPlan<>(
                        "typed",
                        active,
                        x -> x,
                        SnapshotPlan.inputs(active, links),
                        String.class,
                        rows -> root -> rows.lists(links).get(root));
        var rows = SnapshotJdbcReader.read(data, plan, SnapshotSettings.defaults(), () -> {});
        assertThat(rows.get(active)).containsExactly("a");
        assertThat(rows.lists(links).get("a")).containsExactly("x", "x");
        assertThat(rows.lists(links).get("b")).isEmpty();
        try (var connection = data.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            statement.execute("SHUTDOWN");
        }
    }
}

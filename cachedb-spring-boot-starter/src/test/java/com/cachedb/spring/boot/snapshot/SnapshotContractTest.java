package com.reactor.cachedb.spring.boot.snapshot;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.file.*;
import java.util.*;

class SnapshotContractTest {
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(
            SnapshotProperties.class)
    static class PropertyBinding {}

    @Test
    void unknownOptionsFailInsteadOfSilentlyUsingDefaults() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(PropertyBinding.class)
                .withPropertyValues("cachedb.snapshots.jobs.catalog.batch-rowz=10")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void propertiesBindNamedJobsWithDefaults() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(PropertyBinding.class)
                .withPropertyValues("cachedb.snapshots.jobs.catalog.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var settings =
                                    context.getBean(SnapshotProperties.class).jobs().get("catalog");
                            assertThat(settings.enabled()).isFalse();
                            assertThat(settings.retention()).hasHours(1);
                        });
    }

    @Test
    void readRetriesAgainstNewGenerationWhenCleanupRaces() {
        var redis = org.mockito.Mockito.mock(redis.clients.jedis.JedisPooled.class);
        String old = "00000000-0000-0000-0000-000000000001";
        String fresh = "00000000-0000-0000-0000-000000000002";
        org.mockito.Mockito.when(redis.get("test:active"))
                .thenReturn(old + ":1000", fresh + ":2000", fresh + ":2000");
        org.mockito.Mockito.when(redis.hget("test:data:" + fresh, "root")).thenReturn("[]");
        var result = new RedisSnapshotRepository(redis, "test").findById("root").orElseThrow();
        assertThat(result.generation()).isEqualTo(fresh);
        assertThat(result.refreshedAt()).isEqualTo(2000);
    }

    @Test
    void corruptGenerationIsRejectedAsInvalidCacheData() {
        var redis = org.mockito.Mockito.mock(redis.clients.jedis.JedisPooled.class);
        org.mockito.Mockito.when(redis.get("test:active")).thenReturn("corrupt");
        assertThatThrownBy(() -> new RedisSnapshotRepository(redis, "test").findById("root"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mappedPlanReusesTheBusinessFactoryOnce() {
        var root =
                new SnapshotSource<>(
                        "roots", "SELECT id FROM roots", row -> row.get("id").toString());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var plan =
                new SnapshotPlan<>(
                        "base",
                        root,
                        id -> id,
                        List.of(root),
                        String.class,
                        rows -> {
                            calls.incrementAndGet();
                            return id -> List.of(id + "-value");
                        });
        var mapped =
                plan.map(
                        "summary",
                        Integer.class,
                        (id, values) -> List.of(values.iterator().next().length()));
        var mapper = mapped.projection().apply(new SnapshotRows(Map.of(root, List.of("a", "b"))));
        assertThat(mapper.apply("a")).containsExactly(7);
        assertThat(mapper.apply("b")).containsExactly(7);
        assertThat(calls.get()).isEqualTo(1);
    }

    @TempDir Path directory;

    static SnapshotSettings settings(Map<String, Object> values) {
        Map<String, Object> properties = new HashMap<>();
        values.forEach((key, value) -> properties.put("job." + key, value));
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("job", Bindable.of(SnapshotSettings.class))
                .orElse(SnapshotSettings.defaults());
    }

    @Test
    void binderDefaultsAndOverrides() {
        var settings =
                settings(
                        Map.of(
                                "batch-rows",
                                17,
                                "batch-target-size",
                                "2MB",
                                "spool-directory",
                                directory.toString()));
        assertThat(settings.batchRows()).isEqualTo(17);
        assertThat(settings.maxAge()).hasMinutes(30);
        assertThat(settings.warnAge()).hasMinutes(3);
        assertThat(settings.directory()).isEqualTo(directory);
    }

    @Test
    void invalidResourceAndTimingSettingsFailAtStartup() {
        for (var values :
                List.of(
                        Map.<String, Object>of("batch-rows", 0),
                        Map.<String, Object>of("batch-target-size", "0B"),
                        Map.<String, Object>of("warn-age", "PT40M"),
                        Map.<String, Object>of("retention", "PT10M"),
                        Map.<String, Object>of("lease-duration", "PT1S"),
                        Map.<String, Object>of("max-source-rows", Integer.MAX_VALUE))) {
            assertThatThrownBy(() -> settings(values)).isInstanceOf(BindException.class);
        }
    }

    @Test
    void onlyTrustedSelectDefinitionsAreAccepted() {
        assertThatThrownBy(() -> new SnapshotSource<>("rows", "DELETE FROM records", row -> row))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new SnapshotSource<>(
                                        "rows",
                                        "SELECT id FROM records; DELETE FROM records",
                                        row -> row))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void repeatedSourcesAndRootIdentityAreRejected() {
        var root = new SnapshotSource<>("roots", "SELECT id FROM roots", row -> row);
        assertThatThrownBy(
                        () ->
                                new SnapshotPlan<>(
                                        "test",
                                        root,
                                        Object::toString,
                                        List.of(root, root),
                                        String.class,
                                        rows -> value -> List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        var rows = new SnapshotRows(Map.of(root, List.of(Map.of("id", "x"), Map.of("id", "x"))));
        assertThatThrownBy(() -> rows.unique(root, value -> value.get("id")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void diskSpoolPreservesUtf8AndLargeRowsAndCleansUp() throws Exception {
        var root =
                new SnapshotSource<>(
                        "roots", "SELECT id FROM roots", row -> (String) row.get("id"));
        var plan =
                new SnapshotPlan<>(
                        "test",
                        root,
                        id -> id,
                        List.of(root),
                        String.class,
                        rows -> id -> List.of("\u00fc\u011f".repeat(150000)));
        var mapper = new ObjectMapper();
        try (var spool = new SnapshotSpool(directory)) {
            spool.prepare(
                    plan, new SnapshotRows(Map.of(root, List.of("a", "b"))), mapper, () -> {});
            assertThat(spool.entries()).hasSize(2);
            assertThat(spool.entries().get(0).bytes()).isGreaterThan(256 * 1024);
            assertThat(
                            mapper.readTree(spool.read(spool.entries().get(1), () -> {}))
                                    .get(0)
                                    .asText())
                    .isEqualTo("\u00fc\u011f".repeat(150000));
        }
        try (var files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void spoolRejectsDuplicateRootsAndCleansAfterProjectionFailure() throws Exception {
        var root =
                new SnapshotSource<>(
                        "roots", "SELECT id FROM roots", row -> (String) row.get("id"));
        var plan =
                new SnapshotPlan<>(
                        "test",
                        root,
                        id -> id,
                        List.of(root),
                        String.class,
                        rows -> id -> List.of());
        try (var spool = new SnapshotSpool(directory)) {
            assertThatThrownBy(
                            () ->
                                    spool.prepare(
                                            plan,
                                            new SnapshotRows(Map.of(root, List.of("a", "a"))),
                                            new ObjectMapper(),
                                            () -> {}))
                    .hasMessageContaining("duplicate");
        }
        try (var files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }
}

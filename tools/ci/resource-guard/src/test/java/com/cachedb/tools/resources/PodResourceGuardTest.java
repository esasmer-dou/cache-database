package com.reactor.cachedb.tools.resources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.reactor.cachedb.tools.resources.PodResourceGuard.*;

final class PodResourceGuardTest {
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneOffset.UTC);
    private static final String[] QUALIFY = {"qualify", "--container", "maven", "--redis", "redis",
            "--container-cpu", "1", "--pod-cpu", "2", "--child", "1", "--memory", "2Gi", "--fresh"};
    private static final String POD = """
            {"metadata":{"uid":"fixture-uid-1","name":"synthetic"},
             "spec":{"activeDeadlineSeconds":21600,"containers":[
               {"name":"maven","env":[{"name":"SECRET","value":"NEVER_EXPORT_THIS"}],
                "resources":{"requests":{"cpu":"1","memory":"1Gi"},
                  "limits":{"cpu":"1","memory":"2Gi","ephemeral-storage":"3Gi"}},
                "volumeMounts":[{"name":"work","mountPath":"/workspace"}]},
               {"name":"redis","resources":{"requests":{"cpu":"200m","memory":"64Mi"},
                  "limits":{"cpu":"1","memory":"256Mi"}}}],
               "volumes":[{"name":"work","emptyDir":{"sizeLimit":"2Gi"}},
                 {"name":"credentials","secret":{"secretName":"NEVER_EXPORT_THIS"}}]},
             "status":{"phase":"Running","startTime":"2026-10-28T11:55:00Z"}}
            """;
    @TempDir Path temporary;
    private final List<String> controls = new ArrayList<>();

    @Test void resourceContractAndFailClosedParser() throws Exception {
        check("approved-resources-and-redaction", () -> {
            var result = run(POD, QUALIFY);
            assertEquals("fixture-uid-1", result.get("uid"));
            assertEquals(1000, number(result, "benchmarkContainerCpuLimitMilli"));
            assertEquals(2000, number(result, "regularContainerCpuLimitSumMilli"));
            assertEquals(1200, number(result, "regularContainerCpuRequestSumMilli"));
            assertEquals(2048, number(result, "workspaceEmptyDirMiB"));
            assertEquals(3072, number(result, "benchmarkEphemeralStorageLimitMiB"));
            assertFalse(BoundedJson.write(result).contains("NEVER_EXPORT_THIS"));
            assertFalse(BoundedJson.write(result).contains("secretName"));
        });
        check("millicore-aliases", () -> {
            var source = changed("1000m", "spec", "containers", 0, "resources", "limits", "cpu");
            replace(source, "1000m", "spec", "containers", 0, "resources", "requests", "cpu");
            assertEquals(1000, number(run(BoundedJson.write(source), QUALIFY), "benchmarkContainerCpuLimitMilli"));
        });
        check("two-cpu-control", () -> {
            var source = changed("2", "spec", "containers", 0, "resources", "limits", "cpu");
            replace(source, "2", "spec", "containers", 0, "resources", "requests", "cpu");
            var args = QUALIFY.clone(); args[6] = "2"; args[8] = "3"; args[10] = "2";
            assertEquals(3000, number(run(BoundedJson.write(source), args), "regularContainerCpuLimitSumMilli"));
        });
        rejectPod("missing-workspace-size", null, "spec", "volumes", 0, "emptyDir", "sizeLimit");
        rejectPod("insufficient-workspace", "2047Mi", "spec", "volumes", 0, "emptyDir", "sizeLimit");
        rejectPod("invalid-workspace-units", "2G", "spec", "volumes", 0, "emptyDir", "sizeLimit");
        rejectPod("ram-backed-workspace-rejected", "Memory", "spec", "volumes", 0, "emptyDir", "medium");
        for (String path : List.of("/workspace/checkout", "/elsewhere/../workspace/checkout", "/workspace/./checkout")) {
            check("nested-workspace-mount-rejected-" + controls.size(), () -> {
                var pod = parse(POD);
                var container = object(list(object(pod.get("spec")).get("containers")).getFirst());
                list(container.get("volumeMounts")).add(map("name", "nested", "mountPath", path));
                list(object(pod.get("spec")).get("volumes")).add(map("name", "nested", "persistentVolumeClaim", map("claimName", "unapproved")));
                assertThrows(IllegalArgumentException.class, () -> run(BoundedJson.write(pod), QUALIFY));
            });
        }
        check("unrelated-mount-does-not-hide-workspace", () -> {
            var pod = parse(POD);
            var container = object(list(object(pod.get("spec")).get("containers")).getFirst());
            list(container.get("volumeMounts")).add(map("name", "credentials", "mountPath", "/run/credentials"));
            run(BoundedJson.write(pod), QUALIFY);
        });
        rejectPod("insufficient-ephemeral-limit", "3071Mi", "spec", "containers", 0, "resources", "limits", "ephemeral-storage");
        rejectPod("missing-ephemeral-limit", null, "spec", "containers", 0, "resources", "limits", "ephemeral-storage");
        rejectPod("invalid-cpu-quantity", "0.5", "spec", "containers", 0, "resources", "limits", "cpu");
        rejectPod("cpu-limit-approval-mismatch", "2", "spec", "containers", 0, "resources", "limits", "cpu");
        rejectPod("cpu-request-approval-mismatch", "500m", "spec", "containers", 0, "resources", "requests", "cpu");
        rejectPod("redis-cpu-limit-mismatch", "2", "spec", "containers", 1, "resources", "limits", "cpu");
        rejectPod("redis-cpu-request-mismatch", "201m", "spec", "containers", 1, "resources", "requests", "cpu");
        rejectPod("memory-approval-mismatch", "3Gi", "spec", "containers", 0, "resources", "limits", "memory");
        rejectPod("init-container-rejected", List.of(map("name", "sidecar", "resources", Map.of())), "spec", "initContainers");
        rejectPod("pod-overhead-rejected", map("cpu", "1m"), "spec", "overhead");
        rejectPod("pod-level-budget-rejected", map("limits", map("cpu", "2")), "spec", "resources");
        check("extra-regular-container-rejected", () -> {
            var pod = parse(POD); list(object(pod.get("spec")).get("containers")).add(map("name", "other", "resources", Map.of()));
            assertThrows(IllegalArgumentException.class, () -> run(BoundedJson.write(pod), QUALIFY));
        });
        check("same-pod-and-allocation", () -> {
            var before = BoundedJson.write(run(POD, QUALIFY));
            run(POD, "same", "--container", "maven", "--redis", "redis", "--expected", before);
            run(before, "compare", "--expected", before);
        });
        for (var drift : List.of(
                new Drift("uid-drift", "fixture-uid-2", List.of("metadata", "uid")),
                new Drift("cpu-drift", "2", List.of("spec", "containers", 0, "resources", "limits", "cpu")),
                new Drift("memory-drift", "3Gi", List.of("spec", "containers", 0, "resources", "limits", "memory")),
                new Drift("redis-request-drift", "300m", List.of("spec", "containers", 1, "resources", "requests", "cpu")),
                new Drift("workspace-drift", "3Gi", List.of("spec", "volumes", 0, "emptyDir", "sizeLimit")),
                new Drift("ephemeral-drift", "4Gi", List.of("spec", "containers", 0, "resources", "limits", "ephemeral-storage")))) {
            check(drift.name, () -> {
                String before = BoundedJson.write(run(POD, QUALIFY));
                String source = BoundedJson.write(changed(drift.value, drift.path.toArray()));
                assertThrows(IllegalArgumentException.class, () -> run(source, "same", "--container", "maven", "--redis", "redis", "--expected", before));
                String projected = BoundedJson.write(run(source, "snapshot", "--container", "maven", "--redis", "redis"));
                assertThrows(IllegalArgumentException.class, () -> run(projected, "compare", "--expected", before));
            });
        }
        rejectPod("stale-pod", "2026-10-28T11:29:59Z", "status", "startTime");
        rejectPod("future-pod", "2026-10-28T12:00:01Z", "status", "startTime");
        rejectPod("non-running-pod", "Pending", "status", "phase");
        rejectPod("missing-deadline", null, "spec", "activeDeadlineSeconds");
        rejectPod("wrong-deadline", 21601, "spec", "activeDeadlineSeconds");
        rejectPod("fractional-deadline", 21600.5, "spec", "activeDeadlineSeconds");
        rejectPod("invalid-timestamp", "not-a-time", "status", "startTime");
        rejectPod("unsafe-uid", "one\ninjected=value", "metadata", "uid");
        check("freshness-boundary-inclusive", () -> run(BoundedJson.write(changed("2026-10-28T11:30:00Z", "status", "startTime")), QUALIFY));
        check("equivalent-allocation-order", () -> {
            var pod = parse(POD); Collections.reverse(list(object(pod.get("spec")).get("containers")));
            Collections.reverse(list(object(pod.get("spec")).get("volumes")));
            assertEquals(run(POD, QUALIFY).get("allocation"), run(BoundedJson.write(pod), QUALIFY).get("allocation"));
        });
        check("github-output-is-single-line-and-redacted", () -> {
            var path = temporary.resolve("github-output");
            var args = Arrays.copyOf(QUALIFY, QUALIFY.length + 2);
            args[QUALIFY.length] = "--github-output"; args[QUALIFY.length + 1] = path.toString();
            run(POD, args);
            String text = Files.readString(path);
            assertEquals(7, text.lines().count());
            assertTrue(text.contains("poduid=fixture-uid-1\n"));
            assertFalse(text.contains("NEVER_EXPORT_THIS"));
        });
        check("invalid-cli-options", () -> {
            for (String[] args : List.of(new String[]{"unknown"}, new String[]{"snapshot"},
                    new String[]{"snapshot", "--unknown", "value"},
                    new String[]{"snapshot", "--container", "maven", "--container", "maven", "--redis", "redis"},
                    new String[]{"snapshot", "--container", "maven", "--redis"}))
                assertThrows(IllegalArgumentException.class, () -> run(POD, args));
        });
        check("unsupported-explicit-controls", () -> {
            for (int position : new int[]{6, 8, 10, 12}) {
                var args = QUALIFY.clone(); args[position] = position == 12 ? "1Gi" : "5";
                assertThrows(IllegalArgumentException.class, () -> run(POD, args));
            }
        });
        check("missing-benchmark-and-duplicate-volume", () -> {
            assertThrows(IllegalArgumentException.class, () -> run(POD, "qualify", "--container", "absent", "--redis", "redis",
                    "--container-cpu", "1", "--pod-cpu", "2", "--child", "1", "--memory", "2Gi"));
            var pod = parse(POD); list(object(pod.get("spec")).get("volumes")).add(map("name", "work", "emptyDir", map("sizeLimit", "2Gi")));
            assertThrows(IllegalArgumentException.class, () -> run(BoundedJson.write(pod), QUALIFY));
        });
        check("quantity-overflow-is-bounded-and-sanitized", () -> {
            assertEquals("CPU quantity exceeds evidence bound", assertThrows(IllegalArgumentException.class, () -> cpu("9".repeat(100))).getMessage());
            assertEquals("Storage quantity exceeds evidence bound", assertThrows(IllegalArgumentException.class, () -> storage("9".repeat(100) + "Gi")).getMessage());
            assertThrows(IllegalArgumentException.class, () -> cpu("64001m"));
            assertThrows(IllegalArgumentException.class, () -> storage("1048577Mi"));
        });
        for (String json : List.of("{\"a\":null,\"a\":1}", "{} {}", "{\"a\":NaN}", "{\"a\":1e10000}",
                "{\"a\":01}", "{/*comment*/\"a\":1}", "{\"a\":1,}", "{a:1}", "[\"\\ud800\"]", "[\"\\udc00\"]")) {
            check("strict-json-negative-" + controls.size(), () -> assertThrows(IOException.class, () -> BoundedJson.read(json)));
        }
        check("utf8-round-trip-and-unicode", () -> {
            String unicode = "T\u00fcrk\u00e7e \u0131\u011f\u015f\u00f6\u00fc \ud83d\ude80";
            String encoded = BoundedJson.write(map("text", unicode, "nil", null));
            var decoded = parse(encoded); assertEquals(unicode, decoded.get("text")); assertTrue(decoded.containsKey("nil"));
        });
        check("invalid-utf8-rejected", () -> assertThrows(IOException.class,
                () -> BoundedJson.read(new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28}))));
        check("input-byte-budget", () -> assertThrows(IOException.class,
                () -> BoundedJson.read(new ByteArrayInputStream(new byte[BoundedJson.MAX_INPUT_BYTES + 1]))));
        check("input-string-budget", () -> assertThrows(IOException.class, () -> BoundedJson.read(" ".repeat(BoundedJson.MAX_INPUT_BYTES + 1))));
        check("depth-budget", () -> assertThrows(IOException.class, () -> BoundedJson.read("[".repeat(33) + "0" + "]".repeat(33))));
        check("node-budget", () -> assertThrows(IOException.class, () -> BoundedJson.read("[" + "0,".repeat(20_000) + "0]")));
        check("token-budget", () -> assertThrows(IOException.class, () -> BoundedJson.read("[\"" + "a".repeat(65_537) + "\"]")));
        check("output-character-budget", () -> assertThrows(IOException.class, () -> BoundedJson.write("a".repeat(65_536))));
        check("output-byte-budget", () -> assertThrows(IOException.class, () -> BoundedJson.write("\u0131".repeat(40_000))));
        check("snapshot-oversize-rejected-before-output", () -> {
            String pod = POD.replace("\"ephemeral-storage\":\"3Gi\"", "\"ephemeral-storage\":\"3Gi\",\"extended\":\"" + "a".repeat(65_536) + "\"");
            assertThrows(IOException.class, () -> run(pod, QUALIFY));
        });
        check("compare-malformed-expected-rejected", () -> {
            var snapshot = BoundedJson.write(run(POD, QUALIFY));
            assertThrows(IllegalArgumentException.class, () -> run(snapshot, "compare", "--expected", "{}"));
            assertThrows(IOException.class, () -> run(snapshot, "compare", "--expected", "{} {}"));
        });
        Path evidence = Path.of(System.getProperty("cachedb.resource.evidence"));
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("approved-snapshot.json"), BoundedJson.write(run(POD, QUALIFY)), StandardOpenOption.CREATE_NEW);
        Files.writeString(evidence.resolve("summary.json"), BoundedJson.write(map("result", "PASS", "controls", controls,
                "syntheticOnly", true, "benchmarkExecuted", false, "actualBenchmarkQualified", false)), StandardOpenOption.CREATE_NEW);
        System.out.println("RESOURCE_JAVA_CONTROLS_PASS=" + controls.size() + "; SYNTHETIC_ONLY; NO_BENCHMARK_ACCEPTANCE");
    }

    private void check(String name, Checked action) throws Exception { action.run(); controls.add(name); }
    private void rejectPod(String name, Object value, Object... path) throws Exception {
        check(name, () -> assertThrows(IllegalArgumentException.class, () -> run(BoundedJson.write(changed(value, path)), QUALIFY)));
    }
    private Map<String, Object> changed(Object value, Object... path) throws IOException { var pod = parse(POD); replace(pod, value, path); return pod; }
    private static void replace(Map<String, Object> pod, Object value, Object... path) {
        Object node = pod;
        for (int i = 0; i < path.length - 1; i++) node = path[i] instanceof Integer index ? list(node).get(index) : object(node).get(path[i]);
        object(node).put((String) path[path.length - 1], value);
    }
    private static Map<String, Object> parse(String source) throws IOException { return object(BoundedJson.read(source)); }
    private static long number(Map<String, Object> map, String key) { return ((Number) map.get(key)).longValue(); }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) { return (List<Object>) value; }
    private static Map<String, Object> run(String pod, String... args) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var stream = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            execute(args, new ByteArrayInputStream(pod.getBytes(StandardCharsets.UTF_8)), stream, NOW);
        }
        return parse(bytes.toString(StandardCharsets.UTF_8));
    }
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private record Drift(String name, Object value, List<Object> path) {}
}

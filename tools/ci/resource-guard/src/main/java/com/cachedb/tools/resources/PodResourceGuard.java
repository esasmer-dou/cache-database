package com.reactor.cachedb.tools.resources;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Non-secret pod allocation evidence, independent of jq and of the SDK runtime. */
public final class PodResourceGuard {
    private PodResourceGuard() {}

    public static void main(String[] args) {
        try { execute(args, System.in, System.out, Clock.systemUTC()); }
        catch (IllegalArgumentException failure) {
            System.err.println("Resource validation failed: " + failure.getMessage()); System.exit(2);
        } catch (IOException | RuntimeException failure) {
            System.err.println("Resource validation failed: invalid or unavailable document/output"); System.exit(2);
        }
    }

    static void execute(String[] args, InputStream input, PrintStream output, Clock clock) throws IOException {
        if (args.length < 1 || args.length > 24) throw fail("Invalid resource command");
        String command = args[0];
        Set<String> allowed = switch (command) {
            case "qualify" -> Set.of("container", "redis", "container-cpu", "pod-cpu", "child", "memory", "fresh", "github-output");
            case "same" -> Set.of("container", "redis", "expected");
            case "snapshot" -> Set.of("container", "redis");
            case "compare" -> Set.of("expected");
            default -> throw fail("Invalid resource command");
        };
        var options = new HashMap<String, String>();
        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            if (!flag.startsWith("--") || !allowed.contains(flag.substring(2))) throw fail("Unknown resource option");
            String key = flag.substring(2), value = "true";
            if (!key.equals("fresh")) {
                if (++i == args.length || args[i].length() > BoundedJson.MAX_OUTPUT_BYTES) throw fail("Invalid resource option value");
                value = args[i];
            }
            if (options.putIfAbsent(key, value) != null) throw fail("Duplicate resource option");
        }
        var source = object(BoundedJson.read(input));
        var snapshot = command.equals("compare") ? source
                : snapshot(source, required(options, "container"), required(options, "redis"));
        switch (command) {
            case "qualify" -> {
                qualify(snapshot, required(options, "container-cpu"), required(options, "pod-cpu"),
                        required(options, "child"), required(options, "memory"));
                if (options.containsKey("fresh")) fresh(snapshot, clock);
            }
            case "same", "compare" -> sameAllocation(snapshot, object(BoundedJson.read(required(options, "expected"))));
            default -> { }
        }
        String result = BoundedJson.write(snapshot);
        if (options.containsKey("github-output")) githubOutput(Path.of(options.get("github-output")), snapshot, result);
        output.println(result);
    }

    static Map<String, Object> snapshot(Map<String, Object> pod, String name, String redis) {
        containerName(name); containerName(redis);
        var spec = object(pod.get("spec"));
        var containers = new ArrayList<Map<String, Object>>();
        for (Object raw : array(spec.get("containers"))) {
            var container = object(raw);
            var mounts = new ArrayList<Map<String, Object>>();
            for (Object value : optionalArray(container.get("volumeMounts"))) {
                var mount = object(value);
                if (workspaceMount(string(mount.get("mountPath"))))
                    mounts.add(fields(mount, "name", "mountPath", "subPath", "subPathExpr", "readOnly"));
            }
            var projected = fields(container, "name");
            projected.put("resources", optionalObject(container.get("resources")));
            projected.put("workspaceMounts", mounts);
            containers.add(projected);
        }
        containers.sort(Comparator.comparing(c -> string(c.get("name"))));
        var benchmark = named(containers, name);
        var server = named(containers, redis);
        var resources = optionalObject(benchmark.get("resources"));
        var serverResources = optionalObject(server.get("resources"));
        var volumes = new ArrayList<Map<String, Object>>();
        for (Object raw : optionalArray(spec.get("volumes"))) {
            var volume = object(raw);
            if (volume.containsKey("emptyDir")) volumes.add(fields(volume, "name", "emptyDir"));
        }
        volumes.sort(Comparator.comparing(v -> string(v.get("name"))));
        var mounts = optionalArray(benchmark.get("workspaceMounts"));
        Object work = mounts.size() == 1 && "/workspace".equals(object(mounts.getFirst()).get("mountPath"))
                ? object(mounts.getFirst()).get("name") : null;
        var workDir = optionalObject(named(volumes, work).get("emptyDir"));
        var init = new ArrayList<Map<String, Object>>();
        for (Object raw : optionalArray(spec.get("initContainers"))) init.add(fields(object(raw), "name", "resources", "restartPolicy"));
        var metadata = object(pod.get("metadata")); var status = object(pod.get("status"));
        var result = fields(metadata, "uid");
        result.put("phase", status.get("phase")); result.put("startTime", status.get("startTime"));
        result.put("deadlineSeconds", spec.get("activeDeadlineSeconds"));
        result.put("benchmarkContainer", name); result.put("redisContainer", redis);
        result.put("resources", resources); result.put("redisResources", serverResources);
        result.put("benchmarkContainerCpuLimitMilli", quantity(resources, "limits", "cpu", true));
        result.put("benchmarkContainerCpuRequestMilli", quantity(resources, "requests", "cpu", true));
        result.put("redisContainerCpuLimitMilli", quantity(serverResources, "limits", "cpu", true));
        result.put("redisContainerCpuRequestMilli", quantity(serverResources, "requests", "cpu", true));
        result.put("regularContainerCpuLimitSumMilli", cpuSum(containers, "limits"));
        result.put("regularContainerCpuRequestSumMilli", cpuSum(containers, "requests"));
        result.put("workspaceVolume", work); result.put("workspaceEmptyDirMiB", storageOrNull(workDir.get("sizeLimit")));
        result.put("benchmarkEphemeralStorageLimitMiB", quantity(resources, "limits", "ephemeral-storage", false));
        result.put("allocation", map("containers", containers, "emptyDirVolumes", volumes, "initContainers", init,
                "overhead", optionalObject(spec.get("overhead")), "podLevelResources", optionalObject(spec.get("resources"))));
        return result;
    }

    static void qualify(Map<String, Object> snapshot, String containerCpu, String podCpu, String child, String memory) {
        long container = cpu(containerCpu), total = cpu(podCpu);
        if ((!child.equals("1") && !child.equals("2")) || (container != 1000 && container != 2000) || total < 1000 || total > 4000)
            throw fail("Unsupported explicit CPU control");
        var allocation = object(snapshot.get("allocation"));
        if (array(allocation.get("containers")).size() != 2 || snapshot.get("benchmarkContainer").equals(snapshot.get("redisContainer"))
                || !array(allocation.get("initContainers")).isEmpty() || !object(allocation.get("overhead")).isEmpty()
                || !object(allocation.get("podLevelResources")).isEmpty())
            throw fail("CPU contract requires the two approved regular containers without additional pod CPU budgets");
        equalNumber(snapshot.get("benchmarkContainerCpuLimitMilli"), container, "Maven CPU limit differs from approval");
        equalNumber(snapshot.get("benchmarkContainerCpuRequestMilli"), container, "Maven CPU request differs from approval");
        equalNumber(snapshot.get("redisContainerCpuLimitMilli"), 1000, "Redis CPU limit must be 1");
        equalNumber(snapshot.get("redisContainerCpuRequestMilli"), 200, "Redis CPU request must be 200m");
        equalNumber(snapshot.get("regularContainerCpuLimitSumMilli"), total, "Pod CPU-limit sum differs from approval");
        if (storage(memory) < 2048 || !memory.equals(optionalObject(object(snapshot.get("resources")).get("limits")).get("memory")))
            throw fail("Maven memory limit differs from approval or is below 2Gi");
        minimum(snapshot.get("workspaceEmptyDirMiB"), 2048, "PRECONDITION_INSUFFICIENT_STORAGE: /workspace emptyDir must be at least 2Gi");
        var workspace = optionalObject(named(castObjects(array(allocation.get("emptyDirVolumes"))), snapshot.get("workspaceVolume")).get("emptyDir"));
        if (workspace.get("medium") != null && !"".equals(workspace.get("medium")))
            throw fail("PRECONDITION_INSUFFICIENT_STORAGE: /workspace must use disk-backed emptyDir, not RAM");
        minimum(snapshot.get("benchmarkEphemeralStorageLimitMiB"), 3072, "PRECONDITION_INSUFFICIENT_STORAGE: Maven ephemeral-storage must be at least 3Gi");
        var resources = object(snapshot.get("resources")); var redisResources = object(snapshot.get("redisResources"));
        snapshot.put("controls", map("childActiveProcessorCount", Integer.valueOf(child),
                "approvedBenchmarkContainerCpuLimit", containerCpu, "actualBenchmarkContainerCpuLimit", nested(resources, "limits", "cpu"),
                "actualBenchmarkContainerCpuRequest", nested(resources, "requests", "cpu"),
                "approvedPodRegularContainerCpuLimitSum", podCpu, "actualPodRegularContainerCpuLimitSumMilli", total,
                "actualRedisContainerCpuLimit", nested(redisResources, "limits", "cpu"),
                "actualRedisContainerCpuRequest", nested(redisResources, "requests", "cpu"),
                "minimumWorkspaceEmptyDirMiB", 2048, "minimumBenchmarkEphemeralStorageLimitMiB", 3072));
    }

    static void sameAllocation(Map<String, Object> current, Map<String, Object> before) {
        validUid(current.get("uid")); validUid(before.get("uid"));
        var currentAllocation = object(current.get("allocation")); var priorAllocation = object(before.get("allocation"));
        for (var allocation : List.of(currentAllocation, priorAllocation)) {
            if (!allocation.keySet().equals(Set.of("containers", "emptyDirVolumes", "initContainers", "overhead", "podLevelResources"))
                    || array(allocation.get("containers")).size() != 2) throw fail("Invalid allocation evidence");
        }
        if (!current.get("uid").equals(before.get("uid")) || !currentAllocation.equals(priorAllocation))
            throw fail("Pod UID or CPU/memory/storage allocation drifted; raw evidence is not qualified");
    }

    static void fresh(Map<String, Object> snapshot, Clock clock) {
        validUid(snapshot.get("uid"));
        if (!"Running".equals(snapshot.get("phase"))) throw fail("Benchmark pod must be Running");
        equalNumber(snapshot.get("deadlineSeconds"), 21600, "A six-hour pod deadline is required");
        Instant started;
        try { started = Instant.parse(string(snapshot.get("startTime"))); }
        catch (RuntimeException invalid) { throw fail("Invalid benchmark pod start time"); }
        Instant now = clock.instant();
        if (started.isAfter(now) || started.isBefore(now.minusSeconds(1800))) throw fail("A fresh benchmark pod (age <=30m) is required");
    }

    private static void githubOutput(Path path, Map<String, Object> snapshot, String json) throws IOException {
        validUid(snapshot.get("uid"));
        var values = new LinkedHashMap<String, Object>();
        values.put("poduid", snapshot.get("uid")); values.put("resources", json);
        values.put("containercpu", nested(object(snapshot.get("resources")), "limits", "cpu"));
        values.put("containercpurequest", nested(object(snapshot.get("resources")), "requests", "cpu"));
        values.put("podcpusum", snapshot.get("regularContainerCpuLimitSumMilli"));
        values.put("rediscpu", nested(object(snapshot.get("redisResources")), "limits", "cpu"));
        values.put("rediscpurequest", nested(object(snapshot.get("redisResources")), "requests", "cpu"));
        var output = new StringBuilder();
        for (var entry : values.entrySet()) {
            String value = entry.getValue().toString();
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) throw fail("Invalid evidence framing");
            output.append(entry.getKey()).append('=').append(value).append('\n');
        }
        if (output.toString().getBytes(StandardCharsets.UTF_8).length > 131_072) throw fail("Evidence output budget exceeded");
        Files.writeString(path, output, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static long cpu(String value) {
        if (!value.matches("^(0|[1-9][0-9]*)(m)?$")) throw fail("Unsupported CPU quantity");
        try {
            boolean milli = value.endsWith("m");
            long amount = Long.parseLong(milli ? value.substring(0, value.length() - 1) : value);
            long result = milli ? amount : Math.multiplyExact(amount, 1000);
            if (result > 64000) throw fail("CPU quantity exceeds evidence bound");
            return result;
        } catch (ArithmeticException | NumberFormatException failure) { throw fail("CPU quantity exceeds evidence bound"); }
    }

    static long storage(String value) {
        if (!value.matches("^(0|[1-9][0-9]*)(Mi|Gi)$")) throw fail("Storage quantity must use Mi or Gi");
        try {
            long amount = Long.parseLong(value.substring(0, value.length() - 2));
            long result = value.endsWith("Gi") ? Math.multiplyExact(amount, 1024) : amount;
            if (result > 1_048_576) throw fail("Storage quantity exceeds evidence bound");
            return result;
        } catch (ArithmeticException | NumberFormatException failure) { throw fail("Storage quantity exceeds evidence bound"); }
    }

    private static Long storageOrNull(Object value) {
        try { return storage(string(value)); } catch (IllegalArgumentException invalid) { return null; }
    }
    private static Long quantity(Map<String, Object> resources, String kind, String field, boolean cpu) {
        try { String value = string(nested(resources, kind, field)); return cpu ? cpu(value) : storage(value); }
        catch (IllegalArgumentException invalid) { return null; }
    }
    private static Long cpuSum(List<Map<String, Object>> containers, String kind) {
        long total = 0;
        for (var container : containers) {
            Long amount = quantity(optionalObject(container.get("resources")), kind, "cpu", true);
            if (amount == null) return null;
            total = Math.addExact(total, amount);
        }
        return total;
    }
    private static void minimum(Object value, long minimum, String message) {
        if (!(value instanceof Number number) || number.longValue() < minimum) throw fail(message);
    }
    private static void equalNumber(Object value, long expected, String message) {
        if (!(value instanceof Number number) || new java.math.BigDecimal(number.toString()).compareTo(java.math.BigDecimal.valueOf(expected)) != 0)
            throw fail(message);
    }
    private static Object nested(Map<String, Object> value, String parent, String child) { return optionalObject(value.get(parent)).get(child); }
    private static boolean workspaceMount(String path) {
        if (!path.startsWith("/") || path.length() > 16_384 || path.indexOf('\0') >= 0)
            throw fail("Invalid mount path");
        var segments = new ArrayDeque<String>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (segments.isEmpty()) throw fail("Invalid mount path");
                segments.removeLast();
            } else segments.addLast(part);
        }
        String normalized = "/" + String.join("/", segments);
        return normalized.equals("/workspace") || normalized.startsWith("/workspace/");
    }
    private static List<Map<String, Object>> castObjects(List<Object> values) {
        var result = new ArrayList<Map<String, Object>>(values.size());
        for (Object value : values) result.add(object(value));
        return result;
    }
    private static String required(Map<String, String> options, String key) {
        String value = options.get(key); if (value == null || value.isEmpty()) throw fail("Missing resource option"); return value;
    }
    private static void containerName(String value) { if (!value.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?") || value.length() > 63) throw fail("Invalid container name"); }
    private static void validUid(Object value) { if (!(value instanceof String uid) || !uid.matches("[a-zA-Z0-9-]{1,253}")) throw fail("Invalid pod UID"); }
    private static String string(Object value) { if (!(value instanceof String text)) throw fail("Expected string field"); return text; }
    @SuppressWarnings("unchecked") static Map<String, Object> object(Object value) { if (!(value instanceof Map<?, ?>)) throw fail("Expected object field"); return (Map<String, Object>) value; }
    private static Map<String, Object> optionalObject(Object value) { return value == null ? Map.of() : object(value); }
    @SuppressWarnings("unchecked") private static List<Object> array(Object value) { if (!(value instanceof List<?> list) || list.size() > 1024) throw fail("Invalid array field"); return (List<Object>) value; }
    private static List<Object> optionalArray(Object value) { return value == null ? List.of() : array(value); }
    private static Map<String, Object> named(List<Map<String, Object>> values, Object name) {
        Map<String, Object> found = null;
        for (var value : values) if (name != null && name.equals(value.get("name"))) { if (found != null) throw fail("Duplicate named resource"); found = value; }
        return found == null ? Map.of() : found;
    }
    private static Map<String, Object> fields(Map<String, Object> source, String... names) {
        var result = new LinkedHashMap<String, Object>(); for (String name : names) result.put(name, source.get(name)); return result;
    }
    static Map<String, Object> map(Object... pairs) {
        var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result;
    }
    private static IllegalArgumentException fail(String message) { return new IllegalArgumentException(message); }
}

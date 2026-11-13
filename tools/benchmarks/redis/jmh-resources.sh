#!/bin/sh
# Sourced by supported runners. Initialization is explicit; no traps or shell options here.
jmh_resources_init() {
  resources_output=$1
  resources_scripts=$2
  resources_python=${CACHEDB_BENCH_PYTHON:-python3}
  resources_input=${CACHEDB_BENCH_RESOURCE_CLASSES:?Prepare and export the resource helper first; see tools/benchmarks/redis/RESOURCE-COLLECTION.md}
  resources_entry=com/reactor/cachedb/benchmarks/redis/JmhResourcePhaseProfiler.class
  resources_profiler=com.reactor.cachedb.benchmarks.redis.JmhResourcePhaseProfiler
  command -v "$resources_python" >/dev/null || return 1
  test -d "$resources_input" && test ! -L "$resources_input" || return 1
  test "$(find "$resources_input" \( -type f -o -type l \) | wc -l)" -eq 1 || return 1
  test -f "$resources_input/$resources_entry" && test ! -L "$resources_input/$resources_entry" || return 1
  resources_classpath="$resources_output/resource-tools/classes"
  case "$resources_classpath" in *:*|*[[:cntrl:]]*) echo 'Unsafe resource helper classpath' >&2; return 1 ;; esac
  mkdir -p "$resources_classpath/$(dirname "$resources_entry")" "$resources_output/resources" || return 1
  cp "$resources_input/$resources_entry" "$resources_classpath/$resources_entry" || return 1
  cp "$resources_scripts/collect-jmh-resources.py" "$resources_output/resource-tools/collect-jmh-resources.py" || return 1
  resources_sources='RESOURCE-COLLECTION.md
jmh-resources.sh
collect-jmh-resources.py
prepare-jmh-resources.sh
test-jmh-resources.py
src/main/java/com/cachedb/benchmarks/redis/JmhResourcePhaseProfiler.java
src/test/java/com/cachedb/benchmarks/redis/JmhResourcePhaseProfilerTest.java'
  : > "$resources_output/resource-tools/sources.sha256"
  for resources_source in $resources_sources; do
    mkdir -p "$resources_output/resource-tools/sources/$(dirname "$resources_source")" || return 1
    cp "$resources_scripts/$resources_source" "$resources_output/resource-tools/sources/$resources_source" || return 1
    sha256sum "$resources_scripts/$resources_source" "$resources_output/resource-tools/sources/$resources_source" \
      >> "$resources_output/resource-tools/sources.sha256" || return 1
  done
  sha256sum "$resources_input/$resources_entry" "$resources_classpath/$resources_entry" \
    "$resources_scripts/jmh-resources.sh" "$resources_scripts/collect-jmh-resources.py" \
    "$resources_scripts/src/main/java/com/cachedb/benchmarks/redis/JmhResourcePhaseProfiler.java" \
    "$resources_output/resource-tools/collect-jmh-resources.py" \
    "$resources_output/resource-tools/sources.sha256" > "$resources_output/resource-tools/inputs.sha256" || return 1
}

jmh_resources_run() {
  resources_case=$1
  resources_side=$2
  shift 2
  "$resources_python" -B "$resources_output/resource-tools/collect-jmh-resources.py" \
    --output "$resources_output/resources/$resources_case-$resources_side" \
    --case "$resources_case" --side "$resources_side" \
    --interval-ms "${CACHEDB_BENCH_RESOURCE_INTERVAL_MS:-250}" --timeout-seconds 1200 -- "$@"
}

jmh_resources_verify() {
  test -n "${resources_output:-}" || return 1
  test -f "$resources_output/resource-tools/inputs.sha256" || return 1
  sha256sum --check --strict "$resources_output/resource-tools/inputs.sha256" > "$resources_output/resource-tools/inputs.after.log" 2>&1 || return 1
  sha256sum --check --strict "$resources_output/resource-tools/sources.sha256" > "$resources_output/resource-tools/sources.after.log" 2>&1
}

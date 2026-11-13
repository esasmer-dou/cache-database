#!/bin/sh
set -eu
if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  echo 'Usage: run-features-paired.sh FEATURES_JAR NEW_EVIDENCE_DIR [features|all|observation|async|tracking]' >&2
  exit 2
fi
jar=$1
output=$2
group=${3:-features}
case "$group" in
  features|all) groups='observation async tracking' ;;
  observation|async|tracking) groups=$group ;;
  *) echo 'Unknown complete capability matrix' >&2; exit 2 ;;
esac
cpus=${CACHEDB_BENCH_CPUS:?Set the fixed child CPU count explicitly}
case "$cpus" in 1|2) ;; *) exit 2 ;; esac
endpoint=${CACHEDB_BENCH_REDIS:?Use an exclusive disposable Redis server}
baseline=${CACHEDB_BENCH_BASELINE_JAR:?Name the preserved old baseline JAR explicitly}
case "$endpoint" in ''|*'@'*|*[![:graph:]]*) echo 'Credentials/whitespace forbidden in endpoint' >&2; exit 2 ;; esac
test "${#endpoint}" -le 1024
for injected in "${JAVA_TOOL_OPTIONS:-}" "${JDK_JAVA_OPTIONS:-}" "${_JAVA_OPTIONS:-}"; do
  test -z "$injected" || { echo 'Ambient JVM overrides are forbidden' >&2; exit 2; }
done
test -f "$jar" && test -f "$baseline"
test ! "$jar" -ef "$baseline" || { echo 'Feature output must not alias the preserved baseline' >&2; exit 2; }
test ! -e "$output" || { echo 'Refusing overwrite, resume or selection from an earlier attempt' >&2; exit 2; }
script_dir=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
. "$script_dir/jmh-resources.sh"
. "$script_dir/jmh-instrumentation.sh"
repo=$(CDPATH= cd -P "$script_dir/../../.." && pwd)
for utility in timeout sha256sum cut cp mkdir grep sed; do command -v "$utility" >/dev/null; done
java=${JAVA_HOME:+$JAVA_HOME/bin/}java
command -v "$java" >/dev/null
jar_sha=$(sha256sum "$jar" | cut -d ' ' -f 1)
baseline_sha=$(sha256sum "$baseline" | cut -d ' ' -f 1)
mkdir -p "$(dirname "$output")"
mkdir "$output"
output=$(CDPATH= cd -P "$output" && pwd)
# A fixed source set, not a directory sweep that can silently absorb unrelated changes.
# BEGIN FEATURE SOURCE SNAPSHOT
sources='cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisProducerGuard.java
tools/benchmarks/redis/pom.xml
tools/benchmarks/redis/run-coalescing-paired.sh
tools/benchmarks/redis/run-features-paired.sh
tools/benchmarks/redis/src/coalescing/java/com/cachedb/benchmarks/redis/RedisCoalescingBenchmark.java
tools/benchmarks/redis/src/coalescing/java/com/cachedb/benchmarks/redis/RedisCoalescingFixtures.java
tools/benchmarks/redis/src/coalescing/java/com/cachedb/benchmarks/redis/RedisCoalescingPreflight.java
tools/benchmarks/redis/src/features/README.md
tools/benchmarks/redis/src/features/java/com/cachedb/benchmarks/redis/RedisFeatureFixtures.java
tools/benchmarks/redis/src/features/java/com/cachedb/benchmarks/redis/RedisFeaturePreflight.java
tools/benchmarks/redis/src/features/java/com/cachedb/benchmarks/redis/RedisNativeAsyncBenchmark.java
tools/benchmarks/redis/src/features/java/com/cachedb/benchmarks/redis/RedisObservationBenchmark.java
tools/benchmarks/redis/src/features/java/com/cachedb/benchmarks/redis/RedisTrackingBenchmark.java
tools/benchmarks/redis/src/main/java/com/cachedb/benchmarks/redis/RedisBenchmarkFixture.java
tools/benchmarks/redis/src/main/java/com/cachedb/benchmarks/redis/RedisBenchmarkRunner.java
tools/benchmarks/redis/src/main/java/com/cachedb/benchmarks/redis/RedisRepositoryBenchmark.java
tools/benchmarks/redis/src/main/java/com/cachedb/benchmarks/redis/VirtualBenchmarkExecutor.java
tools/ci/check-t1-coalescing-evidence.ps1
tools/ci/check-t9-feature-evidence.ps1
tools/ci/jmh-histogram-consistency.ps1
tools/ci/test-t1-default-acceptance.ps1
tools/ci/test-t9-feature-evidence.ps1
tools/ci/test-jmh-histogram-consistency.ps1
tools/benchmarks/redis/src/main/java/com/cachedb/benchmarks/redis/JmhSampleCompactionProfiler.java
tools/benchmarks/redis/src/test/java/com/cachedb/benchmarks/redis/JmhSampleCompactionProfilerTest.java'
: > "$output/sources.sha256"
for source in $sources; do
  test -f "$repo/$source"
  mkdir -p "$output/sources/$(dirname "$source")"
  cp "$repo/$source" "$output/sources/$source"
  printf '%s  %s\n' "$(sha256sum "$repo/$source" | cut -d ' ' -f 1)" "$source" >> "$output/sources.sha256"
done
source_sha=$(sha256sum "$output/sources.sha256" | cut -d ' ' -f 1)
# END FEATURE SOURCE SNAPSHOT
finish() {
  rc=$?
  trap - 0 HUP INT TERM
  jmh_resources_verify || rc=1
  test "$(sha256sum "$jar" | cut -d ' ' -f 1)" = "$jar_sha" || rc=1
  test "$(sha256sum "$baseline" | cut -d ' ' -f 1)" = "$baseline_sha" || rc=1
  test "$(sha256sum "$output/sources.sha256" | cut -d ' ' -f 1)" = "$source_sha" || rc=1
  for source in $sources; do
    current=$(sha256sum "$repo/$source" | cut -d ' ' -f 1)
    original=$(grep -F "  $source" "$output/sources.sha256" | cut -d ' ' -f 1)
    test "$current" = "$original" || rc=1
    test "$(sha256sum "$output/sources/$source" | cut -d ' ' -f 1)" = "$original" || rc=1
  done
  if [ "$rc" -eq 0 ]; then
    printf 'COMPLETE_NOT_ACCEPTED\nRun the independent raw-evidence checker.\n' > "$output/COMPLETE.txt"
  else
    printf 'FAILED_OR_INCOMPLETE\nexit=%s\nRetain this entire attempt; restart a full matrix in a NEW directory.\n' "$rc" > "$output/FAILED.txt"
  fi
  exit "$rc"
}
trap finish 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
jmh_resources_init "$output" "$script_dir"
printf 'schema=T9_FEATURES_V1\ngroup=%s\ncontrollerHeapMiB=1024\nforkHeapMiB=512\ncpus=%s\njarSha256=%s\nbaselineSha256=%s\nsourcesSha256=%s\nfairness=true\nmaxTotal=32\nmaxWaitMillis=10000\nconnectMillis=5000\nsocketMillis=10000\nblockingMillis=15000\nsource=REDIS_ONLY\nguard=UNCHANGED\n' \
  "$group" "$cpus" "$jar_sha" "$baseline_sha" "$source_sha" > "$output/manifest.txt"
"$java" -version > "$output/java-version.txt" 2>&1
# Informational pod/cgroup context; CPU/heap gates use actual child VM evidence, not this text.
{ uname -a; for f in /sys/fs/cgroup/cpu.max /sys/fs/cgroup/memory.max; do
    if [ -r "$f" ]; then printf '%s=' "$f"; cat "$f"; fi
  done; } > "$output/resources.txt"
: > "$output/order.txt"
artifact_default=''
for capability in $groups; do
  timeout --signal=TERM --kill-after=15s 3m "$java" -Xms512m -Xmx512m "-XX:ActiveProcessorCount=$cpus" \
    "-Dcachedb.bench.redis=$endpoint" -cp "$jar" com.reactor.cachedb.benchmarks.redis.RedisFeaturePreflight "$capability" \
    > "$output/preflight-$capability.log" 2>&1
  proof=$(grep -E '^T9_DEFAULT coalescing=(true|false) configBuilder=(true|false) runtimeOptions=(true|false)$' "$output/preflight-$capability.log")
  test "$(printf '%s\n' "$proof" | grep -c '^T9_DEFAULT ')" -eq 1
  declared=$(printf '%s\n' "$proof" | sed 's/^T9_DEFAULT coalescing=\([^ ]*\) .*/\1/')
  test "$proof" = "T9_DEFAULT coalescing=$declared configBuilder=$declared runtimeOptions=$declared"
  if [ -n "$artifact_default" ]; then test "$declared" = "$artifact_default"; fi
  artifact_default=$declared
done
printf 'defaultCoalescing=%s\n' "$artifact_default" >> "$output/manifest.txt"

run_cell() {
  feature=$1; path=$2; mode=$3; variant=$4
  case "$feature:$path" in
    observation:hit) clazz=RedisObservationBenchmark; method=l1HitFindById; parameter=observation ;;
    observation:miss) clazz=RedisObservationBenchmark; method=l1MissFindById; parameter=observation ;;
    async:miss) clazz=RedisNativeAsyncBenchmark; method=nativeL1MissWindow1000; parameter=coalescing ;;
    tracking:hit) clazz=RedisTrackingBenchmark; method=l1HitFindById; parameter=invalidation ;;
    tracking:write) clazz=RedisTrackingBenchmark; method=saveSingle; parameter=invalidation ;;
    *) exit 2 ;;
  esac
  label="$feature-$path-$mode-$variant"
  printf '%s\n' "$label" >> "$output/order.txt"
  printf 'T9_CONTROLLER xmsMiB=1024 xmxMiB=1024 cpus=%s forkHeapMiB=512\n' "$cpus" > "$output/$label.log"
  jmh_instrumented_run "$feature-$path-$mode" "$variant" "$java" -Xms1024m -Xmx1024m "-XX:ActiveProcessorCount=$cpus" \
    -cp "$resources_classpath:$jar" org.openjdk.jmh.Main ".*$clazz\\.$method\$" \
    -t 1 -bm "$mode" -p "$parameter=$variant" -f 3 -wi 5 -i 5 -w 3s -r 3s -to 60s -si true -prof "$resources_profiler" -prof gc -foe true \
    -jvmArgs "-Xms512m -Xmx512m -XX:ActiveProcessorCount=$cpus" -jvmArgsAppend "-Dcachedb.bench.redis=$endpoint" \
    -rf json -rff "$output/$label.json" >> "$output/$label.log" 2>&1
}
for capability in $groups; do
  reverse=false
  case "$capability" in observation) paths='hit miss' ;; async) paths=miss ;; tracking) paths='hit write' ;; esac
  for mode in thrpt sample; do
    for path in $paths; do
      case "$capability:$reverse" in
        observation:false) variants='disabled noop handler' ;; observation:true) variants='noop disabled handler' ;;
        async:false) variants='false true' ;; async:true) variants='true false' ;;
        tracking:false) variants='PUBSUB SERVER_TRACKING' ;; tracking:true) variants='SERVER_TRACKING PUBSUB' ;;
      esac
      if [ "$reverse" = true ]; then reverse=false; else reverse=true; fi
      for variant in $variants; do run_cell "$capability" "$path" "$mode" "$variant"; done
    done
  done
done
if [ "$group" = all ]; then
  # Reuse the APPLIED T1 harness, including all-worker VT controls and artifact-default proof.
  timeout --signal=TERM --kill-after=15s 5h sh "$script_dir/run-coalescing-paired.sh" "$jar" "$output/coalescing"
fi

#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
  echo 'Usage: run-coalescing-paired.sh T1_PROFILE_JAR NEW_EVIDENCE_DIRECTORY' >&2
  exit 2
fi
jar=$1
output=$2
endpoint=${CACHEDB_BENCH_REDIS:?Set the dedicated Redis endpoint explicitly}
cpus=${CACHEDB_BENCH_CPUS:-2}
controller_heap_mib=512
case "$cpus" in 1|2) ;; *) echo 'CPU count must be 1 or 2' >&2; exit 2 ;; esac
case "$endpoint" in ''|*'@'*|*[![:graph:]]*) echo 'Credentials/whitespace are forbidden in the disposable Redis endpoint' >&2; exit 2 ;; esac
test "${#endpoint}" -le 1024
for injected in "${JAVA_TOOL_OPTIONS:-}" "${JDK_JAVA_OPTIONS:-}" "${_JAVA_OPTIONS:-}"; do
  test -z "$injected" || { echo 'Ambient JVM options are forbidden; preserve the fixed guard/resource contract' >&2; exit 2; }
done
test -f "$jar"
test ! -e "$output" || { echo 'Evidence already exists; refusing overwrite/resume/result selection' >&2; exit 2; }
script_dir=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
. "$script_dir/jmh-resources.sh"
. "$script_dir/jmh-instrumentation.sh"
source_root=$(CDPATH= cd -P "$script_dir/../../.." && pwd)
fixture="$script_dir/src/main/java/com/cachedb/benchmarks/redis/RedisBenchmarkFixture.java"
guard="$source_root/cachedb-storage-redis/src/main/java/com/cachedb/redis/RedisProducerGuard.java"
test -f "$fixture" && test -f "$guard"
for utility in timeout sha256sum cut cp grep sed find wc; do command -v "$utility" >/dev/null; done
java=${JAVA_HOME:+$JAVA_HOME/bin/}java
command -v "$java" >/dev/null
baseline=${CACHEDB_BENCH_BASELINE_JAR:-"$script_dir/target/benchmarks.jar"}
baseline_sha=absent
if [ -f "$baseline" ]; then
  baseline_sha=$(sha256sum "$baseline" | cut -d ' ' -f 1)
  test "$jar" != "$baseline" || { echo 'Do not use the preserved default/baseline JAR as a feature output' >&2; exit 2; }
fi
jar_sha=$(sha256sum "$jar" | cut -d ' ' -f 1)
jar=$(CDPATH= cd -P "$(dirname "$jar")" && pwd)/$(basename "$jar")
compactor=${CACHEDB_BENCH_COMPACTOR_DIR:?Prepare the packaged JMH compactor explicitly before measurement}
compactor_entry=com/reactor/cachedb/benchmarks/redis/JmhSampleCompactionProfiler.class
test -d "$compactor" && test ! -L "$compactor"
test "$(find "$compactor" \( -type f -o -type l \) | wc -l)" -eq 1
test -f "$compactor/$compactor_entry" && test ! -L "$compactor/$compactor_entry"
compactor_sha=$(sha256sum "$compactor/$compactor_entry" | cut -d ' ' -f 1)
fixture_sha=$(sha256sum "$fixture" | cut -d ' ' -f 1)
guard_sha=$(sha256sum "$guard" | cut -d ' ' -f 1)
mkdir -p "$(dirname "$output")"
mkdir "$output"
output=$(CDPATH= cd -P "$output" && pwd)
case "$output:$jar" in *[[:cntrl:]]*) echo 'Control characters in benchmark paths are forbidden' >&2; exit 2 ;; esac
case "$output" in *:*) exit 2 ;; esac
case "$jar" in *:*) exit 2 ;; esac
mkdir -p "$output/compactor/$(dirname "$compactor_entry")"
cp "$compactor/$compactor_entry" "$output/compactor/$compactor_entry"
finish() {
  rc=$?
  trap - 0 HUP INT TERM
  jmh_resources_verify || rc=1
  test "$(sha256sum "$jar" | cut -d ' ' -f 1)" = "$jar_sha" || rc=1
  test "$(sha256sum "$fixture" | cut -d ' ' -f 1)" = "$fixture_sha" || rc=1
  test "$(sha256sum "$guard" | cut -d ' ' -f 1)" = "$guard_sha" || rc=1
  test "$(sha256sum "$compactor/$compactor_entry" | cut -d ' ' -f 1)" = "$compactor_sha" || rc=1
  test "$(sha256sum "$output/compactor/$compactor_entry" | cut -d ' ' -f 1)" = "$compactor_sha" || rc=1
  if [ -z "${checker_tools_sha:-}" ] ||
      [ "$(sha256sum "$output/checker-tools.sha256" | cut -d ' ' -f 1)" != "${checker_tools_sha:-}" ]; then rc=1; fi
  for tool in ${checker_tools:-}; do
    recorded=$(grep -F "  $tool" "$output/checker-tools.sha256" | cut -d ' ' -f 1)
    test "$(sha256sum "$source_root/$tool" | cut -d ' ' -f 1)" = "$recorded" || rc=1
    test "$(sha256sum "$output/checker-tools/$tool" | cut -d ' ' -f 1)" = "$recorded" || rc=1
  done
  if [ "$baseline_sha" != absent ]; then
    test -f "$baseline" && test "$(sha256sum "$baseline" | cut -d ' ' -f 1)" = "$baseline_sha" || rc=1
  fi
  if [ "$rc" -ne 0 ]; then
    printf 'FAILED_OR_INCOMPLETE\nexit=%s\nRetain all raw evidence; no performance acceptance.\n' "$rc" > "$output/FAILED.txt"
  else
    printf 'COMPLETED_NOT_ACCEPTED\nApply the independent T1 evidence/numeric checker.\n' > "$output/COMPLETE.txt"
  fi
  exit "$rc"
}
trap finish 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
jmh_resources_init "$output" "$script_dir"
printf 'schema=T1_COALESCING_V4\ncontrollerHeapMiB=512\nforkHeapMiB=512\njarSha256=%s\nfixtureSha256=%s\nguardSourceSha256=%s\nbaselineSha256=%s\ncpus=%s\nfairness=true\nmaxTotal=32\nmaxWaitMillis=10000\nconnectMillis=5000\nsocketMillis=10000\nblockingMillis=15000\nnear=false\nsource=REDIS_ONLY\nguard=UNCHANGED\n' \
  "$jar_sha" "$fixture_sha" "$guard_sha" "$baseline_sha" "$cpus" > "$output/manifest.txt"
printf 'compactorClassSha256=%s\n' "$compactor_sha" >> "$output/manifest.txt"
printf '%s\n%s\n' "$output" "$jar" > "$output/controller-paths.txt"
cp "$fixture" "$output/RedisBenchmarkFixture.java"
cp "$guard" "$output/RedisProducerGuard.java"
# BEGIN T1 CHECKER TOOL SNAPSHOT
checker_tools='tools/ci/check-t1-coalescing-evidence.ps1
tools/ci/jmh-histogram-consistency.ps1
tools/ci/test-t1-default-acceptance.ps1
tools/ci/test-jmh-histogram-consistency.ps1
tools/benchmarks/redis/src/main/java/com/cachedb/benchmarks/redis/JmhSampleCompactionProfiler.java
tools/benchmarks/redis/src/test/java/com/cachedb/benchmarks/redis/JmhSampleCompactionProfilerTest.java'
mkdir -p "$output/checker-tools"
: > "$output/checker-tools.sha256"
for tool in $checker_tools; do
  test -f "$source_root/$tool"
  mkdir -p "$output/checker-tools/$(dirname "$tool")"
  cp "$source_root/$tool" "$output/checker-tools/$tool"
  printf '%s  %s\n' "$(sha256sum "$source_root/$tool" | cut -d ' ' -f 1)" "$tool" >> "$output/checker-tools.sha256"
done
checker_tools_sha=$(sha256sum "$output/checker-tools.sha256" | cut -d ' ' -f 1)
# END T1 CHECKER TOOL SNAPSHOT
"$java" -version > "$output/java-version.txt" 2>&1
: > "$output/order.txt"

# Both toggles must complete the same 1000 real virtual-worker / 16000-row parity control.
for enabled in false true; do
  timeout --signal=TERM --kill-after=15s 3m "$java" -Xms512m -Xmx512m "-XX:ActiveProcessorCount=$cpus" \
    "-Dcachedb.bench.redis=$endpoint" -cp "$jar" \
    com.reactor.cachedb.benchmarks.redis.RedisCoalescingPreflight "$enabled" \
    > "$output/preflight-$enabled.log" 2>&1
done

# This value is read from the hashed candidate JAR in BOTH real preflights, never an environment override.
default_false=$(grep -E '^T1_DEFAULT coalescing=(true|false) configBuilder=(true|false) runtimeOptions=(true|false)$' "$output/preflight-false.log")
default_true=$(grep -E '^T1_DEFAULT coalescing=(true|false) configBuilder=(true|false) runtimeOptions=(true|false)$' "$output/preflight-true.log")
test "$(printf '%s\n' "$default_false" | grep -c '^T1_DEFAULT ')" -eq 1
test "$(printf '%s\n' "$default_true" | grep -c '^T1_DEFAULT ')" -eq 1
test "$default_false" = "$default_true" || { echo 'Artifact defaults changed across preflights' >&2; exit 1; }
declared_default=$(printf '%s\n' "$default_false" | sed 's/^T1_DEFAULT coalescing=\([^ ]*\) .*/\1/')
case "$declared_default" in true|false) ;; *) exit 1 ;; esac
test "$default_false" = "T1_DEFAULT coalescing=$declared_default configBuilder=$declared_default runtimeOptions=$declared_default"
printf 'defaultCoalescing=%s\n' "$declared_default" >> "$output/manifest.txt"

# Alternate off/on pair order across the fixed matrix; retain every attempt and raw fork.
reverse=false
for mode in thrpt sample; do
  for scenario in point1 point16 point64 virtual1000; do
    threads=1
    method=l1MissFindById
    virtual=false
    case "$scenario" in
      point16) threads=16 ;;
      point64) threads=64 ;;
      virtual1000) threads=1000; method=l1MissFindByIdVirtual1000; virtual=true ;;
    esac
    toggles='false true'
    if [ "$reverse" = true ]; then toggles='true false'; reverse=false; else reverse=true; fi
    for enabled in $toggles; do
      label="$scenario-$mode-$enabled"
      printf '%s\n' "$label" >> "$output/order.txt"
      append="-Dcachedb.bench.redis=$endpoint"
      cell_classpath="$resources_classpath:$jar"
      set -- -t "$threads" -bm "$mode" -p "coalescing=$enabled" -f 3 -wi 5 -i 5 -w 3s -r 3s -to 60s -foe true
      if [ "$virtual" = true ] && [ "$mode" = sample ]; then
        cell_classpath="$output/compactor:$resources_classpath:$jar"
        set -- "$@" -prof com.reactor.cachedb.benchmarks.redis.JmhSampleCompactionProfiler -prof "$resources_profiler" -prof gc
      else
        set -- "$@" -prof "$resources_profiler" -prof gc
      fi
      if [ "$virtual" = true ]; then
        append="$append -Djmh.executor=CUSTOM -Djmh.executor.class=com.reactor.cachedb.benchmarks.redis.VirtualBenchmarkExecutor"
        # JMH 1.37 CUSTOM workers must not spin-rendezvous 1000 VTs on one/two carriers.
        set -- "$@" -si false
      fi
      echo "Measuring $label"
      printf 'T1_CONTROLLER xmsMiB=%s xmxMiB=%s cpus=%s forkHeapMiB=512\n' \
        "$controller_heap_mib" "$controller_heap_mib" "$cpus" > "$output/$label.log"
      set -- "$java" "-Xms${controller_heap_mib}m" "-Xmx${controller_heap_mib}m" "-XX:ActiveProcessorCount=$cpus" \
        -cp "$cell_classpath" org.openjdk.jmh.Main ".*RedisCoalescingBenchmark\.$method\$" "$@" \
        -jvmArgs "-Xms512m -Xmx512m -XX:ActiveProcessorCount=$cpus" -jvmArgsAppend "$append" \
        -rf json -rff "$output/$label.json"
      printf '%s\n' "$@" > "$output/$label.controller.args"
      jmh_instrumented_run "$scenario-$mode" "$enabled" "$@" >> "$output/$label.log" 2>&1
    done
  done
done

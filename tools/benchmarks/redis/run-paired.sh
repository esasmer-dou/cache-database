#!/bin/sh
set -eu

if [ "$#" -ne 3 ]; then
  echo 'Usage: run-paired.sh BASELINE_JAR CANDIDATE_JAR NEW_OUTPUT_DIRECTORY' >&2
  exit 2
fi
baseline=$1
candidate=$2
output=$3
endpoint=${CACHEDB_BENCH_REDIS:-127.0.0.1:16383}
cpus=${CACHEDB_BENCH_CPUS:-2}
selected=${CACHEDB_BENCH_CASE:-all}
case "$cpus" in 1|2) ;; *) echo 'Benchmark CPU count must be 1 or 2' >&2; exit 2 ;; esac
case "$selected" in all|all-comparable|post-virtual|hit|miss1|miss16|miss64|virtual|mixed|count|facet|write) ;;
  *) echo 'Unknown benchmark scenario' >&2; exit 2 ;; esac
case "$endpoint" in ''|*'@'*|*[![:graph:]]*) echo 'Use a disposable benchmark endpoint without credentials or whitespace' >&2; exit 2 ;; esac
test "${#endpoint}" -le 1024
test -f "$baseline" && test -f "$candidate"
test ! -e "$output" || { echo 'Evidence directory already exists; refusing to overwrite results' >&2; exit 2; }
mkdir -p "$output"
output=$(CDPATH= cd -P "$output" && pwd)
baseline=$(CDPATH= cd -P "$(dirname "$baseline")" && pwd)/$(basename "$baseline")
candidate=$(CDPATH= cd -P "$(dirname "$candidate")" && pwd)/$(basename "$candidate")
script_dir=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
. "$script_dir/jmh-resources.sh"
. "$script_dir/jmh-instrumentation.sh"
sha256sum "$baseline" "$candidate" > "$output/jars.before.sha256"
compactor=${CACHEDB_BENCH_COMPACTOR_DIR:-}
compactor_entry=com/reactor/cachedb/benchmarks/redis/JmhSampleCompactionProfiler.class
if [ "$selected" = all ] || [ "$selected" = virtual ]; then
  test -n "$compactor" && test -d "$compactor" && test ! -L "$compactor"
  test "$(find "$compactor" \( -type f -o -type l \) | wc -l)" -eq 1
  test -f "$compactor/$compactor_entry" && test ! -L "$compactor/$compactor_entry"
  mkdir -p "$output/compactor/$(dirname "$compactor_entry")"
  cp "$compactor/$compactor_entry" "$output/compactor/$compactor_entry"
  sha256sum "$compactor/$compactor_entry" "$output/compactor/$compactor_entry" > "$output/compactor.before.sha256"
fi
finish() {
  rc=$?
  trap - 0 HUP INT TERM
  jmh_resources_verify || rc=1
  sha256sum --check --strict "$output/jars.before.sha256" > "$output/jars.after.log" 2>&1 || rc=1
  if [ -f "$output/compactor.before.sha256" ]; then
    sha256sum --check --strict "$output/compactor.before.sha256" > "$output/compactor.after.log" 2>&1 || rc=1
  fi
  if [ "$rc" -ne 0 ]; then printf 'FAILED_OR_INCOMPLETE\n' > "$output/FAILED.txt"; fi
  exit "$rc"
}
trap finish 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
java=${JAVA_HOME:+$JAVA_HOME/bin/}java
command -v timeout >/dev/null
jmh_resources_init "$output" "$script_dir"

# Alternate pair order without selecting results by whether they pass a threshold.
reverse=false
for scenario in hit miss1 miss16 miss64 virtual mixed count facet write; do
  threads=1
  virtual=false
  case "$scenario" in
    hit) method=l1HitFindById ;;
    miss1) method=l1MissFindById ;;
    miss16) method=l1MissFindById; threads=16 ;;
    miss64) method=l1MissFindById; threads=64 ;;
    virtual) method=l1MissFindByIdVirtual1000; threads=1000; virtual=true ;;
    mixed) method=findAllMixed100 ;;
    count) method=aggregateCount10k ;;
    facet) method=facet10k20Groups ;;
    write) method=saveSingle ;;
  esac
  versions='baseline candidate'
  if [ "$reverse" = true ]; then versions='candidate baseline'; reverse=false; else reverse=true; fi
  if [ "$selected" = all-comparable ]; then
    if [ "$scenario" = virtual ]; then continue; fi
  elif [ "$selected" = post-virtual ]; then
    case "$scenario" in mixed|count|facet|write) ;; *) continue ;; esac
  elif [ "$selected" != all ] && [ "$selected" != "$scenario" ]; then continue; fi
  for version in $versions; do
    jar=$baseline
    if [ "$version" = candidate ]; then jar=$candidate; fi
    append="-Dcachedb.bench.redis=$endpoint"
    cell_classpath="$resources_classpath:$jar"
    set -- -t "$threads" -bm thrpt,sample -f 3 -wi 5 -i 5 -w 3s -r 3s -foe true
    if [ "$virtual" = true ]; then
      cell_classpath="$output/compactor:$resources_classpath:$jar"
      set -- "$@" -prof com.reactor.cachedb.benchmarks.redis.JmhSampleCompactionProfiler -prof "$resources_profiler" -prof gc
      append="$append -Djmh.executor=CUSTOM -Djmh.executor.class=com.reactor.cachedb.benchmarks.redis.VirtualBenchmarkExecutor"
      set -- "$@" -si false
    else
      set -- "$@" -prof "$resources_profiler" -prof gc
    fi
    echo "Measuring $scenario / $version"
    set -- "$java" -Xms512m -Xmx512m "-XX:ActiveProcessorCount=$cpus" \
      -cp "$cell_classpath" org.openjdk.jmh.Main ".*RedisRepositoryBenchmark\.$method\$" "$@" \
      -jvmArgs "-Xms512m -Xmx512m -XX:ActiveProcessorCount=$cpus" -jvmArgsAppend "$append" \
      -rf json -rff "$output/$scenario-$version.json"
    printf '%s\n' "$@" > "$output/$scenario-$version.controller.args"
    jmh_instrumented_run "$scenario" "$version" "$@" > "$output/$scenario-$version.log" 2>&1
  done
done

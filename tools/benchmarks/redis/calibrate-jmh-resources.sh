#!/bin/sh
# Short observer-cost probe, not the full JMH matrix or a performance acceptance gate.
set -eu
test "$#" -eq 2 || { echo 'Usage: calibrate-jmh-resources.sh BENCHMARK_JAR NEW_OUTPUT_DIRECTORY' >&2; exit 2; }
jar=$1
output=$2
test -f "$jar" && test ! -e "$output"
for option in "${JAVA_TOOL_OPTIONS:-}" "${JDK_JAVA_OPTIONS:-}" "${_JAVA_OPTIONS:-}"; do
  test -z "$option" || { echo 'Inherited JVM options are not allowed' >&2; exit 2; }
done
endpoint=${CACHEDB_BENCH_REDIS:?Use a disposable benchmark Redis}
case "$endpoint" in ''|*'@'*|*[![:graph:]]*) exit 2 ;; esac
test "${#endpoint}" -le 1024
scripts=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
jar=$(CDPATH= cd -P "$(dirname "$jar")" && pwd)/$(basename "$jar")
mkdir -p "$output"
output=$(CDPATH= cd -P "$output" && pwd)
. "$scripts/jmh-resources.sh"
jmh_resources_init "$output" "$scripts"
java=${JAVA_HOME:+$JAVA_HOME/bin/}java
sha256sum "$jar" "$scripts/calibrate-jmh-resources.sh" > "$output/calibration.before.sha256"
finish() {
  rc=$?
  trap - 0 HUP INT TERM
  jmh_resources_verify || rc=1
  sha256sum --check --strict "$output/calibration.before.sha256" > "$output/calibration.after.log" 2>&1 || rc=1
  printf '%s\n' "$rc" > "$output/calibration.exit"
  exit "$rc"
}
trap finish 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
n=0
# Reversing the order retains both observations instead of selecting the favorable one.
for mode in plain gc resource resource-gc resource-gc resource gc plain; do
  n=$((n+1))
  label="$n-$mode"
  printf '%s\n' "$label" >> "$output/order.txt"
  set -- "$java" -Xms512m -Xmx512m -XX:ActiveProcessorCount=2 \
    -cp "$resources_classpath:$jar" org.openjdk.jmh.Main \
    '.*RedisRepositoryBenchmark\.l1HitFindById$' \
    -t 1 -bm thrpt -f 1 -wi 2 -i 3 -w 1s -r 1s -to 15s -foe true \
    -jvmArgs '-Xms512m -Xmx512m -XX:ActiveProcessorCount=2' \
    -jvmArgsAppend "-Dcachedb.bench.redis=$endpoint" \
    -rf json -rff "$output/$label.json"
  case "$mode" in
    gc) set -- "$@" -prof gc ;;
    resource) set -- "$@" -prof "$resources_profiler" ;;
    resource-gc) set -- "$@" -prof "$resources_profiler" -prof gc ;;
  esac
  printf '%s\n' "$@" > "$output/$label.controller.args"
  case "$mode" in
    resource*)
      "$resources_python" -B "$output/resource-tools/collect-jmh-resources.py" \
        --output "$output/resources/$label" --case hit --side "$label" \
        --interval-ms 250 --timeout-seconds 45 -- "$@" > "$output/$label.log" 2>&1 ;;
    *) timeout --signal=TERM --kill-after=5s 45 "$@" > "$output/$label.log" 2>&1 ;;
  esac
done
"$resources_python" -B - "$output" <<'PY'
import json
import math
import pathlib
import statistics
import sys

root = pathlib.Path(sys.argv[1])
groups = {}
observations = []
for line in (root / "order.txt").read_text().splitlines():
    rows = json.loads((root / (line + ".json")).read_text())
    if len(rows) != 1:
        raise ValueError("calibration requires one real benchmark row per condition")
    row = rows[0]
    metric = row["primaryMetric"]
    if (row["forks"] != 1 or row["measurementIterations"] != 3
            or row["mode"] != "thrpt" or metric["scoreUnit"] != "ops/us"
            or not row["benchmark"].endswith("RedisRepositoryBenchmark.l1HitFindById")):
        raise ValueError("unexpected calibration contract")
    raw = metric["rawData"]
    if len(raw) != 1 or len(raw[0]) != 3 or any(not math.isfinite(x) or x <= 0 for x in raw[0]):
        raise ValueError("missing or nonfinite raw calibration evidence")
    mode = line.split("-", 1)[1]
    score = metric["score"]
    if not math.isfinite(score) or score <= 0:
        raise ValueError("invalid calibration score")
    observation = {"label": line, "mode": mode, "ops_per_us": score, "raw_iterations": raw[0]}
    if mode.startswith("resource"):
        status = json.loads((root / "resources" / line / "status.json").read_text())
        if status["command_exit"] != 0 or status["status"] != "COMPLETE_NOT_ACCEPTED" or status["completed_phases"] != 5:
            raise ValueError("incomplete observer evidence")
        observation["observer_cpu_ns"] = status["observer_process_cpu_ns"]
        observation["observer_elapsed_ns"] = status["observer_elapsed_ns"]
        observation["observer_cpu_fraction"] = status["observer_process_cpu_ns"] / status["observer_elapsed_ns"]
    observations.append(observation)
    groups.setdefault(mode, []).append(score)
if any(len(values) != 2 for values in groups.values()) or len(groups) != 4:
    raise ValueError("missing reversed condition")
medians = {mode: statistics.median(values) for mode, values in groups.items()}
summary = {"scope": "CALIBRATION_ONLY_NOT_MATRIX_ACCEPTANCE_OR_ZERO_OVERHEAD_PROOF",
           "conditions": groups, "median_ops_per_us": medians, "observations": observations,
           "observer_plus_gc_vs_gc_percent": 100 * (medians["resource-gc"] / medians["gc"] - 1),
           "gc_vs_plain_percent": 100 * (medians["gc"] / medians["plain"] - 1),
           "resource_vs_plain_percent": 100 * (medians["resource"] / medians["plain"] - 1),
           "interpretation": "Two short reversed observations per condition; retain noise, no subtraction or acceptance threshold."}
(root / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps(summary, indent=2))
PY
printf 'CALIBRATION_COMPLETE_NOT_PERFORMANCE_ACCEPTANCE\n'

#!/bin/sh
set -eu
umask 077
export LC_ALL=C
test "$#" -eq 2 || { echo 'Usage: prepare-jmh-resources.sh JMH_1_37_JAR NEW_OUTPUT_DIRECTORY' >&2; exit 2; }
jar=$1
output=$2
test -f "$jar" && test ! -e "$output"
jar=$(CDPATH= cd -P "$(dirname "$jar")" && pwd)/$(basename "$jar")
scripts=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
helper="$scripts/src/main/java/com/cachedb/benchmarks/redis/JmhResourcePhaseProfiler.java"
test_source="$scripts/src/test/java/com/cachedb/benchmarks/redis/JmhResourcePhaseProfilerTest.java"
for injected in "${JAVA_TOOL_OPTIONS:-}" "${JDK_JAVA_OPTIONS:-}" "${_JAVA_OPTIONS:-}" "${CLASSPATH:-}"; do test -z "$injected"; done
for utility in timeout sha256sum find wc; do command -v "$utility" >/dev/null; done
java=${JAVA_HOME:+$JAVA_HOME/bin/}java
javac=${JAVA_HOME:+$JAVA_HOME/bin/}javac
python=${CACHEDB_BENCH_PYTHON:-python3}
command -v "$python" >/dev/null
mkdir -p "$(dirname "$output")"
mkdir "$output"
output=$(CDPATH= cd -P "$output" && pwd)
mkdir "$output/classes" "$output/test-classes" "$output/empty-source" "$output/java-events"
sha256sum "$jar" "$helper" "$test_source" "$scripts/collect-jmh-resources.py" \
  "$scripts/test-jmh-resources.py" "$scripts/jmh-resources.sh" "$scripts/prepare-jmh-resources.sh" \
  "$scripts/RESOURCE-COLLECTION.md" > "$output/inputs.sha256"
finish() {
  rc=$?
  trap - 0 HUP INT TERM
  sha256sum --check --strict "$output/inputs.sha256" > "$output/inputs.after.log" 2>&1 || rc=1
  if [ "$rc" -eq 0 ]; then printf 'CONTRACT_PASS_NOT_PERFORMANCE_ACCEPTANCE\n' > "$output/COMPLETE.txt";
  else printf 'FAILED_OR_INCOMPLETE\n' > "$output/FAILED.txt"; fi
  exit "$rc"
}
trap finish 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
ulimit -f 32768
timeout --signal=TERM --kill-after=5s 120s "$javac" -proc:none -implicit:none -J-Xmx128m -J-XX:ActiveProcessorCount=1 \
  -sourcepath "$output/empty-source" -cp "$jar" -d "$output/classes" "$helper" > "$output/compile.log" 2>&1
test "$(find "$output/classes" -type f | wc -l)" -eq 1
timeout --signal=TERM --kill-after=5s 120s "$javac" -proc:none -implicit:none -J-Xmx128m -J-XX:ActiveProcessorCount=1 \
  -sourcepath "$output/empty-source" -cp "$output/classes:$jar" -d "$output/test-classes" "$test_source" > "$output/test-compile.log" 2>&1
CACHEDB_BENCH_RESOURCE_EVENTS="$output/java-events" timeout --signal=TERM --kill-after=5s 60s "$java" -Xms16m -Xmx64m -XX:ActiveProcessorCount=1 \
  -cp "$output/test-classes:$output/classes:$jar" com.reactor.cachedb.benchmarks.redis.JmhResourcePhaseProfilerTest \
  > "$output/java-contract.log" 2>&1
timeout --signal=TERM --kill-after=5s 60s "$python" -B "$scripts/test-jmh-resources.py" > "$output/python-contract.log" 2>&1
sha256sum "$output/classes/com/reactor/cachedb/benchmarks/redis/JmhResourcePhaseProfiler.class" > "$output/helper.sha256"

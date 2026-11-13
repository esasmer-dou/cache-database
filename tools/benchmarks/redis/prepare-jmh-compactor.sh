#!/bin/sh
set -eu
umask 077
ulimit -c 0
export LC_ALL=C
test "$#" -eq 2 || { echo 'Usage: prepare-jmh-compactor.sh PACKAGED_BENCHMARK_JAR NEW_OUTPUT_DIRECTORY' >&2; exit 2; }
jar=$1
output=$2
test -f "$jar" && test ! -e "$output"
jar=$(CDPATH= cd -P "$(dirname "$jar")" && pwd)/$(basename "$jar")
script_dir=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
helper="$script_dir/src/main/java/com/cachedb/benchmarks/redis/JmhSampleCompactionProfiler.java"
selftest="$script_dir/src/test/java/com/cachedb/benchmarks/redis/JmhSampleCompactionProfilerTest.java"
entry=com/reactor/cachedb/benchmarks/redis/JmhSampleCompactionProfiler.class
for injected in "${JAVA_TOOL_OPTIONS:-}" "${JDK_JAVA_OPTIONS:-}" "${_JAVA_OPTIONS:-}" "${CLASSPATH:-}"; do test -z "$injected"; done
for utility in timeout sha256sum cut find sort grep wc cmp cat; do command -v "$utility" >/dev/null; done
java=${JAVA_HOME:+$JAVA_HOME/bin/}java
javac=${JAVA_HOME:+$JAVA_HOME/bin/}javac
jar_tool=${JAVA_HOME:+$JAVA_HOME/bin/}jar
test "$(sha256sum "$helper" | cut -d ' ' -f 1)" = 9483f3b96d0b4c7445485e5999720d06c73ce9e58accd6a51b80cd4787c14305
test "$(sha256sum "$selftest" | cut -d ' ' -f 1)" = 31e872dd0d19bcba516650f99006157c477c9289a56620866d3cd2d94e98da76
test "$(wc -c < "$jar")" -le 268435456
mkdir -p "$(dirname "$output")"
mkdir "$output"
output=$(CDPATH= cd -P "$output" && pwd)
mkdir "$output/helper-classes" "$output/test-classes" "$output/empty-source"
sha256sum "$jar" "$helper" "$selftest" "$script_dir/prepare-jmh-compactor.sh" > "$output/inputs.sha256"
finish() {
  rc=$?
  trap - 0 HUP INT TERM
  sha256sum --check --strict "$output/inputs.sha256" > "$output/inputs.after.log" 2>&1 || rc=1
  if [ "$rc" -eq 0 ]; then printf 'PACKAGED_HELPER_SELFCHECK_PASS_NOT_PERFORMANCE_ACCEPTANCE\n' > "$output/COMPLETE.txt";
  else printf 'FAILED_OR_INCOMPLETE\n' > "$output/FAILED.txt"; fi
  exit "$rc"
}
trap finish 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
# Bound all tool output, including a malformed archive listing; never compile a shadow helper source.
ulimit -f 4096
timeout --signal=TERM --kill-after=5s 30s "$jar_tool" -J-Xmx128m -J-XX:ActiveProcessorCount=1 tf "$jar" > "$output/jar-entries.txt" 2> "$output/jar.stderr"
test "$(grep -Fxc "$entry" "$output/jar-entries.txt")" -eq 1
(cd "$output/helper-classes"; timeout --signal=TERM --kill-after=5s 30s "$jar_tool" -J-Xmx128m -J-XX:ActiveProcessorCount=1 xf "$jar" "$entry")
test -f "$output/helper-classes/$entry" && test ! -L "$output/helper-classes/$entry"
test "$(find "$output/helper-classes" -type f | wc -l)" -eq 1
timeout --signal=TERM --kill-after=5s 120s "$javac" -proc:none -implicit:none -J-Xmx128m -J-XX:ActiveProcessorCount=1 \
  -sourcepath "$output/empty-source" -cp "$jar" -d "$output/test-classes" "$selftest" \
  > "$output/compile.stdout" 2> "$output/compile.stderr"
(cd "$output/test-classes"; find com -type f | sort) > "$output/test-entries.txt"
printf '%s\n' 'com/reactor/cachedb/benchmarks/redis/JmhSampleCompactionProfilerTest$1.class' \
  'com/reactor/cachedb/benchmarks/redis/JmhSampleCompactionProfilerTest$GcOrderControl.class' \
  'com/reactor/cachedb/benchmarks/redis/JmhSampleCompactionProfilerTest.class' | sort > "$output/test-entries.expected"
cmp "$output/test-entries.expected" "$output/test-entries.txt"
timeout --signal=TERM --kill-after=5s 120s "$java" -Xms64m -Xmx512m -XX:ActiveProcessorCount=1 \
  -cp "$output/test-classes:$jar" com.reactor.cachedb.benchmarks.redis.JmhSampleCompactionProfilerTest \
  > "$output/selftest.stdout" 2> "$output/selftest.stderr"
test "$(cat "$output/selftest.stdout")" = 'JmhSampleCompactionProfilerTest: 5 checks passed'
(cd "$output/helper-classes"; sha256sum "$entry") > "$output/helper.sha256"
sha256sum "$output/inputs.sha256" "$output/helper.sha256" "$output/selftest.stdout" > "$output/proof.sha256"

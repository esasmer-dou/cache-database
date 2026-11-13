#!/usr/bin/env bash
# Paired worker: executes only inside the approved EmaTech benchmark container.
set -euo pipefail
umask 077
work=$1; commit=$2; baseline=$3; expected=$4; cpus=$5; endpoint=$6; output=$7; cutoff=$8; uid=$9
cd -P "$work"
test "$(git rev-parse HEAD)" = "$commit"
baseline=$(realpath "$baseline")
case "$baseline" in /workspace/*) ;; *) exit 2 ;; esac
case "$baseline" in */target/*) echo 'Preserved baseline must be outside every target/' >&2; exit 2 ;; esac
assert_source() {
  test "$(git rev-parse HEAD)" = "$commit" || return 1
  git diff --quiet "$commit" -- . || return 1
  local extra
  set -- . ':(exclude).cachedb-ci-evidence/**'
  case "$baseline" in "$PWD"/*) set -- "$@" ":(exclude)${baseline#"$PWD"/}" ;; esac
  extra=$(git ls-files --others --exclude-standard -- "$@") || return 1
  test -z "$extra"
}
assert_source
for utility in flock pgrep timeout sha256sum mvn pwsh tar python3 lscpu; do command -v "$utility" >/dev/null; done
for injected in "${JAVA_TOOL_OPTIONS:-}" "${JDK_JAVA_OPTIONS:-}" "${_JAVA_OPTIONS:-}" "${CLASSPATH:-}"; do test -z "$injected"; done
exec 9>/tmp/cachedb-exclusive-performance.lock
flock -n 9
if pgrep -x java >/dev/null; then echo 'Benchmark container is not idle' >&2; exit 2; fi
test -f "$baseline"
test "$(sha256sum "$baseline" | cut -d ' ' -f 1)" = "$expected"
test ! -e "$output"
mkdir -p "$(dirname "$output")"
mkdir "$output"
complete=false
finish() {
  rc=$?
  trap - EXIT HUP INT TERM
  test "$(sha256sum "$baseline" | cut -d ' ' -f 1)" = "$expected" || rc=1
  assert_source || rc=1
  if test "$rc" -ne 0 || test "$complete" != true; then
    printf 'FAILED_OR_INCOMPLETE\nexit=%s\nNo retry, resume or evidence selection.\n' "$rc" > "$output/CI_FAILED.txt"
    test "$rc" -ne 0 || rc=1
  else
    printf 'COMPLETE_NUMERIC_GATES_NOT_RELEASE_ACCEPTANCE\n' > "$output/CI_COMPLETE.txt"
  fi
  exit "$rc"
}
trap finish EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
bounded() {
  local seconds=$1; shift
  local left=$((cutoff - $(date +%s)))
  test "$left" -gt 0
  if test "$seconds" -gt "$left"; then seconds=$left; fi
  timeout --signal=TERM --kill-after=15s "${seconds}s" "$@"
}
printf 'commit=%s\npodUid=%s\nbaselineVersion=0.18.1\nbaselineSha256=%s\nchildActiveProcessorCount=%s\nworkCutoffEpoch=%s\n' \
  "$commit" "$uid" "$expected" "$cpus" "$cutoff" > "$output/ci-attachment.txt"
# Check the independently supplied original binary, never rebuild it from candidate source.
bounded 30 python3 - "$baseline" > "$output/baseline-version.txt" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as jar:
    for module in ('cachedb-core', 'cachedb-runtime', 'cachedb-redis'):
        entry = 'META-INF/maven/com.reactor.cachedb/' + module + '/pom.properties'
        lines = jar.read(entry).decode('iso-8859-1').splitlines()
        if [line.strip() for line in lines if line.startswith('version=')] != ['version=0.18.1']:
            raise SystemExit('Preserved baseline is not 0.18.1: ' + module)
        print(module + '=0.18.1')
PY
bounded 60 git archive --format=tar.gz -o "$output/source.tar.gz" "$commit"
java -version > "$output/benchmark-java.txt" 2>&1
lscpu > "$output/benchmark-cpu.txt"
export MAVEN_OPTS='-Xmx512m'
bounded 1500 mvn -B -ntp -Predis-benchmarks -pl tools/benchmarks/redis -am -DskipTests clean package \
  > "$output/build.log" 2>&1 </dev/null
candidate="$PWD/tools/benchmarks/redis/target/benchmarks.jar"
bounded 120 pwsh -NoProfile -File tools/ci/compare-benchmarks.ps1 -SelfTest > "$output/comparator-selftest.log" 2>&1 </dev/null
bounded 300 pwsh -NoProfile -File tools/ci/test-paired-benchmark-merge.ps1 > "$output/merge-selftest.log" 2>&1 </dev/null
bounded 360 sh tools/benchmarks/redis/prepare-jmh-compactor.sh "$candidate" "$output/compactor-selfcheck" \
  > "$output/compactor-selfcheck.log" 2>&1 </dev/null
export CACHEDB_BENCH_COMPACTOR_DIR="$output/compactor-selfcheck/helper-classes"
bounded 360 sh tools/benchmarks/redis/prepare-jmh-resources.sh "$candidate" "$output/resource-selfcheck" \
  > "$output/resource-selfcheck.log" 2>&1 </dev/null
export CACHEDB_BENCH_RESOURCE_CLASSES="$output/resource-selfcheck/classes"
export CACHEDB_BENCH_CPUS="$cpus" CACHEDB_BENCH_REDIS="$endpoint"
mkdir "$output/jars"
cp "$baseline" "$output/jars/preserved-v0.18.1.jar"
cp "$candidate" "$output/jars/candidate.jar"
sha256sum "$output/jars/"*.jar > "$output/jars.sha256"
cp -R tools/benchmarks/baseline/v0.18.1-virtual-liveness "$output/legacy-virtual-liveness"
CACHEDB_BENCH_CASE=miss1 bounded 1200 sh tools/benchmarks/redis/run-paired.sh \
  "$baseline" "$baseline" "$output/aa-control" > "$output/aa-launcher.log" 2>&1 </dev/null
bounded 120 pwsh -NoProfile -File tools/ci/compare-benchmarks.ps1 \
  -Baseline "$output/aa-control/miss1-baseline.json" -Current "$output/aa-control/miss1-candidate.json" \
  -Summary "$output/aa-control/comparison.md" > "$output/aa-gates.log" 2>&1 </dev/null
bounded 120 pwsh -NoProfile -File tools/ci/check-legacy-virtual-liveness.ps1 \
  -Path tools/benchmarks/baseline/v0.18.1-virtual-liveness/failure.json > "$output/legacy-gates.log" 2>&1 </dev/null
CACHEDB_BENCH_CASE=all-comparable bounded 7200 sh tools/benchmarks/redis/run-paired.sh \
  "$baseline" "$candidate" "$output/paired" > "$output/paired-launcher.log" 2>&1 </dev/null
CACHEDB_BENCH_CASE=virtual bounded 1200 sh tools/benchmarks/redis/run-paired.sh \
  "$candidate" "$candidate" "$output/candidate-virtual" > "$output/virtual-launcher.log" 2>&1 </dev/null
bounded 300 pwsh -NoProfile -File tools/ci/merge-paired-benchmarks.ps1 -Directory "$output/paired" \
  -ProcessorCount "$cpus" -LegacyVirtualLivenessEvidence tools/benchmarks/baseline/v0.18.1-virtual-liveness/failure.json \
  -CandidateVirtualResult "$output/candidate-virtual/virtual-candidate.json" > "$output/paired-gates.log" 2>&1 </dev/null
complete=true

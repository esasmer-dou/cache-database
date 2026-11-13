#!/usr/bin/env bash
# Controller orchestration only; compilation happens in the already approved pod.
set -euo pipefail
umask 077
for utility in kubectl timeout git tar sha256sum date; do command -v "$utility" >/dev/null; done
test "$(git rev-parse HEAD)" = "$GITHUB_SHA"
for name in "$NS" "$POD" "$CONTAINER"; do
  [[ "$name" =~ ^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$ ]] && test "${#name}" -le 253
done
[[ "$WORK" =~ ^/workspace(/[A-Za-z0-9_.-]+)*$ ]]
case "$WORK" in */../*|*/./*|*/..|*/.) exit 2 ;; esac
test ! -e "$RESOURCE_GUARD"
mkdir -p "$(dirname "$RESOURCE_GUARD")"
bounded() {
  local seconds=$1; shift
  if test -n "${CACHEDB_CI_WORK_CUTOFF_EPOCH:-}"; then
    local left=$((CACHEDB_CI_WORK_CUTOFF_EPOCH - $(date +%s)))
    test "$left" -gt 0
    if test "$seconds" -gt "$left"; then seconds=$left; fi
  fi
  timeout --signal=TERM --kill-after=5s "${seconds}s" "$@"
}
kube=(kubectl --context kubernetes-admin@EmaTech --request-timeout=30s -n "$NS")
identity=$(bounded 60 "${kube[@]}" get pod "$POD" -o 'jsonpath={.metadata.uid}{"|"}{.status.startTime}{"|"}{.spec.activeDeadlineSeconds}{"|"}{.status.phase}')
IFS='|' read -r uid started lifetime phase <<< "$identity"
[[ "$uid" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]
test -z "${APPROVED_POD_UID:-}" || test "$uid" = "$APPROVED_POD_UID"
test "$lifetime" = 21600 && test "$phase" = Running
[[ "$started" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]]
start_epoch=$(date -u -d "$started" +%s); now=$(date +%s)
test "$start_epoch" -le "$now" && test "$((now - start_epoch))" -le 1800
if test -n "${APPROVED_DEADLINE_EPOCH:-}"; then
  test "$APPROVED_DEADLINE_EPOCH" -le "$((start_epoch + lifetime))"
fi
git diff --quiet "$GITHUB_SHA" -- .
test -z "$(git ls-files --others --exclude-standard -- pom.xml tools/ci/resource-guard)"
source_sha=$(git ls-tree -r "$GITHUB_SHA" -- pom.xml .mvn tools/ci/resource-guard | sha256sum | cut -d ' ' -f 1)
printf '%s\n' "$uid" > "$RESOURCE_GUARD.poduid"
printf 'commit=%s\nsourceTreeSha256=%s\npodUid=%s\nstartTime=%s\npodDeadlineSeconds=%s\n' \
  "$GITHUB_SHA" "$source_sha" "$uid" "$started" "$lifetime" > "$RESOURCE_GUARD.binding.txt"
seconds=300
if test -n "${CACHEDB_CI_WORK_CUTOFF_EPOCH:-}"; then
  left=$((CACHEDB_CI_WORK_CUTOFF_EPOCH - $(date +%s)))
  test "$left" -gt 0
  if test "$seconds" -gt "$left"; then seconds=$left; fi
fi
# The independent remote timeout also applies after controller disconnection.
bounded "$seconds" "${kube[@]}" --request-timeout=0 exec -i "$POD" -c "$CONTAINER" -- \
  timeout --signal=TERM --kill-after=5s "${seconds}s" bash -s -- \
  "$WORK" "$GITHUB_SHA" "$source_sha" > "$RESOURCE_GUARD.build.log" 2>&1 <<'REMOTE_GUARD'
set -euo pipefail
cd -P "$1"
for utility in git sha256sum mvn flock pgrep; do command -v "$utility" >/dev/null; done
test "$(git rev-parse HEAD)" = "$2"
git diff --quiet "$2" -- .
test -z "$(git ls-files --others --exclude-standard -- pom.xml tools/ci/resource-guard)"
test "$(git ls-tree -r "$2" -- pom.xml .mvn tools/ci/resource-guard | sha256sum | cut -d ' ' -f 1)" = "$3"
for injected in "${JAVA_TOOL_OPTIONS:-}" "${JDK_JAVA_OPTIONS:-}" "${_JAVA_OPTIONS:-}" "${CLASSPATH:-}"; do test -z "$injected"; done
exec 9>/tmp/cachedb-exclusive-performance.lock
flock -n 9
if pgrep -x java >/dev/null; then echo 'Benchmark container is not idle' >&2; exit 2; fi
test ! -e tools/ci/resource-guard/target
MAVEN_OPTS='-Xmx256m' mvn -B -ntp -f tools/ci/resource-guard/pom.xml -DskipTests clean package </dev/null
git diff --quiet "$2" -- .
REMOTE_GUARD
test "$(bounded 60 "${kube[@]}" get pod "$POD" -o 'jsonpath={.metadata.uid}')" = "$uid"
remote="$WORK/tools/ci/resource-guard/target/resource-guard.jar"
expected=$(bounded 60 "${kube[@]}" exec "$POD" -c "$CONTAINER" -- sha256sum "$remote")
expected=${expected%% *}
[[ "$expected" =~ ^[0-9a-f]{64}$ ]]
bounded 60 "${kube[@]}" exec "$POD" -c "$CONTAINER" -- cat "$remote" > "$RESOURCE_GUARD.partial"
test "$(sha256sum "$RESOURCE_GUARD.partial" | cut -d ' ' -f 1)" = "$expected"
mv "$RESOURCE_GUARD.partial" "$RESOURCE_GUARD"
printf '%s  %s\n' "$expected" "$RESOURCE_GUARD" > "$RESOURCE_GUARD.sha256"
printf 'jarSha256=%s\n' "$expected" >> "$RESOURCE_GUARD.binding.txt"

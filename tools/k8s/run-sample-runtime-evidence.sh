#!/usr/bin/env bash
# K8s only. The parent pins admission.properties; this script never obtains authority.
set -euo pipefail
umask 077
test "$#" -eq 2
admission=$(realpath -e -- "$1")
[[ "$2" =~ ^[0-9a-f]{64}$ ]]
printf '%s  %s\n' "$2" "$admission" | sha256sum --strict --status -c -
test -n "${KUBERNETES_SERVICE_HOST:-}"
get() {
  local values
  values=$(sed -n "s/^$1=//p" "$admission")
  test -n "$values" && [[ "$values" != *$'\n'* && "$values" != *$'\r'* ]]
  printf '%s' "$values"
}
check() {
  local root=$1 manifest=$2 pin=$3 hash member count=0
  [[ "$pin" =~ ^[0-9a-f]{64}$ ]]
  test -f "$manifest" && test ! -L "$manifest" && test "$(stat -c %s "$manifest")" -le 2097152
  printf '%s  %s\n' "$pin" "$manifest" | sha256sum --strict --status -c -
  while IFS= read -r line; do
    [[ "$line" =~ ^([0-9a-f]{64})\ \ (.+)$ ]]
    hash=${BASH_REMATCH[1]}; member=${BASH_REMATCH[2]}
    [[ "$member" != *'..'* && "$member" != *'\'* && "$member" != *$'\r'* && "$member" != *':'* ]]
    if test "$root" != /; then [[ "$member" != /* ]]; fi
    test -f "$root/$member" && test ! -L "$root/$member"
    test "$(realpath -e "$root/$member")" = "${root%/}/$member"
    printf '%s  %s\n' "$hash" "$root/$member" | sha256sum --strict --status -c -
    count=$((count+1)); test "$count" -le 10000
  done < "$manifest"
  test "$count" -gt 0
}
source_root=$(get sourceRoot); sdk_root=$(get sdkRoot); cp_root=$(get classpathRoot)
check "$source_root" "$(get sourceManifest)" "$(get sourceManifestSha256)"
check "$sdk_root" "$(get sdkManifest)" "$(get sdkManifestSha256)"
check / "$(get toolsManifest)" "$(get toolsManifestSha256)"
check "$cp_root" "$(get classpathManifest)" "$(get classpathManifestSha256)"
for key in java javac maven pwsh node npx settings globalSettings; do
  file=$(get "$key")
  [[ "$file" = /* ]] && test -f "$file"
  resolved=$(realpath -e "$file")
  grep -Fqx "$(sha256sum "$resolved" | sed 's@  /@  @')" "$(get toolsManifest)"
  if [[ "$key" != settings && "$key" != globalSettings ]]; then test -x "$file"; fi
done
java=$(get java); javac=$(get javac)
export JAVA_HOME="$(dirname "$(dirname "$java")")"
test "$javac" = "$JAVA_HOME/bin/javac"
export PATH="$(dirname "$java"):$(dirname "$(get maven)"):$(dirname "$(get pwsh)"):$(dirname "$(get node)"):$(dirname "$(get npx)"):/usr/bin:/bin"
test "$(command -v node)" = "$(get node)" && test "$(command -v npx)" = "$(get npx)"
export LC_ALL=C TZ=UTC MAVEN_SKIP_RC=1
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
export MAVEN_OPTS='-Xms64m -Xmx384m -XX:ActiveProcessorCount=1'
export NODE_OPTIONS='--max-old-space-size=256' DOTNET_GCHeapHardLimit=0x8000000
export npm_config_offline=true npm_config_audit=false npm_config_fund=false
export npm_config_cache="$(get npmCache)"
remaining=$(( $(date -u -d "$(get originalCutoff)" +%s) - $(date -u +%s) - 10 ))
test "$remaining" -gt 30
exec 9>/workspace/current-run.lock
flock -n 9
work=$(get workRoot); out=$(get evidenceRoot)
test ! -e "$work" && test ! -e "$out"
mkdir -m 700 "$work" "$out" "$work/classes" "$work/home" "$work/tmp"
export HOME="$work/home" TMPDIR="$work/tmp"
ulimit -c 0
# CoreCLR reserves a sparse 2 TiB anonymous memfd for W^X double mapping.
# RLIMIT_FSIZE also covers that memfd; 128 MiB kills pwsh with SIGXFSZ (153).
# Actual files remain limited to 128 MiB each / 2 GiB total by adapter audits;
# cgroup memory and the coordinator's filesystem quotas remain independent.
ulimit -f 2147483648
ulimit -u 256
trap 'code=$?; printf "%s\n" "$code" > "$out/adapter.exit"' EXIT
for provider in postgres mssql oracle; do
  prefix="CACHEDB_INITIAL_${provider^^}_ADMIN"
  printf -v "${prefix}_URL" '%s' "$(get "${provider}AdminUrl")"
  printf -v "${prefix}_USER" '%s' "$(get "${provider}AdminUser")"
  secret=$(get "${provider}PasswordFile")
  test -f "$secret" && test "$(stat -Lc %s "$secret")" -le 1024
  password=$(cat -- "$secret")
  test -n "$password" && [[ "$password" != *$'\n'* && "$password" != *$'\r'* ]]
  printf -v "${prefix}_PASSWORD" '%s' "$password"
  export "${prefix}_URL" "${prefix}_USER" "${prefix}_PASSWORD"
  unset password
done
cp=''
while IFS= read -r line; do
  member=${line:66}
  [[ "$member" = *.jar ]] && [[ "$member" != *:* ]]
  cp+="${cp:+:}$cp_root/$member"
done < "$(get classpathManifest)"
remaining=$(( $(date -u -d "$(get originalCutoff)" +%s) - $(date -u +%s) - 10 ))
test "$remaining" -gt 0
timeout --signal=TERM --kill-after=10s "$remaining" \
  "$javac" -J-Xmx192m -J-XX:ActiveProcessorCount=1 --release 21 -proc:none -cp "$cp" -d "$work/classes" \
  "$source_root/cachedb-spring-boot-starter/src/test/java/com/cachedb/jdbc/InitialSqlSandbox.java" \
  "$source_root/tools/k8s/SampleRuntimeEvidence.java" > "$out/compile.log" 2>&1
remaining=$(( $(date -u -d "$(get originalCutoff)" +%s) - $(date -u +%s) - 10 ))
test "$remaining" -gt 0
timeout --signal=TERM --kill-after=10s "$remaining" \
  "$java" -Xms32m -Xmx192m -XX:ActiveProcessorCount=1 -cp "$work/classes:$cp" \
  SampleRuntimeEvidence "$admission" "$2" > "$out/adapter.log" 2>&1

#!/usr/bin/env bash
# Run inside a fresh Kubernetes tooling workspace with parent-built dependencies.
set -euo pipefail
umask 077
test "$#" -eq 3 && test -n "${KUBERNETES_SERVICE_HOST:-}"
source_root=$(realpath -e -- "$1")
classpath_manifest=$(realpath -e -- "$2")
out=$3
[[ "$out" = /workspace/* && "$out" != *'..'* ]] && test ! -e "$out"
mkdir -m 700 "$out" "$out/classes"
cp=''
while IFS= read -r jar; do
  [[ "$jar" = /* && "$jar" = *.jar && "$jar" != *:* ]] && test -f "$jar"
  cp+="${cp:+:}$jar"
done < "$classpath_manifest"
test -n "$cp"
timeout --signal=TERM --kill-after=5s 30 javac -J-Xmx192m -J-XX:ActiveProcessorCount=1 \
  --release 21 -proc:none -cp "$cp" -d "$out/classes" \
  "$source_root/cachedb-spring-boot-starter/src/test/java/com/cachedb/jdbc/InitialSqlSandbox.java" \
  "$source_root/tools/k8s/SampleRuntimeEvidence.java" \
  "$source_root/tools/k8s/SampleRuntimeEvidenceContractTest.java" > "$out/compile.log" 2>&1
CACHEDB_CONTRACT_INHERITED_SECRET=not-a-real-secret \
SPRING_PROFILES_ACTIVE=not-a-real-profile \
  timeout --signal=TERM --kill-after=5s 40 java -Xms32m -Xmx192m -XX:ActiveProcessorCount=1 \
    -cp "$out/classes:$cp" SampleRuntimeEvidenceContractTest "$out/fixtures" > "$out/contracts.log" 2>&1
bash -n "$source_root/tools/k8s/run-sample-runtime-evidence.sh"

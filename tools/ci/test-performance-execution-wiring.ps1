#requires -Version 7.2
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$EvidenceDirectory = ''
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$RepositoryRoot = (Get-Item -LiteralPath $RepositoryRoot).FullName
$paths = @(
    '.github/workflows/performance-gate.yml',
    '.github/actions/cachedb-redis-performance/action.yml',
    'tools/ci/prepare-ematech-resource-guard.sh',
    'tools/ci/run-ematech-redis-performance.sh',
    '.github/actions/cachedb-v019-performance/action.yml'
)
$text = @($paths | ForEach-Object { [IO.File]::ReadAllText((Join-Path $RepositoryRoot $_)).Replace("`r`n", "`n") })
$passed = [Collections.Generic.List[string]]::new()
function Require([bool]$Condition, [string]$Message) { if (!$Condition) { throw $Message } }
function Has([string]$Source, [string]$Token) { Require ($Source.Contains($Token)) "Missing wiring contract: $Token" }
function Workflow([string]$Source) {
    foreach ($token in @('name: Redis Performance Gate', '  redis-performance:',
        'runs-on: [self-hosted, linux, cachedb-ematech-control]', 'timeout-minutes: 180',
        'environment: cachedb-performance-ematech', 'group: cachedb-ematech-performance',
        'uses: ./.github/actions/cachedb-redis-performance', 'ref: ${{ github.sha }}',
        'persist-credentials: false', '+ 10800', 'if-no-files-found: error')) { Has $Source $token }
    Require ($Source -notmatch '(?m)^\s*(schedule:|push:|services:)|ubuntu-latest|setup-java|(?m)^\s*(mvn|javac|pwsh)\s') 'Controller build or unattended trigger.'
    foreach ($name in @('namespace','pod','pod_uid','deadline_epoch','container','workspace','redis',
        'baseline_jar','baseline_sha256','cpus','container_cpu_limit','pod_cpu_limit','memory_limit')) {
        Require ($Source -match ('(?m)^      ' + $name + ':\n        description: [^\n]+\n        required: true\n')) "Missing explicit required input: $name"
    }
    Require ($Source.IndexOf('+ 10800') -lt $Source.IndexOf('uses: actions/checkout@')) 'Cap starts after checkout.'
}
function Guard([string]$Source) {
    $remote = [regex]::Match($Source, "(?s)<<'REMOTE_GUARD'\n(.*?)\nREMOTE_GUARD\n")
    Require $remote.Success 'Missing bounded in-pod compilation block.'
    $hostSource = $Source.Remove($remote.Index, $remote.Length)
    Require ($hostSource -notmatch '(?m)^\s*(?:\S+=\S+\s+)*(mvn|javac|pwsh)\s') 'Helper build/test on controller.'
    Has $remote.Groups[1].Value "MAVEN_OPTS='-Xmx256m' mvn -B -ntp -f tools/ci/resource-guard/pom.xml -DskipTests clean package"
    foreach ($token in @('--request-timeout=0 exec -i "$POD" -c "$CONTAINER" --', '"${seconds}s" bash -s --',
        'test "$uid" = "$APPROVED_POD_UID"', 'test "$lifetime" = 21600',
        'test "$((now - start_epoch))" -le 1800', '"$APPROVED_DEADLINE_EPOCH" -le',
        'pom.xml .mvn tools/ci/resource-guard', 'git diff --quiet "$2" -- .',
        '"$RESOURCE_GUARD.partial" | cut', '"$RESOURCE_GUARD.binding.txt"', '"$RESOURCE_GUARD.sha256"')) { Has $Source $token }
}
function Action([string]$Source) {
    Require ($Source -notmatch '(?m)^\s*(mvn|javac|pwsh)\s') 'Build/test on paired controller.'
    foreach ($token in @('APPROVED_POD_UID: ${{ inputs.pod-uid }}',
        'APPROVED_DEADLINE_EPOCH: ${{ inputs.deadline-epoch }}',
        'deadline=$CACHEDB_CI_CAP_EPOCH', 'cutoff=$((deadline - 900))',
        'export CACHEDB_CI_WORK_CUTOFF_EPOCH="$cutoff"', 'tools/ci/prepare-ematech-resource-guard.sh',
        'qualify --container', '--fresh --github-output', 'same --container',
        '"$POD_UID"', '"${left}s" bash -s --', '< tools/ci/run-ematech-redis-performance.sh',
        'DEADLINE - $(date +%s) - 300', 'kb=$(du -sk "$1"', 'test "$resource_match" = true')) { Has $Source $token }
    Require ($Source -notmatch 'kubectl[^\n]*(apply|delete|create)\s') 'Action provisions or deletes resources.'
}
function Worker([string]$Source) {
    foreach ($token in @('test "$(git rev-parse HEAD)" = "$commit"',
        'case "$baseline" in "$PWD"/*) set -- "$@"', 'git ls-files --others --exclude-standard -- "$@"',
        'test "$(sha256sum "$baseline" | cut -d '' '' -f 1)" = "$expected"',
        "['version=0.18.1']", 'cutoff - $(date +%s)', 'trap finish EXIT',
        'export CACHEDB_BENCH_COMPACTOR_DIR=', 'export CACHEDB_BENCH_RESOURCE_CLASSES=',
        'export CACHEDB_BENCH_CPUS="$cpus"', '"$baseline" "$baseline" "$output/aa-control"',
        '"$baseline" "$candidate" "$output/paired"', '"$candidate" "$candidate" "$output/candidate-virtual"',
        '-ProcessorCount "$cpus" -LegacyVirtualLivenessEvidence', '-CandidateVirtualResult',
        'compare-benchmarks.ps1 -SelfTest', 'test-paired-benchmark-merge.ps1', 'git archive --format=tar.gz')) { Has $Source $token }
    $previous = -1
    foreach ($token in @('CACHEDB_BENCH_CASE=miss1', '-Summary "$output/aa-control/comparison.md"',
        'check-legacy-virtual-liveness.ps1', 'CACHEDB_BENCH_CASE=all-comparable', 'CACHEDB_BENCH_CASE=virtual',
        'merge-paired-benchmarks.ps1', 'complete=true')) {
        $index = $Source.IndexOf($token)
        Require ($index -gt $previous) "Gate order missing or changed: $token"
        $previous = $index
    }
    Require ($Source -notmatch 'du -|runtime.version=0.18.1|ThroughputTolerance|P99Tolerance|ContinuationDirectory|continue-on-error') 'Measurement/threshold or retained-baseline bypass.'
}
function Features([string]$Source) {
    $remote = [regex]::Match($Source, "(?s)<<'REMOTE'\n.*?\n        REMOTE\n")
    Require $remote.Success 'Existing feature remote worker missing.'
    $hostSource = $Source.Remove($remote.Index, $remote.Length)
    Require ($hostSource -notmatch '(?m)^\s*(?:\S+=\S+\s+)*(mvn|javac|pwsh)\s') 'Feature helper still builds/tests on controller.'
    foreach ($token in @('bash tools/ci/prepare-ematech-resource-guard.sh',
        'sha256sum --check --strict "$RESOURCE_GUARD.sha256"', '"$RESOURCE_GUARD.binding.txt"',
        'guard_uid=$(cat "$RESOURCE_GUARD.poduid")')) { Has $Source $token }
}
function Control([string]$Name, [scriptblock]$Check, [string]$Source, [bool]$Reject = $false) {
    $rejected = $false
    try { & $Check $Source } catch { if (!$Reject) { throw }; $rejected = $true }
    Require (!$Reject -or $rejected) "Negative control was accepted: $Name"
    $passed.Add($Name)
    Write-Output "PASS $Name"
}
Control 'paired-workflow' ${function:Workflow} $text[0]
Control 'reject-hosted-runner' ${function:Workflow} ($text[0].Replace('runs-on: [self-hosted, linux, cachedb-ematech-control]', 'runs-on: ubuntu-latest')) $true
Control 'reject-missing-approved-uid' ${function:Workflow} ($text[0].Replace('      pod_uid:', '      guessed_uid:')) $true
Control 'guard-built-in-pod' ${function:Guard} $text[2]
Control 'reject-controller-helper-build' ${function:Guard} ($text[2] + "`nmvn package`n") $true
Control 'reject-unbound-helper-source' ${function:Guard} ($text[2].Replace('pom.xml .mvn tools/ci/resource-guard', 'tools/ci/resource-guard')) $true
Control 'reject-helper-stream-short-timeout' ${function:Guard} ($text[2].Replace('--request-timeout=0 exec', '--request-timeout=30s exec')) $true
Control 'paired-action-authority-and-export' ${function:Action} $text[1]
Control 'reject-extended-cap' ${function:Action} ($text[1].Replace('deadline=$CACHEDB_CI_CAP_EPOCH', 'deadline=$((CACHEDB_CI_CAP_EPOCH + 3600))')) $true
Control 'reject-missing-export-reserve' ${function:Action} ($text[1].Replace('cutoff=$((deadline - 900))', 'cutoff=$deadline')) $true
Control 'paired-worker-sequence-and-equality' ${function:Worker} $text[3]
Control 'reject-missing-aa' ${function:Worker} ($text[3].Replace('CACHEDB_BENCH_CASE=miss1', 'CACHEDB_BENCH_CASE=hit')) $true
Control 'reject-mismatched-merger-cpu' ${function:Worker} ($text[3].Replace('-ProcessorCount "$cpus"', '-ProcessorCount 2')) $true
Control 'reject-unconditional-baseline-exclusion' ${function:Worker} ($text[3].Replace('case "$baseline" in "$PWD"/*)', 'case "$baseline" in *)')) $true
Control 'features-guard-relocation' ${function:Features} $text[4]
Control 'reject-feature-controller-build' ${function:Features} ($text[4] + "`nmvn package`n") $true
Control 'reject-unbound-feature-helper' ${function:Features} ($text[4].Replace('sha256sum --check --strict "$RESOURCE_GUARD.sha256"', 'true')) $true
if ($EvidenceDirectory) {
    Require (!(Test-Path -LiteralPath $EvidenceDirectory)) 'Evidence directory must be new.'
    $null = New-Item -ItemType Directory -Path $EvidenceDirectory
    $inputs = @($paths | ForEach-Object {
        [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath (Join-Path $RepositoryRoot $_) -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
    [ordered]@{ schemaVersion = 1; result = 'PASS'; controls = $passed.ToArray(); inputs = $inputs } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'summary.json') -Encoding utf8NoBOM
}
Write-Output "PASS $($passed.Count) performance execution-wiring controls; not a performance acceptance result."

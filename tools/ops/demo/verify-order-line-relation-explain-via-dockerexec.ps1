$ErrorActionPreference = 'Stop'

function Invoke-DockerNative {
    param(
        [Parameter(Mandatory = $true)][string]$Arguments
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
    $psi.Arguments = $Arguments
    $psi.WorkingDirectory = 'C:\Program Files\Docker\Docker\resources\bin'
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    $process = [System.Diagnostics.Process]::Start($psi)
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    if ($process.ExitCode -ne 0) {
        throw "Command failed: docker.exe $Arguments`nSTDOUT:`n$stdout`nSTDERR:`n$stderr"
    }

    return $stdout
}

$raw = Invoke-DockerNative 'exec cachedb-demo-ui bash -lc "wget -qO- \"http://127.0.0.1:8080/api/explain?entity=DemoOrderEntity&include=orderLines&limit=24\""' 
$plan = $raw | ConvertFrom-Json

$fetchRelation = $plan.relationStates | Where-Object { $_.relationName -eq 'orderLines' -and $_.usage -eq 'FETCH' } | Select-Object -First 1
$fetchStep = $plan.steps | Where-Object { $_.stage -eq 'FETCH' -and $_.expression -eq 'orderLines' } | Select-Object -First 1

if (-not $fetchRelation) {
    throw 'Missing FETCH relation state for orderLines.'
}
if ($fetchRelation.status -ne 'BATCH_PRELOAD') {
    throw "Expected BATCH_PRELOAD, got $($fetchRelation.status)"
}
if (-not $fetchStep) {
    throw 'Missing FETCH step for orderLines.'
}

[pscustomobject]@{
    relationStatus = $fetchRelation.status
    relationKind = $fetchRelation.kind
    mappedBy = $fetchRelation.mappedBy
    batchLoadOnly = $fetchRelation.batchLoadOnly
    fetchStrategy = $fetchStep.strategy
    warningCount = @($plan.warnings).Count
} | ConvertTo-Json -Depth 5

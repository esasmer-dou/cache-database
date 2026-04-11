param(
    [Parameter(Mandatory = $true)]
    [string]$GitHubToken,
    [string]$Owner = "esasmer-dou",
    [string]$Repository = "cache-database",
    [string]$Branch = "codex/public-beta-launch",
    [string]$Version = "0.1.0-beta.1",
    [string]$GitUserName = "esasmer-dou",
    [string]$GitUserEmail = "esasmer-dou@users.noreply.github.com",
    [string]$ReleaseTitle = "cache-database public beta"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$gitExe = "C:\Program Files\Git\cmd\git.exe"
$tag = "v$Version"
$httpsRemote = "https://github.com/$Owner/$Repository.git"
$releaseNotesPath = Join-Path $repoRoot "docs\releases\$tag.md"
$bundlePath = Join-Path $repoRoot "target\releases\cache-database-$Version-public-beta.zip"
$releaseApi = "https://api.github.com/repos/$Owner/$Repository/releases"
$curlExe = "C:\Windows\System32\curl.exe"

function Invoke-Git {
    param(
        [string[]]$Arguments,
        [hashtable]$ExtraEnvironment = @{}
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $gitExe
    $psi.Arguments = ($Arguments | ForEach-Object {
            if ($_ -match '[\s"]') {
                '"' + ($_.Replace('"', '\"')) + '"'
            } else {
                $_
            }
        }) -join ' '
    $psi.WorkingDirectory = $repoRoot
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.Environment["SystemRoot"] = "C:\WINDOWS"
    $psi.Environment["ComSpec"] = "C:\WINDOWS\System32\cmd.exe"
    $psi.Environment["Path"] = "C:\WINDOWS\system32;C:\WINDOWS;C:\Program Files\Git\cmd;C:\Program Files\Git\mingw64\bin;C:\Program Files\Git\usr\bin"

    foreach ($key in $ExtraEnvironment.Keys) {
        $psi.Environment[$key] = [string]$ExtraEnvironment[$key]
    }

    $process = [System.Diagnostics.Process]::Start($psi)
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    if (-not [string]::IsNullOrWhiteSpace($stdout)) {
        Write-Host $stdout.TrimEnd()
    }
    if (-not [string]::IsNullOrWhiteSpace($stderr)) {
        Write-Host $stderr.TrimEnd()
    }
    if ($process.ExitCode -ne 0) {
        throw "git failed: $($Arguments -join ' ')"
    }
}

function New-AskPassScript {
    $askPassPath = Join-Path ([System.IO.Path]::GetTempPath()) ("cachedb-git-askpass-" + [System.Guid]::NewGuid().ToString("N") + ".cmd")
    $content = @'
@echo off
echo %~1 | findstr /I "Username" >nul
if %ERRORLEVEL%==0 (
  echo x-access-token
) else (
  echo %GITHUB_TOKEN%
)
'@
    Set-Content -Path $askPassPath -Value $content -Encoding ASCII
    return $askPassPath
}

function New-GitHubClient {
    $client = [System.Net.Http.HttpClient]::new()
    $client.DefaultRequestHeaders.Add("User-Agent", "cache-database-release-script")
    $client.DefaultRequestHeaders.Add("Authorization", "Bearer $GitHubToken")
    $client.DefaultRequestHeaders.Add("Accept", "application/vnd.github+json")
    return $client
}

function Invoke-Curl {
    param(
        [string[]]$Arguments,
        [int]$TimeoutMs = 300000
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $curlExe
    $psi.Arguments = ($Arguments | ForEach-Object {
            if ($_ -match '[\s"]') {
                '"' + ($_.Replace('"', '\"')) + '"'
            } else {
                $_
            }
        }) -join ' '
    $psi.WorkingDirectory = $repoRoot
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $psi.Environment["SystemRoot"] = "C:\WINDOWS"
    $psi.Environment["ComSpec"] = "C:\WINDOWS\System32\cmd.exe"
    $psi.Environment["Path"] = "C:\WINDOWS\system32;C:\WINDOWS;" +
            [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" +
            [System.Environment]::GetEnvironmentVariable("Path", "User")

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $psi
    [void]$process.Start()
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()

    if (-not $process.WaitForExit($TimeoutMs)) {
        try { $process.Kill($true) } catch {}
        throw "curl timed out: $($Arguments -join ' ')"
    }

    $stdout = $stdoutTask.Result
    $stderr = $stderrTask.Result
    if ($process.ExitCode -ne 0) {
        throw "curl failed: $($Arguments -join ' ')`n$stdout`n$stderr"
    }
    return [pscustomobject]@{
        StdOut = $stdout
        StdErr = $stderr
    }
}

function Invoke-GitHubApiCurl {
    param(
        [string]$Method,
        [string]$Url,
        [string]$Body,
        [string]$ContentType = "application/json"
    )

    $responseFile = Join-Path ([System.IO.Path]::GetTempPath()) ("cachedb-api-response-" + [System.Guid]::NewGuid().ToString("N") + ".json")
    $bodyFile = $null
    try {
        $args = @(
            "-sS",
            "-X", $Method,
            "-H", "User-Agent: cache-database-release-script",
            "-H", "Authorization: Bearer $GitHubToken",
            "-H", "Accept: application/vnd.github+json",
            "-o", $responseFile,
            "-w", "%{http_code}"
        )
        if ($null -ne $Body) {
            $bodyFile = Join-Path ([System.IO.Path]::GetTempPath()) ("cachedb-api-body-" + [System.Guid]::NewGuid().ToString("N") + ".json")
            [System.IO.File]::WriteAllText($bodyFile, $Body, [System.Text.Encoding]::UTF8)
            $args += @("-H", "Content-Type: $ContentType", "--data-binary", "@$bodyFile")
        }
        $args += $Url
        $result = Invoke-Curl -Arguments $args
        $statusCodeText = $result.StdOut.Trim()
        $statusCode = 0
        [void][int]::TryParse($statusCodeText, [ref]$statusCode)
        $responseBody = if (Test-Path $responseFile) { [System.IO.File]::ReadAllText($responseFile) } else { "" }
        return [pscustomobject]@{
            StatusCode = $statusCode
            Body = $responseBody
        }
    } finally {
        if ($bodyFile -and (Test-Path $bodyFile)) {
            Remove-Item -Force $bodyFile
        }
        if (Test-Path $responseFile) {
            Remove-Item -Force $responseFile
        }
    }
}

function Upload-ReleaseAssetCurl {
    param(
        [string]$UploadUrl,
        [string]$AssetPath
    )

    $args = @(
        "-sS",
        "-X", "POST",
        "-H", "User-Agent: cache-database-release-script",
        "-H", "Authorization: Bearer $GitHubToken",
        "-H", "Accept: application/vnd.github+json",
        "-H", "Content-Type: application/zip",
        "--data-binary", "@$AssetPath",
        $UploadUrl
    )
    $result = Invoke-Curl -Arguments $args -TimeoutMs 900000
    return $result.StdOut | ConvertFrom-Json
}

if (-not (Test-Path $gitExe)) {
    throw "git.exe not found at $gitExe"
}
if (-not (Test-Path $releaseNotesPath)) {
    throw "Release notes file not found: $releaseNotesPath"
}
if (-not (Test-Path $bundlePath)) {
    throw "Release bundle not found: $bundlePath"
}

if (-not (Test-Path (Join-Path $repoRoot ".git"))) {
    Invoke-Git -Arguments @("init", "-b", $Branch)
}

Invoke-Git -Arguments @("config", "user.name", $GitUserName)
Invoke-Git -Arguments @("config", "user.email", $GitUserEmail)

$askPass = New-AskPassScript
try {
    $authEnv = @{
        "GIT_ASKPASS" = $askPass
        "GITHUB_TOKEN" = $GitHubToken
        "GIT_TERMINAL_PROMPT" = "0"
    }

    $remoteExists = $false
    try {
        Invoke-Git -Arguments @("remote", "get-url", "origin")
        $remoteExists = $true
    } catch {
        $remoteExists = $false
    }

    if ($remoteExists) {
        Invoke-Git -Arguments @("remote", "set-url", "origin", $httpsRemote)
    } else {
        Invoke-Git -Arguments @("remote", "add", "origin", $httpsRemote)
    }

    Invoke-Git -Arguments @("checkout", "-B", $Branch)
    Invoke-Git -Arguments @("add", ".")

    $commitSucceeded = $true
    try {
        Invoke-Git -Arguments @("commit", "-m", "Prepare public beta release $tag")
    } catch {
        $commitSucceeded = $false
        Write-Host "No new commit created; continuing with current branch state."
    }

    try {
        Invoke-Git -Arguments @("tag", "-a", $tag, "-m", $ReleaseTitle)
    } catch {
        Write-Host "Tag $tag already exists locally; continuing."
    }

    Invoke-Git -Arguments @("push", "-u", "origin", $Branch) -ExtraEnvironment $authEnv
    Invoke-Git -Arguments @("push", "origin", $tag) -ExtraEnvironment $authEnv

    $releaseBody = Get-Content $releaseNotesPath -Raw
    $existingReleaseResponse = Invoke-GitHubApiCurl -Method "GET" -Url "$releaseApi/tags/$tag" -Body $null
    $existingRelease = $null
    if ($existingReleaseResponse.StatusCode -eq 200 -and -not [string]::IsNullOrWhiteSpace($existingReleaseResponse.Body)) {
        $existingRelease = $existingReleaseResponse.Body | ConvertFrom-Json
    } elseif ($existingReleaseResponse.StatusCode -ne 404) {
        throw "Failed to read release state: $($existingReleaseResponse.Body)"
    }

    if ($null -eq $existingRelease) {
        $payload = @{
            tag_name         = $tag
            target_commitish = $Branch
            name             = $ReleaseTitle
            body             = $releaseBody
            draft            = $false
            prerelease       = $true
        } | ConvertTo-Json -Depth 8

        $response = Invoke-GitHubApiCurl -Method "POST" -Url $releaseApi -Body $payload
        if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 300) {
            throw "Failed to create release: $($response.Body)"
        }
        $release = $response.Body | ConvertFrom-Json
    } else {
        $payload = @{
            tag_name    = $tag
            name        = $ReleaseTitle
            body        = $releaseBody
            draft       = $false
            prerelease  = $true
        } | ConvertTo-Json -Depth 8

        $response = Invoke-GitHubApiCurl -Method "PATCH" -Url "$releaseApi/$($existingRelease.id)" -Body $payload
        if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 300) {
            throw "Failed to update release: $($response.Body)"
        }
        $release = $response.Body | ConvertFrom-Json
    }

    $assetName = Split-Path -Leaf $bundlePath
    foreach ($asset in $release.assets) {
        if ($asset.name -eq $assetName) {
            $deleteResponse = Invoke-GitHubApiCurl -Method "DELETE" -Url "https://api.github.com/repos/$Owner/$Repository/releases/assets/$($asset.id)" -Body $null
            if ($deleteResponse.StatusCode -lt 200 -or $deleteResponse.StatusCode -ge 300) {
                throw "Failed to delete existing asset $assetName"
            }
        }
    }

    $uploadUrl = (($release.upload_url -replace '\{.*$', '') + "?name=$assetName")
    [void](Upload-ReleaseAssetCurl -UploadUrl $uploadUrl -AssetPath $bundlePath)

    Write-Host "Release published successfully."
    Write-Host "Branch: $Branch"
    Write-Host "Tag: $tag"
    Write-Host "Asset: $bundlePath"
} finally {
    if (Test-Path $askPass) {
        Remove-Item -Force $askPass
    }
}

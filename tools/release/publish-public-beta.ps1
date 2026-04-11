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
    $client = New-GitHubClient
    try {
        $existingResponse = $client.GetAsync("$releaseApi/tags/$tag").GetAwaiter().GetResult()
        $existingRelease = $null
        if ($existingResponse.IsSuccessStatusCode) {
            $existingRelease = $existingResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
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

            $response = $client.PostAsync(
                $releaseApi,
                [System.Net.Http.StringContent]::new($payload, [System.Text.Encoding]::UTF8, "application/json")
            ).GetAwaiter().GetResult()

            if (-not $response.IsSuccessStatusCode) {
                throw "Failed to create release: $($response.Content.ReadAsStringAsync().GetAwaiter().GetResult())"
            }

            $release = ($response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json)
        } else {
            $payload = @{
                tag_name    = $tag
                name        = $ReleaseTitle
                body        = $releaseBody
                draft       = $false
                prerelease  = $true
            } | ConvertTo-Json -Depth 8

            $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Patch, "$releaseApi/$($existingRelease.id)")
            $request.Content = [System.Net.Http.StringContent]::new($payload, [System.Text.Encoding]::UTF8, "application/json")
            $response = $client.SendAsync($request).GetAwaiter().GetResult()
            if (-not $response.IsSuccessStatusCode) {
                throw "Failed to update release: $($response.Content.ReadAsStringAsync().GetAwaiter().GetResult())"
            }
            $release = ($response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json)
        }

        $assetName = Split-Path -Leaf $bundlePath
        foreach ($asset in $release.assets) {
            if ($asset.name -eq $assetName) {
                $deleteResponse = $client.DeleteAsync("https://api.github.com/repos/$Owner/$Repository/releases/assets/$($asset.id)").GetAwaiter().GetResult()
                if (-not $deleteResponse.IsSuccessStatusCode) {
                    throw "Failed to delete existing asset $assetName"
                }
            }
        }

        $uploadUrl = (($release.upload_url -replace '\{.*$', '') + "?name=$assetName")
        $uploadClient = [System.Net.Http.HttpClient]::new()
        $uploadClient.DefaultRequestHeaders.Add("User-Agent", "cache-database-release-script")
        $uploadClient.DefaultRequestHeaders.Add("Authorization", "Bearer $GitHubToken")
        $uploadClient.DefaultRequestHeaders.Add("Accept", "application/vnd.github+json")
        $bytes = [System.IO.File]::ReadAllBytes($bundlePath)
        $content = [System.Net.Http.ByteArrayContent]::new($bytes)
        $content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/zip")
        $uploadResponse = $uploadClient.PostAsync($uploadUrl, $content).GetAwaiter().GetResult()
        if (-not $uploadResponse.IsSuccessStatusCode) {
            throw "Failed to upload release asset: $($uploadResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult())"
        }

        Write-Host "Release published successfully."
        Write-Host "Branch: $Branch"
        Write-Host "Tag: $tag"
        Write-Host "Asset: $bundlePath"
    } finally {
        $client.Dispose()
    }
} finally {
    if (Test-Path $askPass) {
        Remove-Item -Force $askPass
    }
}

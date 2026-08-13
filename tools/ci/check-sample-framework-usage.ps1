param(
    [string[]] $SampleRoots = @(
        "./sample-cache-database-postgresql",
        "./sample-cache-database-mssql"
    ),
    [string] $SummaryPath = "./target/sample-framework-usage-summary.md"
)

$ErrorActionPreference = "Stop"
$violations = [System.Collections.Generic.List[string]]::new()
$checkedFiles = 0

function Add-Matches {
    param(
        [System.IO.FileInfo[]] $Files,
        [string] $Pattern,
        [string] $Rule
    )
    foreach ($file in $Files) {
        $matches = Select-String -LiteralPath $file.FullName -Pattern $Pattern
        foreach ($match in $matches) {
            $relative = [System.IO.Path]::GetRelativePath((Get-Location).Path, $file.FullName)
            $violations.Add("${relative}:$($match.LineNumber) [$Rule] $($match.Line.Trim())")
        }
    }
}

foreach ($sampleRoot in $SampleRoots) {
    $resolvedRoot = (Resolve-Path -LiteralPath $sampleRoot).Path
    $mainJava = Join-Path $resolvedRoot "src/main/java"
    $application = Get-ChildItem -LiteralPath (Join-Path $mainJava "com/example/cachedb/sample/application") -Recurse -Filter "*.java"
    $businessApplication = $application | Where-Object { $_.FullName -notmatch '[\\/]application[\\/]ops[\\/]' }
    $web = Get-ChildItem -LiteralPath (Join-Path $mainJava "com/example/cachedb/sample/web") -Recurse -Filter "*.java"
    $allJava = Get-ChildItem -LiteralPath $mainJava -Recurse -Filter "*.java"
    $checkedFiles += $allJava.Count

    Add-Matches $application 'import\s+com\.reactor\.cachedb\.(core\.api|redis|jdbc)\.' "application-internal-api"
    Add-Matches $businessApplication 'import\s+com\.reactor\.cachedb\.starter\.CacheDatabase;' "application-bootstrap-leak"
    Add-Matches $application 'CacheBinding|CacheDbImplementation|CacheDbConfiguration' "application-generated-type-leak"
    Add-Matches $application '\.findVersionedHotById\(' "manual-optimistic-update"
    Add-Matches $web 'import\s+com\.example\.cachedb\.sample\.repository\.' "controller-repository-leak"
    Add-Matches $web 'import\s+com\.reactor\.cachedb\.(redis|jdbc|core\.api)\.' "controller-internal-api"
    Add-Matches $allJava 'import\s+(jakarta\.persistence|org\.springframework\.data\.jpa)\.' "competing-orm-leak"

    $generatedSources = $allJava | Where-Object {
        $_.Name -match '(CacheBinding|CacheDbImplementation|CacheDbConfiguration)\.java$'
    }
    foreach ($file in $generatedSources) {
        $relative = [System.IO.Path]::GetRelativePath((Get-Location).Path, $file.FullName)
        $violations.Add("$relative [checked-in-generated-source] generated CacheDB sources belong under target/generated-sources")
    }

    $repositoryFiles = Get-ChildItem -LiteralPath (Join-Path $mainJava "com/example/cachedb/sample/repository") -Filter "*Repository.java" |
        Where-Object { $_.Name -ne "SampleRepositories.java" }
    foreach ($repository in $repositoryFiles) {
        $content = Get-Content -LiteralPath $repository.FullName -Raw
        if ($content -notmatch '@CacheRepository\s*\(') {
            $relative = [System.IO.Path]::GetRelativePath((Get-Location).Path, $repository.FullName)
            $violations.Add("$relative [missing-cache-repository] repository interfaces must be compile-time generated")
        }

        $warmSources = @([regex]::Matches($content, 'from\s*=\s*"([A-Za-z_$][A-Za-z0-9_$]*)"') |
            ForEach-Object { $_.Groups[1].Value })
        $hotMethods = @([regex]::Matches(
                $content,
                '(?s)@HotRoute\s*\(.*?\)\s*@CacheRouteQuery\s*\(.*?\)\s*(?:HotWindow|CursorPage)<[^>]+>\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*\('
            ) | ForEach-Object { $_.Groups[1].Value })
        foreach ($hotMethod in $hotMethods) {
            if ($warmSources -notcontains $hotMethod) {
                $relative = [System.IO.Path]::GetRelativePath((Get-Location).Path, $repository.FullName)
                $violations.Add(
                    "$relative [missing-warm-route] hot route '$hotMethod' has no bounded @WarmRoute coverage plan"
                )
            }
        }
    }

    Add-Matches $repositoryFiles 'windowParameter\s*=|limitParameter\s*=|maxRowsParameter\s*=|targetParameter\s*=' `
        "redundant-generated-role-binding"
    Add-Matches $repositoryFiles '@CacheLookup\s*\([^)]*(idParameter|relationLimitParameter)\s*=' `
        "redundant-lookup-role-binding"
    Add-Matches $application '\.(completePage|page)\(\)' "application-window-conversion"
}

$summary = @(
    "# Sample Framework Usage",
    "",
    "- Java files checked: $checkedFiles",
    "- Sample roots: $($SampleRoots.Count)",
    "- Violations: $($violations.Count)"
)
if ($violations.Count -eq 0) {
    $summary += "- Result: PASS"
} else {
    $summary += "- Result: FAIL"
    $summary += ""
    $summary += "## Violations"
    $summary += $violations | ForEach-Object { "- $_" }
}

$summaryFile = [System.IO.Path]::GetFullPath($SummaryPath)
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($summaryFile)) | Out-Null
Set-Content -LiteralPath $summaryFile -Value $summary -Encoding utf8

if ($violations.Count -gt 0) {
    $violations | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Host "Verified declarative framework boundaries across $checkedFiles sample Java files."

param(
    [string]$SummaryPath = "target/readme-quality-summary.md"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$summaryFullPath = Join-Path $repoRoot $SummaryPath
$summaryDirectory = Split-Path -Parent $summaryFullPath
$findings = [System.Collections.Generic.List[string]]::new()
[xml]$rootPom = Get-Content -LiteralPath (Join-Path $repoRoot "pom.xml")
$versionPattern = [regex]::Escape([string]$rootPom.project.version)

function Require-Text {
    param(
        [Parameter(Mandatory = $true)][string]$RelativePath,
        [Parameter(Mandatory = $true)][string[]]$Patterns
    )

    $path = Join-Path $repoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        $findings.Add("$RelativePath is missing")
        return
    }

    $content = Get-Content -LiteralPath $path -Raw
    foreach ($pattern in $Patterns) {
        if ($content -notmatch $pattern) {
            $findings.Add("$RelativePath is missing required contract: $pattern")
        }
    }
}

Require-Text -RelativePath "README.md" -Patterns @(
    'Latest published release',
    $versionPattern,
    'cachedb-spring-boot-starter-postgres',
    'cachedb-spring-boot-starter-mssql',
    '## Ten-Minute Learning Path',
    '## Production Checklist'
)

Require-Text -RelativePath "tr/README.md" -Patterns @(
    'Yayımlanmış son sürüm',
    $versionPattern,
    'cachedb-spring-boot-starter-postgres',
    'cachedb-spring-boot-starter-mssql',
    '## On Dakikalık Öğrenme Akışı',
    '## Production''a Yakın Kullanım İçin Kısa Kontrol Listesi'
)

Require-Text -RelativePath "cachedb-examples/README.md" -Patterns @(
    '## Choose the Right Sample',
    '## First Successful Run',
    '## Evidence Boundaries',
    '## Troubleshooting'
)

Require-Text -RelativePath "tr/cachedb-examples/README.md" -Patterns @(
    '## Doğru Örneği Seç',
    '## İlk Başarılı Çalıştırma',
    '## Kanıtın Sınırı',
    '## Sorun Giderme'
)

Require-Text -RelativePath "cachedb-production-tests/README.md" -Patterns @(
    '## Start Here',
    '## Capacity Benchmarks',
    '## Failure and Recovery',
    '## Production Gate'
)

Require-Text -RelativePath "tr/cachedb-production-tests/README.md" -Patterns @(
    '## Buradan Başla',
    '## Kapasite Benchmark''ları',
    '## Hata ve Toparlanma',
    '## Production Kapısı'
)

$samples = @(
    @{ Root = 'sample-cache-database-postgresql'; Provider = 'postgres'; Port = '8091'; Database = 'PostgreSQL' },
    @{ Root = 'sample-cache-database-mssql'; Provider = 'mssql'; Port = '8092'; Database = 'SQL Server' }
)

foreach ($sample in $samples) {
    $root = [string]$sample.Root
    $provider = [string]$sample.Provider
    $port = [string]$sample.Port
    $database = [string]$sample.Database

    Require-Text -RelativePath "$root/README.md" -Patterns @(
        "cachedb-spring-boot-starter-$provider",
        'SPRING_PROFILES_ACTIVE.*demo',
        "127\.0\.0\.1:$port",
        '## Runtime Contract',
        '## Code Walkthrough',
        '## Warm Existing Data',
        '## Tuning by Use Case',
        '## Production Checklist',
        '## Troubleshooting',
        [regex]::Escape($database)
    )

    Require-Text -RelativePath "$root/README.tr.md" -Patterns @(
        "cachedb-spring-boot-starter-$provider",
        'SPRING_PROFILES_ACTIVE.*demo',
        "127\.0\.0\.1:$port",
        '## Çalışma Zamanı Sözleşmesi',
        '## Kod Üzerinden Akış',
        '## Mevcut Veriyi Hazırlama',
        '## Kullanım Senaryosuna Göre Ayar',
        '## Canlı Ortam Kontrol Listesi',
        '## Sorun Giderme',
        [regex]::Escape($database)
    )
}

if (-not (Test-Path -LiteralPath $summaryDirectory)) {
    New-Item -ItemType Directory -Path $summaryDirectory -Force | Out-Null
}

$summary = @(
    '# README Quality Summary',
    '',
    "Findings: $($findings.Count)",
    ''
)
if ($findings.Count -eq 0) {
    $summary += '- root and sample README contracts are present'
} else {
    $summary += '## Findings'
    $summary += ''
    $summary += $findings | ForEach-Object { "- $_" }
}

Set-Content -LiteralPath $summaryFullPath -Value ($summary -join [Environment]::NewLine)

if ($findings.Count -gt 0) {
    $findings | ForEach-Object { Write-Host $_ }
    throw "README quality validation failed."
}

Write-Host "README quality validation passed."
Write-Host "Summary written to $summaryFullPath"

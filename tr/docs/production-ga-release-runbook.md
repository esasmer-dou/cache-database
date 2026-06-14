# Production GA Release Runbook

Bu akış public beta release akışından bilerek daha serttir. CacheDB ancak her
route, provider, staging topolojisi ve imzalı artifact kapısı yeşil olduğunda
GA olarak duyurulmalıdır.

## Taviz Verilmeyecek Kural

Aşağıdakilerden biri eksikse production GA release yayınlama veya duyurma:

- production'daki her ekran, API, batch, worker ve rapor route'u için tam
  migration coverage
- gerçek Redis ve kaynak veritabanı topolojisiyle staging Redis HA kanıtı
- GA iddiasına MSSQL dahilse staging MSSQL HA kanıtı
- source ve javadoc artifact'leriyle imzalı Maven Central publish
- yeşil public API compatibility ve benchmark regression kapıları
- gateway auth veya CacheDB token auth arkasında açık admin exposure kararı

## Gerekli Repository Secret'ları

GA workflow'larını çalıştırmadan önce GitHub repository secret'larına şunları
ekle:

```text
CENTRAL_USERNAME
CENTRAL_PASSWORD
GPG_PRIVATE_KEY
GPG_PASSPHRASE
STAGING_REDIS_URI
STAGING_POSTGRES_URL
STAGING_POSTGRES_USER
STAGING_POSTGRES_PASSWORD
STAGING_MSSQL_URL
STAGING_MSSQL_USER
STAGING_MSSQL_PASSWORD
```

Production release iddiası MSSQL'i kapsıyorsa MSSQL secret'ları zorunludur.
Release yalnızca PostgreSQL kapsamındaysa MSSQL'i GA duyurusuna ve yayınlanan
destek matrisine dahil etme.

## Adım Adım GA Akışı

1. `1.0.0` gibi stabil bir sürüm hazırla. GA sürümünde `beta`, `alpha`, `rc`,
   `preview` veya `SNAPSHOT` kullanma.
2. [ga-migration-coverage-template.csv](../../docs/ga-migration-coverage-template.csv)
   dosyasından `docs/ga-migration-coverage.csv` üret. Her production route'u
   için owner, query shape, CacheDB shape, warm status, compare status, cutover
   status ve rollback planı dolu olmalıdır.
3. Release commit'ini `main` branch'ine gönder ve aynı commit üzerinde
   `Public Beta Readiness` ile `Production Evidence` workflow'larının
   geçtiğini doğrula.
4. GitHub Actions üzerinden `Production GA Staging Evidence` workflow'unu
   `docs/ga-migration-coverage.csv` ile çalıştır. Bekleme pencerelerinde
   yönetilen Redis failover'ını ve MSSQL kapsamdaysa SQL Server HA veya
   Always On failover'ını tetikle.
5. Stabil tag'i oluştur ve gönder; örnek: `v1.0.0`.
6. Stabil tag üzerinde `Maven Central Publish` workflow'unu manuel olarak
   `gaRelease=true` ile çalıştır. Workflow, imzalı artifact publish etmeden
   önce GA preflight kontrolünü çalıştırır.
7. Aynı tag ve coverage CSV için `Production GA Release Readiness`
   workflow'unu çalıştır.
8. GitHub release'i yalnızca readiness özeti `PASS` ise yayınla.

## Lokal Ön Kontrol

Tag oluştuktan sonra operatör şu komutu çalıştırabilir:

```powershell
pwsh ./tools/ci/check-ga-release-readiness.ps1 `
  -Repository esasmer-dou/cache-database `
  -TargetRef main `
  -ReleaseTag v1.0.0 `
  -CoverageCsvPath docs/ga-migration-coverage.csv `
  -CheckGitHubSecrets
```

Repository secret'ları, staging evidence, Maven Central publish ve tam
migration coverage yoksa bu komutun başarısız olması beklenir.

Maven Central'a imzalı artifact göndermek için, Maven dışındaki tüm GA kapıları
yeşil olduktan sonra şu komutu çalıştır:

```powershell
gh workflow run maven-central-publish.yml `
  --repo esasmer-dou/cache-database `
  --ref v1.0.0 `
  -f gaRelease=true `
  -f releaseTag=v1.0.0 `
  -f targetRef=main `
  -f migrationCoverageCsvPath=docs/ga-migration-coverage.csv
```

## Production Kararı

BEST: GA release'i yalnızca `Production GA Release Readiness` yeşilken çıkar.

ACCEPTABLE: seçili production pilotları route bazlı coverage, rollback ve
staging evidence ile ilerlerken public beta release yayınlamaya devam et.

ANTI-PATTERN: unit testler, lokal Docker testleri veya public beta readiness
workflow'u geçtiği için beta build'i GA olarak yeniden adlandırmak.

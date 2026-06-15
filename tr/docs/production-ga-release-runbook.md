# Production GA Release Runbook

Bu akış public beta release akışından bilerek daha serttir. CacheDB için
framework GA demek, kütüphane release'inin net sınırlar, yeşil CI kanıtı ve
belgelenmiş bir dağıtım kanalıyla güvenle tüketilebilir olması demektir. Bu,
her kullanıcının kendi production topolojisinin otomatik olarak sertifikalandığı
anlamına gelmez.

## Taviz Verilmeyecek Kural

Aşağıdakilerden biri eksikse framework GA release yayınlama veya duyurma:

- Redis coordination ve SQL provider reconnect davranışı için lokal Docker veya
  CI outage/restart kanıtı
- belgelenmiş resmi dağıtım yolu. Şu an seçilen yol: GitHub Release artifact
- Maven Central seçildiyse source ve javadoc artifact'leriyle imzalı Maven
  Central publish
- yeşil public API compatibility ve benchmark regression kapıları
- gateway auth veya CacheDB token auth arkasında açık admin exposure kararı

Bir uygulama production trafiğini CacheDB'ye kesecekse, ayrıca tam route
coverage ve gerçek staging HA kanıtı gerekir.

## Gerekli Repository Secret'ları

GitHub repository secret'larını yalnızca kullandığın opsiyonel kapılar için
ekle. Maven Central için:

```text
CENTRAL_USERNAME
CENTRAL_PASSWORD
GPG_PRIVATE_KEY
GPG_PASSPHRASE
```

Managed staging HA kanıtı için:

```text
STAGING_REDIS_URI
STAGING_POSTGRES_URL
STAGING_POSTGRES_USER
STAGING_POSTGRES_PASSWORD
STAGING_MSSQL_URL
STAGING_MSSQL_USER
STAGING_MSSQL_PASSWORD
```

MSSQL staging secret'ları yalnızca release iddiası managed SQL Server HA veya
Always On kanıtını kapsıyorsa zorunludur. Aksi durumda MSSQL desteğini topology
sertifikası olarak değil, Docker restart/reconnect ve provider evidence olarak
anlat.

## Adım Adım GA Akışı

1. `0.1.0` veya `1.0.0` gibi stabil bir sürüm hazırla. GA sürümünde `beta`,
   `alpha`, `rc`, `preview` veya `SNAPSHOT` kullanma.
2. Lokal Docker HA preflight'i çalıştır:

   ```powershell
   pwsh ./tools/ci/run-local-docker-ha-preflight.ps1
   ```

   Bu komut Redis, PostgreSQL ve SQL Server container'larını başlatır; Redis
   outage/recovery evidence ve SQL Server restart/reconnect evidence çalıştırır.
3. Release iddiası MSSQL listener/failover davranışını içeriyorsa ama ortak
   staging Always On ortamında isteğe bağlı failover tetikleyemiyorsan lokal
   listener preflight'i çalıştır:

   ```powershell
   pwsh ./tools/ci/run-local-mssql-listener-failover-evidence.ps1
   ```

   Bu test, sabit listener endpoint'i üzerinden eski JDBC connection'ın
   geçersiz kaldığını ve yeni connection'ın yeni backend'e gittiğini kanıtlar.
   Always On replikasyonunun, quorum davranışının veya yönetilen failover
   politikasının yerine geçmez.
4. Release commit'ini `main` branch'ine gönder ve aynı commit üzerinde
   `Public Beta Readiness` ile `Production Evidence` workflow'larının
   geçtiğini doğrula.
5. Resmi GitHub Release artifact'ini hedef commit'ten üret:

   ```powershell
   pwsh ./tools/release/build-release-package.ps1 `
     -Version 0.1.0 `
     -PackageLabel github-release
   ```

   Stabil release için `github-release` gibi beta içermeyen bir package label
   kullan.
6. Stabil tag'i oluştur ve gönder; örnek: `v0.1.0`.
7. Maven Central resmi dağıtım kanalı olarak seçildiyse, stabil tag üzerinde
   `Maven Central Publish` workflow'unu manuel olarak
   `gaRelease=true` ile çalıştır. Workflow, imzalı artifact publish etmeden
   önce GA preflight kontrolünü çalıştırır.
8. Aynı tag için `Production GA Release Readiness` workflow'unu çalıştır.
   `requireManagedStagingHa`, `requireApplicationMigrationCoverage` veya
   `requireMavenCentralPublish` seçeneklerini yalnızca release iddiası bu
   opsiyonel kapıları kapsıyorsa aç.
9. GitHub release'i yalnızca readiness özeti `PASS` ise yayınla ve resmi
   release artifact'ini ekle.

## Lokal Ön Kontrol

Tag oluştuktan sonra operatör şu komutu çalıştırabilir:

```powershell
pwsh ./tools/ci/check-ga-release-readiness.ps1 `
  -Repository esasmer-dou/cache-database `
  -TargetRef main `
  -ReleaseTag v0.1.0
```

Bu komut framework-level GA hazırlığını kontrol eder. Release iddiası
gerektiriyorsa şu opsiyonel flag'leri ayrıca ekle:

```powershell
-RequireMavenCentralPublish
-RequireManagedStagingHa
-RequireApplicationMigrationCoverage -CoverageCsvPath docs/ga-migration-coverage.csv
```

Maven Central resmi dağıtım kanalı olarak seçildiyse ve Maven dışındaki tüm GA
kapıları yeşilse şu komutu çalıştır:

```powershell
gh workflow run maven-central-publish.yml `
  --repo esasmer-dou/cache-database `
  --ref v0.1.0 `
  -f gaRelease=true `
  -f releaseTag=v0.1.0 `
  -f targetRef=main `
  -f migrationCoverageCsvPath=docs/ga-migration-coverage.csv
```

## Production Kararı

BEST: GA release'i yalnızca `Production GA Release Readiness` yeşilken ve
GitHub Release artifact'i resmi dağıtım paketi olarak eklenmişken çıkar.

ACCEPTABLE: framework GA'yı Docker/CI outage evidence ve açık sınırlarla çıkar;
uygulamalar ise production cutover öncesinde route bazlı coverage, rollback ve
staging HA evidence çalıştırmaya devam eder.

ANTI-PATTERN: unit testler, lokal Docker testleri veya public beta readiness
workflow'u geçtiği için beta build'i GA olarak yeniden adlandırmak.

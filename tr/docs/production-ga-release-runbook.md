# Production GA Release Runbook

Bu akış CacheDB'nin tam Production GA onayı için zorunlu sürüm sürecidir.
framework GA demek, kütüphane release'inin net sınırlar, yeşil CI kanıtı ve
belgelenmiş bir dağıtım kanalıyla güvenle tüketilebilir olması demektir. Bu,
her kullanıcının kendi production topolojisinin otomatik olarak sertifikalandığı
anlamına gelmez.

## Taviz Verilmeyecek Kural

Numaralandırılmış framework paketleri, tam GA onayı iddia edilmeden dağıtılabilir.
Bu dağıtım kontrolü için `check-ga-release-readiness.ps1 -EvidenceScope FrameworkPackage`
kullanılır; eksik topoloji kanıtı sürüm notlarında açıkça belirtilir.
Varsayılan `ProductionGA` kapsamı daha sıkıdır: aynı commit üzerindeki fiziksel
Data Guard workflow kanıtı zorunludur. Paket kontrolü bu GA şartını kaldırmaz.

Aşağıdakilerden biri eksikse framework GA release yayınlama veya duyurma:

- Redis coordination ve SQL provider reconnect davranışı için lokal Docker veya
  CI outage/restart kanıtı
- Oracle desteği sürüm kapsamındaysa self-hosted fiziksel Data Guard kanıtı
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
STAGING_ORACLE_URL
STAGING_ORACLE_USER
STAGING_ORACLE_PASSWORD
```

Provider staging secret'ları yalnızca sürüm iddiası ilgili yönetilen HA
topolojisini kapsıyorsa zorunludur. Yerel fiziksel Data Guard hattı, temsilî
framework kanıtı olarak raporlanabilir; PostgreSQL HA, SQL Server Always On,
Oracle RAC veya müşteri topolojisi sertifikası değildir.

## Staging HA Kanıtının Sınırı

`run-staging-redis-ha-evidence.ps1` ve `run-staging-mssql-ha-evidence.ps1`,
`ExternalFailoverWaitSeconds` ile ayrılan iki smoke aşaması çalıştırır. Canlı
failover için bağımsız bir gözlem kanıtı toplamaz veya doğrulamazlar. Beklemenin
pozitif ya da sıfır olması, topoloji geçişini veya bağlantı toparlanmasını kanıtlamaz.

Varsayılan çağrı iki smoke aşamasını korur ancak ikisi de geçse bile HA onayını
başarısız sonuçlandırır. Her çalıştırmanın JSON ve özetinde
`liveFailoverVerified=false` ve `haQualified=false` yer alır. `Production GA Staging
Evidence` workflow'u bu güvenli varsayılanı kullanır; mevcut haliyle
`-RequireManagedStagingHa` şartını karşılayamaz. Bu hattın HA onayı verebilmesi
için gerçek topolojiye özgü bağımsız gözlem kanıtı ve bunun doğrulayıcısı gerekir.
Bir operatör onay kutusu veya çağıranın verdiği başarı bayrağı bu kanıtın yerine geçmez.

Yalnızca HA onayı sağlamayan tanılama için iki script'ten birine
`-ReconnectSmokeOnly` eklenebilir. Bu mod ancak iki smoke aşaması da geçerse
başarılı olur; yine iki HA bayrağını false üretir ve yeniden bağlantı kurulduğunu
kanıtlamaz. Bu seçeneği HA şartını denetleyen çağrılara ekleme veya smoke-only
başarısını GA kanıtı sayma.

Raporlar `target/cachedb-staging-ha-reports/` veya
`target/cachedb-staging-mssql-ha-reports/` altındaki benzersiz `run-*` dizinlerinde
saklanır. Önceki raporlar temizlenmez; hata halinde kısmi smoke sonuçları korunur.
Workflow, `run-*/staging-*-ha-evidence.md` özetlerini yayımlar ve hata sonrasında
`always()` ile artifact yüklemeyi dener. Eksik kanıt HA başarısı değildir;
artifact aktarımının başarılı olması da HA onayı sağlamaz.

## Adım Adım GA Akışı

1. `0.1.0` veya `1.0.0` gibi stabil bir sürüm hazırla. GA sürümünde `beta`,
   `alpha`, `rc`, `preview` veya `SNAPSHOT` kullanma.
2. Lokal Docker HA preflight'i çalıştır:

   ```powershell
   pwsh ./tools/ci/run-local-docker-ha-preflight.ps1
   ```

   Bu komut Redis, PostgreSQL ve SQL Server container'larını başlatır; Redis
   outage/recovery evidence ve SQL Server restart/reconnect evidence çalıştırır.
3. Oracle provider kanıtını container restart ile çalıştır:

   ```powershell
   pwsh ./tools/ci/run-oracle-provider-evidence.ps1 -RestartOracleContainer
   ```

   Bu hat; Oracle JDBC Thin bağlantısını, sürüm kontrollü yazmaları, outbox'ı,
   migration keşfini, sınırlı okumaları, gecikmeli ağ davranışını, throughput'u
   ve tek instance restart sonrasında yeniden bağlantıyı doğrular. RAC veya Data
   Guard sertifikası vermez.
4. Lisanslı Oracle 19c Enterprise image'ının hazır olduğu Windows self-hosted
   runner üzerinde fiziksel Data Guard hattını çalıştır:

   ```powershell
   pwsh ./tools/ci/run-local-oracle-dataguard-evidence.ps1 `
     -MavenExecutable C:\apache-maven-3.9.9\bin\mvn.cmd
   ```

   Bu test gerçek redo taşıma ve uygulamayı, planlı broker switchover'ını,
   primary kaybını, eski bağlantının reddedilmesini, çok adresli tek servis adlı
   Oracle JDBC tanımı üzerinden Hikari toparlanmasını, iki geçişten sonra provider
   davranışını ve eski primary'nin yeniden standby yapılarak son boşluksuz/sıfır
   gecikmeli hazırlık kontrolünü kanıtlar. RAC/SCAN/FAN/FCF yapısını, sıfır RPO'yu
   veya müşterinin production ağ ve servis politikasını kanıtlamaz.
5. Release iddiası MSSQL listener/failover davranışını içeriyorsa ama ortak
   staging Always On ortamında isteğe bağlı failover tetikleyemiyorsan lokal
   listener preflight'i çalıştır:

   ```powershell
   pwsh ./tools/ci/run-local-mssql-listener-failover-evidence.ps1
   ```

   Bu test, sabit listener endpoint'i üzerinden eski JDBC connection'ın
   geçersiz kaldığını ve yeni connection'ın yeni backend'e gittiğini kanıtlar.
   Always On replikasyonunun, quorum davranışının veya yönetilen failover
   politikasının yerine geçmez.
6. Release commit'ini `main` branch'ine gönder ve aynı commit üzerinde
   `Framework Readiness` ile `Production Evidence` workflow'larının
   geçtiğini doğrula.
7. Resmi GitHub Release artifact'ini hedef commit'ten üret:

   ```powershell
   pwsh ./tools/release/build-release-package.ps1 `
     -Version 0.1.0 `
     -PackageLabel github-release
   ```

   Stabil release için `github-release` gibi beta içermeyen bir package label
   kullan.
8. Stabil tag'i oluştur ve gönder; örnek: `v0.1.0`.
9. Maven Central resmi dağıtım kanalı olarak seçildiyse, stabil tag üzerinde
   `Maven Central Publish` workflow'unu manuel olarak
   `gaRelease=true` ile çalıştır. Workflow, imzalı artifact publish etmeden
   önce GA preflight kontrolünü çalıştırır.
10. Aynı tag için `Production GA Release Readiness` workflow'unu çalıştır.
   `requireManagedStagingHa`, `requireApplicationMigrationCoverage` veya
   `requireMavenCentralPublish` seçeneklerini yalnızca release iddiası bu
   opsiyonel kapıları kapsıyorsa aç.
11. GitHub release'i yalnızca readiness özeti `PASS` ise yayınla ve resmi
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

## Kubernetes Kabul Kayıtlarının Aktarımı

Kubernetes kabul testlerini onaylanmış Kubernetes namespace'i içinde çalıştırın.
Herkese açık `Framework Readiness` iş akışı bu testlerin raporlarını doğrular;
testleri yeniden çalıştırmaz ve dosya yüklenmesini test başarısı saymaz.

`tools/ci/k8s-acceptance-contract.psd1` şu anda 52 kabul yükümlülüğü tanımlar:
bir SDK derlemesi, tek sunucu testleri, Cluster testleri, örnek özelliklerin
doğrulanması, üç sağlayıcının eksiksiz test kümeleri, üç olumsuz sertifikasyon
kontrolü, on iki API karşılaştırma senaryosu ve otuz depo kontrolü. Bir özelliğin
kısa testi, sağlayıcı testlerinin veya API karşılaştırmasının yerine geçmez.
Performans ve HA doğrulaması ayrı kabul koşullarıdır.

Paket kontrolü (`control/17`), ikinci bir derleme istemek yerine özgün
`sdk-build` işlemine referans verebilir. Bu referans yalnızca `id`,
`executionRef` ve `canonicalCommand` alanlarını içerir; işlem kayıtlarını
değiştiremez. Diğer yükümlülükler ayrı işlemlerle doğrulanır. Gerçek komutu ve
ortam kayıtlarını koruyun: standart komut kabul koşulunu tanımlar, bütün
çalıştırma ayrıntılarını değil. Çevrimdışı derlemenin komut ve ortam kanıtları
ayrıca onaylanmış ve sağlama toplamlarıyla korunmuş olmalıdır; dosya hash'lerinin
eşleşmesi tek başına farklı bir komutu onaylamaz.

1. Her işlemin gerçek komutunu, çalışma dizinini, günlüğünü, çıkış kodu dosyasını,
   zaman bilgilerini ve pod UID'sini saklayın. İşlemin kullandığı kaynak SHA'sını
   ve SDK dosya listesini kaydedin. Kaynak ve SDK dosyalarının işlem öncesi ve
   sonrası sağlama toplamlarını koruyun. Eksik kayıtları başka bir paketin
   sonuçlarıyla tamamlamayın; süresi dolan çalışmanın bitiş sınırını uzatmayın.
2. `source`, `sdk`, `producer`, `runs`, `executions` ve `files` alanlarını içeren
   açık bir `cachedb.k8s-acceptance-input/v1` envanteri sağlayın. Her dosya kaydında
   göreli yol, bayt uzunluğu ve SHA-256 bulunmalıdır. Üretici kimliklerini gerçek,
   korumalı iş akışından alın; örnek veya uydurma kimlik kullanmayın.
3. Saklanan kayıtlardan yeni bir çıktı dizini oluşturun:

   ```powershell
   $inventory = './target/runtime-evidence/execution-inventory.json'
   $pin = (Get-FileHash -LiteralPath $inventory -Algorithm SHA256).Hash.ToLowerInvariant()
   pwsh ./tools/ci/new-k8s-acceptance-receipt.ps1 `
     -InventoryPath $inventory `
     -InventorySha256 $pin `
     -EvidenceRoot ./target/runtime-evidence `
     -OutputDirectory ./target/k8s-acceptance
   ```

   Envanter önceden hazırlanmış olmalıdır. Bu komut test çalıştırmaz ve eksik
   kanıt üretmez. Eksik çalıştırmaları, değişen dosyaları, ortak kullanılan işlem
   günlüklerini ve geçersiz süre sınırlarını reddeder. Özgün envanteri
   `origin/execution-inventory.json` altında korur. Başarılı sonucu
   `ASSEMBLED_NOT_RUNTIME_ACCEPTANCE` olur; bu, sürüm onayı değildir. Yerel
   envanterin sağlama toplamını almak dosyayı sabitler; üreticinin kimliğini
   doğrulamaz.
4. Oluşturulan kayıtları onaylı özel üretici iş akışı üzerinden yayımlayın.
   Herkese açık deponun `CACHEDB_K8S_ACCEPTANCE_ARTIFACT_ID`,
   `CACHEDB_K8S_ACCEPTANCE_ARCHIVE_SHA256`,
   `CACHEDB_K8S_ACCEPTANCE_RECEIPT_SHA256`,
   `CACHEDB_K8S_ACCEPTANCE_SDK_SHA256` ve
   `CACHEDB_K8S_ACCEPTANCE_PRODUCER_WORKFLOW` değişkenlerini gerçek değerlerle
   ayarlayın. Depo kimliğini ve salt okunur erişim anahtarını ayrıca yapılandırın.
5. `check-k8s-acceptance.ps1` kontrolünü korumalı, herkese açık iş akışına bırakın.
   Bu kontrol üreticinin yetkisini, kaynak ve SDK kimliğini, zorunlu raporların
   içeriğini doğrular. `K8S_ACCEPTANCE_VERIFIED` yalnızca bu kabul koşulunu kapatır;
   sürüm yayımlamaz ve diğer GA koşullarını kaldırmaz. Eksik rapor, başarısız
   işlem veya güvenilmeyen üretici varsa kabul verilmez.

## Production Kararı

BEST: GA release'i yalnızca `Production GA Release Readiness` yeşilken ve
GitHub Release artifact'i resmi dağıtım paketi olarak eklenmişken çıkar.

ACCEPTABLE: framework GA'yı Docker/CI outage evidence ve açık sınırlarla çıkar;
uygulamalar ise production cutover öncesinde route bazlı coverage, rollback ve
staging HA evidence çalıştırmaya devam eder.

ANTI-PATTERN: unit testler, lokal Docker testleri veya framework readiness
workflow'u geçtiği için beta build'i GA olarak yeniden adlandırmak.

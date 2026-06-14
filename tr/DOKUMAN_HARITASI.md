# CacheDB Türkçe Doküman Haritası

English version: [../DOCUMENTATION_MAP.md](../DOCUMENTATION_MAP.md)

Bu sayfa, Türkçe dokümanların giriş kapısıdır. Amaç dosya adlarını ezberletmek
değil, kullanıcının sorduğu soruya göre doğru belgeye hızlı gitmesini
sağlamaktır.

## İlk Kez Gelen Kullanıcı

Sıra ile oku:

1. [README](README.md)
2. [Başlangıç Rehberi](docs/getting-started.md)
3. [Kavramlar ve Kabuller](docs/kavramlar-ve-kabuller.md)
4. [Kullanım Senaryosu Örnekleri](docs/use-case-examples.md)
5. [Production Tuning Rehberi](docs/production-tuning-rehberi.md)

Bu beş belge, sıfırdan başlayan bir ekibin CacheDB'nin ne olduğunu, nasıl
kurulduğunu, relation/projection kararlarının nasıl verildiğini ve production
ayarlarının hangi mantıkla yapılacağını anlaması için yeterli olmalıdır.

## Soruya Göre Doküman Seçimi

| Soru | Doküman |
| --- | --- |
| CacheDB nedir, ne değildir? | [README](README.md) |
| Yeni projeye nasıl eklerim? | [Başlangıç Rehberi](docs/getting-started.md) |
| Spring Boot'ta JDBC dependency gerekiyor mu? | [Spring Boot Starter](docs/spring-boot-starter.md) |
| Entity, relation, projection, route contract ne demek? | [Kavramlar ve Kabuller](docs/kavramlar-ve-kabuller.md) |
| Insert, read, update, delete nasıl yapılır? | [Kullanım Senaryosu Örnekleri](docs/use-case-examples.md) |
| Customer-order, invoice-payment, stock, ticket gibi modeller nasıl kurulur? | [Kullanım Senaryosu Örnekleri](docs/use-case-examples.md) |
| Çok ilişkili ekranı nasıl hızlandırırım? | [Production Reçeteleri](docs/production-recipes.md) |
| Redis memory nasıl sınırlandırılır? | [Production Tuning Rehberi](docs/production-tuning-rehberi.md) |
| Tüm config property'leri nerede? | [Tuning Parametreleri](docs/tuning-parameters.md) |
| Mevcut SQL veritabanı + ORM uygulamasını nasıl taşırım? | [Geçiş Planlayıcı](docs/migration-planner.md) |
| Kaynak veritabanı CacheDB dışında değişirse Redis'i nasıl güncel tutarım? | [Outbox ve CDC Apply Runner](docs/outbox-cdc-apply-runner.md) |
| CacheDB MSSQL veya başka SQL veritabanını destekleyebilir mi? | [Veritabanı Sağlayıcı SPI](docs/veritabani-provider-spi.md) |
| Production'a çıkmadan önce hangi kanıtlar gerekir? | [Production Test Raporu](docs/production-test-report.md) |
| Stable release mi, provider bazlı production sınırı mı? | [Production GA Criteria](../PRODUCTION_GA_CRITERIA.md) ve [Stable Release Launch Kit](docs/stable-release-launch-kit.md) |
| GA release çıkabilir mi, nasıl karar verilir? | [Production GA Release Runbook](docs/production-ga-release-runbook.md) |
| Release nasıl kesilir? | [Release Akışı](docs/release-flow.md) |
| Maven Central yayını için ne eksik? | [Maven Central Publish Checklist](docs/maven-central-publish-checklist.md) |

## Kullanıcı Tipine Göre Rota

### Uygulama Geliştiricisi

Önce şunları oku:

- [Başlangıç Rehberi](docs/getting-started.md)
- [Kullanım Senaryosu Örnekleri](docs/use-case-examples.md)
- [Spring Boot Starter](docs/spring-boot-starter.md)

Hedef: entity yazmak, repository kullanmak, relation ve projection farkını
anlamak.

### Takım Lideri veya Mimar

Önce şunları oku:

- [Kavramlar ve Kabuller](docs/kavramlar-ve-kabuller.md)
- [ORM Alternatifi Rehberi](docs/orm-alternative.md)
- [Production Reçeteleri](docs/production-recipes.md)
- [Production Tuning Rehberi](docs/production-tuning-rehberi.md)

Hedef: CacheDB'nin hangi route'lara uygun olduğunu, nerede projection şart
olduğunu ve production risklerini görmek.

### Platform veya SRE Ekibi

Önce şunları oku:

- [Tuning Parametreleri](docs/tuning-parameters.md)
- [Production Test Raporu](docs/production-test-report.md)
- [Production Readiness Raporu](docs/production-readiness-report.md)
- [Final Production Go/No-Go Raporu](docs/final-production-go-no-go-report.md)

Hedef: Redis, kalıcı SQL provider, worker, leader lease, failover, CI evidence
ve operasyonel gözlemlenebilirlik sınırlarını anlamak.

### Mevcut ORM'den Geçecek Ekip

Önce şunları oku:

- [Geçiş Planlayıcı](docs/migration-planner.md)
- [Outbox ve CDC Apply Runner](docs/outbox-cdc-apply-runner.md)
- [Kullanım Senaryosu Örnekleri](docs/use-case-examples.md)
- [Production Reçeteleri](docs/production-recipes.md)
- [Production Tuning Rehberi](docs/production-tuning-rehberi.md)

Hedef: kaynak veritabanı şemasını keşfetmek, hot route seçmek, Redis warm planı
oluşturmak, kaynak veritabanı baseline'ı ile CacheDB sonucunu karşılaştırmak ve cutover
kararını kanıtla vermek.

## Dokümanların Rolü

| Doküman | Rolü |
| --- | --- |
| [README](README.md) | Ürünün kısa konumlandırması ve ana karar kapısı |
| [Başlangıç Rehberi](docs/getting-started.md) | Sıfırdan çalışan ilk kullanım |
| [Kavramlar ve Kabuller](docs/kavramlar-ve-kabuller.md) | Arka plandaki tanımlar, kabuller ve tasarım sınırları |
| [Kullanım Senaryosu Örnekleri](docs/use-case-examples.md) | Gerçek hayat entity, query, update, delete, projection ve dashboard örnekleri |
| [Production Tuning Rehberi](docs/production-tuning-rehberi.md) | Redis memory, hot policy, route contract, write-behind ve Kubernetes tuning kararları |
| [Tuning Parametreleri](docs/tuning-parameters.md) | Property referansı ve varsayılan değerler |
| [Geçiş Planlayıcı](docs/migration-planner.md) | Mevcut SQL veritabanı sistemlerinden geçiş akışı |
| [Outbox ve CDC Apply Runner](docs/outbox-cdc-apply-runner.md) | Dış veritabanı değişiklikleri, outbox/CDC event'leri ve cache-only apply davranışı |
| [Veritabanı Sağlayıcı SPI](docs/veritabani-provider-spi.md) | PostgreSQL, MSSQL ve ilerideki SQL dialect'leri için storage provider sınırı |
| [Production Reçeteleri](docs/production-recipes.md) | Production kullanım desenleri, BEST/ACCEPTABLE/ANTI-PATTERN ayrımı |
| [Mimari](docs/architecture.md) | İç mimari, veri akışı, registry, relation loading ve açık tasarım kararları |
| [Production Test Raporu](docs/production-test-report.md) | Test kanıtları, smoke sonuçları, benchmark ve certification lane |
| [Production Readiness Raporu](docs/production-readiness-report.md) | Ürünün production seviyesine ne kadar yakın olduğu |
| [Production GA Release Runbook](docs/production-ga-release-runbook.md) | Stabil GA release için sert go/no-go akışı |
| [Stable Release Launch Kit](docs/stable-release-launch-kit.md) | GitHub açıklaması, release mesajı ve stable release konumlandırması |
| [Public Beta Launch Kit](docs/public-beta-launch-kit.md) | Geçmiş public beta release konumlandırması |
| [Release Checklist](docs/release-checklist.md) | Release öncesi kontrol listesi |

## Türkçe Yazım Kuralı

Türkçe dokümanlarda doğal Türkçe kullanılmalıdır. İngilizce metinlerin bire bir
çevirisi kabul edilmez.

Kural:

- `ı`, `İ`, `ğ`, `ş`, `ö`, `ü`, `ç` karakterleri doğru kullanılmalıdır.
- Teknik terimler gereksiz çevrilmemelidir: `projection`, `route contract`,
  `write-behind`, `hot set`, `warm`, `cutover` gibi terimler korunabilir.
- Açıklama cümleleri Türkçe düşünce akışına uygun yazılmalıdır.
- Kod, property ve sınıf adları çevrilmemelidir.
- Yeni doküman eklendiğinde mümkünse hem İngilizce hem Türkçe karşılığı
  planlanmalıdır; Türkçe tarafı eksik bırakılmamalıdır.

Kalite kontrolü:

```powershell
pwsh tools\ci\check-tr-docs.ps1
```

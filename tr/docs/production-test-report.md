# Production Kanıt Rehberi

İngilizce sürüm: [../../docs/production-test-report.md](../../docs/production-test-report.md)

Bu belge, CacheDB production kanıtlarının nasıl üretildiğini ve nasıl
yorumlanacağını anlatır. İkinci bir hazırlık kararı yayımlamaz. Güncel karar
kaynağı [Production Olgunluğu](production-olgunlugu.md) belgesidir.

## Kanıt Hatları

| Hat | Neyi kanıtlar? | Neyi kanıtlamaz? |
| --- | --- | --- |
| Tüm Maven reactor | Derleme zamanı üretimi, unit ve integration sözleşmeleri, modül uyumluluğu | Müşterinin iş yükü kapasitesi |
| Framework Readiness | Public API, reflection kullanılmaması, doküman, paket yapısı, provider ve sample eşdeğerliği | Yönetilen altyapı failover'ı |
| Production Evidence | Redis kesintisi sonrası toparlanma, çok instance koordinasyonu, projection ve sıralama benchmark'ları, provider smoke testleri | Uygulamanın rota envanterinin eksiksizliği |
| SQL Server provider evidence | Version korumalı yazma, batching, throughput eşiği, yeniden başlatma ve bağlantı yenileme, lock sınıflandırması, outbox ve migration davranışı | Her Always On topolojisi |
| Public Maven Repository Publish | Değişmez artifact'lerin anonim indirilmesi ve checksum kontrolü | Uygulamanın geçişe hazır olması |
| `cachedb:certify` | Tek uygulamanın rota, veri eşitliği, bellek, failover, canary ve geri dönüş kanıtı | Başka uygulama veya ortam |

## Yerel Framework Komutları

Repository wrapper'ı üzerinden Java 21 kullan:

```powershell
pwsh ./tools/build/invoke-maven-semeru.ps1 `
  -WorkingDirectory . `
  -MavenArgs @('-B', 'clean', 'verify')

pwsh ./tools/ci/run-local-docker-ha-preflight.ps1
```

Docker kontrolü; birbirinden ayrılmış Redis 8, PostgreSQL 16 ve SQL Server 2022
container'larını başlatır, kesinti ve yeniden başlatma kanıtlarını üretir,
raporları `target` altında yazar. `-KeepContainers` verilmezse container'ları
işlem sonunda kaldırır.

## Performans Kontrolleri

- Projection-first, parent bazlı relation, ranked-window ve summary/detail
  benchmark'ları CI içinde açık eşikler kullanır.
- SQL Server yüksek hacimli yazma kanıtı; satır sayısını, işlem sayısını, geçen
  süreyi, saniyedeki işlem sayısını, gereken eşiği ve sonucu raporlar.
- Eşik sonucu yalnızca aynı commit, runner, payload, veritabanı ve ayar için
  anlamlıdır. Eğilimleri eşdeğer ortamlarda karşılaştır.
- CI'ı geçirmek için eşiği düşürmek çözüm değildir. SQL round trip, lock
  beklemesi, allocation, connection pool baskısı ve batch şeklini incele.

## Rapor Konumları

| Rapor | Konum |
| --- | --- |
| Production evidence | `target/cachedb-prodtest-reports/` |
| Redis failover | `target/cachedb-redis-failover-reports/` |
| SQL Server provider | `target/cachedb-mssql-provider-reports/` |
| Yerel Docker HA | `target/cachedb-local-docker-ha-reports/` |
| Public Maven çözümleme | `target/public-maven-repository-summary.md` |
| Uygulama sertifikası | `target/cachedb-production-certification.md` |

CI artifact'leri sınırlı süre saklanır. Sürüm kararında kullanılan değişmez
özetleri release kanıt konumuna veya uygulamanın sertifika dizinine taşı.

## Uygulama Kanıtı

Framework kanıtı, uygulamanın ekran, API, batch, worker ve raporlarını
kendiliğinden çıkaramaz. Her uygulama şu kontrolü çalıştırmalıdır:

```bash
mvn verify -Pproduction-certification
```

Kanıt biçimi ve kopyalanabilir Maven profili
[Production Sertifikası](production-sertifikasi.md) belgesinde bulunur. Her
kanıt dosyası, uygulamanın kesin commit'ine ve staging ortamına bağlanır.

## Karar Kuralı

- Kararlı framework sürümünü yalnızca zorunlu framework hatları aynı etiket
  üzerinde geçerse ve public artifact'ler anonim indirilebilirse yayımla.
- Uygulamayı yalnızca kendi sertifika kontrolü engelsiz geçerse canlı trafiğe aç.
- İş yükü, payload, ağ, kaynak limiti, provider, Redis topolojisi veya SQL
  topolojisi önemli ölçüde değişirse kanıtları yeniden üret.

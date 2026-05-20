# Production Test Raporu

Bu doküman, `cache-database` e-ticaret DAO modülü için şu ana kadar çalıştırılan production-benzeri smoke test sonuçlarını ve bir sonraki full benchmark aşamasını özetler.

## Güncel Smoke Sonuçları

Doğrulama ortamı:

- Redis: `redis://default:welcome1@127.0.0.1:56379`
- PostgreSQL: `jdbc:postgresql://127.0.0.1:55432/postgres`
- PostgreSQL kullanıcı bilgisi: `postgres / postgresql`
- Test sınıfı: `EcommerceProductionScenarioSmokeTest`

Koşulan senaryolar:

| Senaryo | Tip | İşlem | Okuma | Yazma | Hata | Ort. Gecikme | Maks Gecikme | Write-Behind Backlog | DLQ | Health |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| `campaign-push-spike-smoke` | LOAD | 72 | 38 | 34 | 0 | 27,224 us | 107,732 us | 434 | 0 | `DEGRADED` |
| `write-behind-backpressure-breaker-smoke` | BREAK | 42 | 10 | 32 | 0 | 48,305 us | 117,131 us | 432 | 0 | `DEGRADED` |

## Bu Sonuçlar Ne Söylüyor

Olumlu sinyaller:

- DAO katmanı karışık okuma/yazma trafiğini runtime hatası olmadan işleyebildi.
- Redis-first yol hem dengeli hem de yazma ağırlıklı trafiğe cevap verdi.
- Her iki smoke koşusunda da dead-letter oluşmadı.
- Production test modülü hem makine-okunur hem de insan-okunur rapor üretti.

Görülen darboğaz:

- Her iki koşu sonunda da anlamlı write-behind backlog oluştu.
- Bu, kısa vadede asıl limitin Redis mutation hızı değil, PostgreSQL flush kapasitesi ve drain hızı olduğunu gösteriyor.
- `BREAK` senaryosunda gecikme, karışık yük senaryosuna göre belirgin şekilde daha kötü; bu da inventory/order yazma baskısıyla uyumlu.

Bugünkü yorum:

- Mimari smoke ölçeğinde doğru çalışıyor.
- Patlama trafiği için production hazırlığı henüz kanıtlanmış değil.
- Sistem bugünkü haliyle aniden kırılmaktan çok, async flush backlog biriktirerek degrade olma eğiliminde.

## Production Öncesi Ana Riskler

1. Write-behind saturation.
   PostgreSQL flush worker'ları kampanya piklerinde geride kalabilir ve kalıcı backlog üretebilir.

2. Sustained burst altında health degradation.
   DLQ oluşmasa bile backlog çok uzun süre taşınırsa sistem uzun süre `DEGRADED` kalabilir.

3. Hot SKU contention.
   Flash-sale benzeri az sayıda SKU üzerindeki inventory yazmaları gecikmeyi artırabilir ve worker dağılımını bozabilir.

4. Geniş katalog taramasında cache churn.
   Düşük hot-set bütçesiyle büyük browse fırtınaları Redis verimini azaltıp read-through baskısını arttırabilir.

5. Benchmark boşluğu.
   Bugünkü doğrulama bilerek küçültülmüş smoke testtir. 10k, 25k veya 50k TPS sınıfında davranış henüz kanıtlanmadı.

## Guardrail Alert ve Runbook Seti

Önerilen production alert seti:

| Alert | Warning Tetik | Critical Tetik | Ana Risk | İlk Operator Aksiyonu |
| --- | --- | --- | --- | --- |
| `WRITE_BEHIND_BACKLOG` | backlog warning eşiğini geçer | backlog critical eşiğini geçer | PostgreSQL drain saturation | producer'ları yavaşlat, flush throughput ve drain durumunu kontrol et |
| `COMPACTION_PENDING_BACKLOG` | pending compaction warning eşiğini geçer | pending compaction critical eşiğini geçer | Redis memory büyümesi ve stale flush gecikmesi | compaction worker sağlığını ve runtime profile switching'i kontrol et |
| `REDIS_MEMORY_PRESSURE` | used memory warning bütçesini geçer | used memory critical bütçesini geçer | Redis eviction baskısı ve degraded serving | daha sert guardrail profile'a geç, hot-set/page-cache bütçelerini daralt |
| `REDIS_HARD_REJECTIONS` | yok | herhangi bir rejected write | bounded-memory koruması yazıları düşürüyor | kampanya trafiğini azalt ve drain kapasitesini geri kazan |
| `QUERY_INDEX_DEGRADED` | namespace degraded işaretlenir | degraded namespace SLA içinde rebuild edilmez | full-scan fallback nedeniyle query latency artar | query-index rebuild tetikle ve hard-limit baskısının bittiğini doğrula |
| `TOMBSTONE_BUILDUP` | tombstone sayısı delete baseline üstünde büyür | tombstone TTL ile boşalmıyor | delete ağırlıklı churn veya drain lag | delete trafiğini, stale resurrection korumasını ve TTL cleanup'i kontrol et |

Önerilen operator runbook:

1. Backlog ve memory birlikte yükseliyorsa önce Redis sizing değil PostgreSQL drain kapasitesini şüpheli kabul et.
2. Hard rejection görülürse önce kritik olmayan kampanya trafiğini kıs; sistem bounded memory koruması için availability'den feragat ediyor olabilir.
3. Bir namespace degraded query-index modundaysa hard-limit baskısının düştüğünü doğrula ve `/api/query-index/rebuild` tetikle.
4. Pressure düştükten sonra tombstone yüksek kalmaya devam ediyorsa delete ağırlıklı iş yüklerini, write-behind drain'i ve tombstone TTL ayarlarını incele.
5. Throughput kazanmak için tombstone'u kapatma; bu stale resurrection riskini geri getirir.

## Admin Metrics Export

Admin HTTP artık Prometheus uyumlu scrape endpoint'i de açıyor:

```text
GET /api/prometheus
```

Buradan şu metrik aileleri alınabilir:

- write-behind, DLQ, reconciliation ve diagnostics stream uzunlukları
- write-behind worker flush/coalescing/dead-letter sayaçları
- dead-letter recovery replay/failure sayaçları
- planner statistics key sayıları
- Redis used memory, peak memory, compaction pending, payload count ve hard rejection sayıları
- runtime profile etiketi ve switch sayısı

Bu sayede production alerting yalnızca dashboard'a bağlı kalmadan Prometheus tarafına da taşınabilir.

## Production Certification Runner

Production test modülü artık certification giriş noktası da içeriyor:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ProductionCertificationMain"
```

Certification raporu şunları birleştirir:

- representative benchmark koşusu
- gerçek Redis/PostgreSQL yığınında restart/recover doğrulaması
- throughput, backlog, hata, hard rejection, rebuild başarısı ve restart recovery için açık gate'ler

Üretilen dosyalar:

- `target/cachedb-prodtest-reports/production-certification-report.json`
- `target/cachedb-prodtest-reports/production-certification-report.md`

## Semantics ve Shedding Matrisi

| Entity / Query Sınıfı | Persistence Semantiği | Hard-Limit Davranışı | Recovery Yolu |
| --- | --- | --- | --- |
| `customer` | `LATEST_STATE` | cache, index ve learning daha agresif shed edilebilir | otomatik veya admin query-index rebuild |
| `inventory` | `LATEST_STATE` | son stok doğrusunu korumak için cache ve index yolları agresif shed edilebilir | otomatik veya admin query-index rebuild |
| `cart` | `LATEST_STATE` | mutable session state olduğu için agresif shedding kabul edilir | otomatik veya admin query-index rebuild |
| `order` | `EXACT_SEQUENCE` | query index daha tutucu tutulabilir; write ordering korunur | persistence sıralaması bozulmadan rebuild |
| exact lookup query'leri | sağlıklı durumda indexed | namespace policy izin verirse açık kalabilir | degrade değilse rebuild gerekmez |
| text / relation / sort ağırlıklı query'ler | sağlıklı durumda indexed | hard-limit altında önce shed edilmesi tercih edilir | rebuild bitene kadar full-scan fallback |

## Önerilen Full Benchmark Planı

Repository artık doğrudan tam ölçekli suite giriş noktası da içeriyor:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.FullScaleBenchmarkMain" `
  "-Dcachedb.prod.scaleFactor=1.0" `
  "-Dcachedb.prod.redis.uri=redis://default:welcome1@127.0.0.1:6379" `
  "-Dcachedb.prod.postgres.url=jdbc:postgresql://127.0.0.1:5432/postgres"
```

Tam ölçek suite şu an browse ağırlıklı, checkout ağırlıklı, contention, cache-thrash ve backpressure karakterli sekiz farklı `50k TPS` senaryo içerir.

Ayrıca kademeli ölçek koşusu için ayrı bir giriş noktası vardır:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ScaleLadderBenchmarkMain" `
  "-Dcachedb.prod.scaleLadder=0.10,0.25,0.50,1.0" `
  "-Dcachedb.prod.redis.uri=redis://default:welcome1@127.0.0.1:6379" `
  "-Dcachedb.prod.postgres.url=jdbc:postgresql://127.0.0.1:5432/postgres"
```

### Faz 1: Kontrollü Yük Rampası

Amaç:

- güvenli throughput aralığını bulmak
- ilk kalıcı degradation noktasını görmek

Önerilen adımlar:

1. `campaign-push-spike` senaryosunu `0.05`, `0.10`, `0.20` `scaleFactor` ile koş.
2. Şu metrikleri kaydet:
   - ops/sn
   - ortalama ve p95 gecikme
   - write-behind backlog artış hızı
   - yük bittikten sonraki drain süresi
   - PostgreSQL CPU ve IO
3. Backlog hedef iyileşme penceresinde boşalamıyorsa artık yükü artırma.

Başarı kriterleri:

- DLQ oluşmaması
- backlog'un hedef sürede boşalması
- health'in `DEGRADED` seviyesinden tekrar `UP` seviyesine dönmesi

### Faz 2: Yazma Ağırlıklı Eşik Testi

Amaç:

- write-behind çöküş noktasını bulmak

Önerilen senaryolar:

- `write-behind-backpressure-breaker`
- `flash-sale-hot-sku-contention`

Ölçülecekler:

- queue length eğimi
- DLQ oluşumu
- recovery worker claim sayısı
- order/inventory persistence lag

Başarı kriterleri:

- kalıcı DLQ birikmemesi
- recovery'nin kontrol edilebilir kalması
- stale write'ların yeni durumu ezmemesi

### Faz 3: Browse Fırtınasında Cache Verimi

Amaç:

- katalog ağırlıklı dönemlerde Redis hot-set ve page-cache davranışını görmek

Önerilen senaryo:

- `weekend-browse-storm`

Ölçülecekler:

- hot-set eviction hızı
- page cache hit oranı
- planner learned-stat büyümesi
- Redis bellek kullanımı

Başarı kriterleri:

- eviction'in kontrol altında kalması
- Redis belleğinin bütçe içinde kalması
- uzun taramalarda okuma gecikmesinin sert biçimde artmaması

### Faz 4: Bilerek Kırma Testleri

Amaç:

- sistemi konfor alanının dışına itince recovery davranışını doğrulamak

Önerilen kırma testleri:

- write-behind worker sayısını `1`e düşürmek
- batch size'i agresif şekilde küçültmek
- hot-set limitlerini daraltmak
- checkout ve inventory oranını arttırmak
- yük altında PostgreSQL'i kısa süre durdurmak

Beklenen çıktılar:

- backlog büyür
- health degrade olur
- recovery yolu gözlemlenebilir kalır
- veri tutarlılığı korumaları yine de çalışır

## Production Gate Önerisi

Gerçek bir e-ticaret kampanya yolu için DAO katmanına production-ready denmeden önce şunlar sağlanmalı:

- birden fazla ölçekte sustained burst testleri koşulmuş olmalı
- write-behind drain süresi ölçülüp kabul edilmiş olmalı
- browse storm altında Redis bellek bütçesi doğrulanmış olmalı
- DLQ boş kalmalı ya da operasyonel olarak geri alınabilir olmalı
- hot inventory contention güvensiz persistence lag üretmemeli
- operasyon ekibinin `DEGRADED` ve `DOWN` eşikleri net olmalı

## Sonraki Somut Çıktılar

1. `5m`, `15m`, `30m` gibi sabit süreli uzun benchmark profilleri ekle.
2. Benchmark raporlarına p95/p99 gecikme ve backlog eğimi ekle.
3. Resilience testi için opsiyonel PostgreSQL pause/fault injection ekle.
4. Koşuları zaman içinde karşılaştıracak benchmark karşılaştırma dokümanı ekle.

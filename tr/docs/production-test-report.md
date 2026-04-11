# Production Test Raporu

Bu dokuman, `cache-database` e-ticaret DAO modulu icin su ana kadar calistirilan production-benzeri smoke test sonuclarini ve bir sonraki full benchmark fazini ozetler.

## Guncel Smoke Sonuclari

Dogrulama ortami:

- Redis: `redis://default:welcome1@127.0.0.1:56379`
- PostgreSQL: `jdbc:postgresql://127.0.0.1:55432/postgres`
- PostgreSQL kullanici bilgisi: `postgres / postgresql`
- Test sinifi: `EcommerceProductionScenarioSmokeTest`

Kosulan senaryolar:

| Senaryo | Tip | Islem | Okuma | Yazma | Hata | Ort. Gecikme | Maks Gecikme | Write-Behind Backlog | DLQ | Health |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| `campaign-push-spike-smoke` | LOAD | 72 | 38 | 34 | 0 | 27,224 us | 107,732 us | 434 | 0 | `DEGRADED` |
| `write-behind-backpressure-breaker-smoke` | BREAK | 42 | 10 | 32 | 0 | 48,305 us | 117,131 us | 432 | 0 | `DEGRADED` |

## Bu Sonuclar Ne Soyluyor

Olumlu sinyaller:

- DAO katmani karisik okuma/yazma trafigini runtime hatasi olmadan isleyebildi.
- Redis-first yol hem dengeli hem de yazma agirlikli trafige cevap verdi.
- Her iki smoke kosusunda da dead-letter olusmadi.
- Production test modulu hem makine-okunur hem de insan-okunur rapor uretti.

Gorulen darbohaz:

- Her iki kosu sonunda da anlamli write-behind backlog olustu.
- Bu, kisa vadede asil limitin Redis mutation hizi degil, PostgreSQL flush kapasitesi ve drain hizi oldugunu gosteriyor.
- `BREAK` senaryosunda gecikme, karisik yuk senaryosuna gore belirgin sekilde daha kotu; bu da inventory/order yazma baskisiyla uyumlu.

Bugunku yorum:

- Mimari smoke olceginde dogru calisiyor.
- Patlama trafigi icin production hazirligi henuz kanitlanmis degil.
- Sistem bugunku haliyle aniden kirilmaktan cok, async flush backlog biriktirerek degrade olma egiliminde.

## Production Oncesi Ana Riskler

1. Write-behind saturation.
   PostgreSQL flush worker'lari kampanya piklerinde geride kalabilir ve kalici backlog uretebilir.

2. Sustained burst altinda health degradation.
   DLQ olusmasa bile backlog cok uzun sure tasinirsa sistem uzun sure `DEGRADED` kalabilir.

3. Hot SKU contention.
   Flash-sale benzeri az sayida SKU uzerindeki inventory yazmalari gecikmeyi artirabilir ve worker dagilimini bozabilir.

4. Genis katalog taramasinda cache churn.
   Dusuk hot-set butcesiyle buyuk browse firtinalari Redis verimini azaltip read-through baskisini arttirabilir.

5. Benchmark boslugu.
   Bugunku dogrulama bilerek kucultulmus smoke testtir. 10k, 25k veya 50k TPS sinifinda davranis henuz kanitlanmadi.

## Guardrail Alert ve Runbook Seti

Onerilen production alert seti:

| Alert | Warning Tetik | Critical Tetik | Ana Risk | Ilk Operator Aksiyonu |
| --- | --- | --- | --- | --- |
| `WRITE_BEHIND_BACKLOG` | backlog warning esigini gecer | backlog critical esigini gecer | PostgreSQL drain saturation | producer'lari yavaslat, flush throughput ve drain durumunu kontrol et |
| `COMPACTION_PENDING_BACKLOG` | pending compaction warning esigini gecer | pending compaction critical esigini gecer | Redis memory buyumesi ve stale flush gecikmesi | compaction worker sagligini ve runtime profile switching'i kontrol et |
| `REDIS_MEMORY_PRESSURE` | used memory warning butcesini gecer | used memory critical butcesini gecer | Redis eviction baskisi ve degraded serving | daha sert guardrail profile'a gec, hot-set/page-cache butcelerini daralt |
| `REDIS_HARD_REJECTIONS` | yok | herhangi bir rejected write | bounded-memory korumasi yazilari dusuruyor | kampanya trafigini azalt ve drain kapasitesini geri kazan |
| `QUERY_INDEX_DEGRADED` | namespace degraded isaretlenir | degraded namespace SLA icinde rebuild edilmez | full-scan fallback nedeniyle query latency artar | query-index rebuild tetikle ve hard-limit baskisinin bittigini dogrula |
| `TOMBSTONE_BUILDUP` | tombstone sayisi delete baseline ustunde buyur | tombstone TTL ile bosalmiyor | delete agirlikli churn veya drain lag | delete trafigini, stale resurrection korumasini ve TTL cleanup'i kontrol et |

Onerilen operator runbook:

1. Backlog ve memory birlikte yukseliyorsa once Redis sizing degil PostgreSQL drain kapasitesini supheli kabul et.
2. Hard rejection gorulurse once kritik olmayan kampanya trafigini kis; sistem bounded memory korumasi icin availability'den feragat ediyor olabilir.
3. Bir namespace degraded query-index modundaysa hard-limit baskisinin dustugunu dogrula ve `/api/query-index/rebuild` tetikle.
4. Pressure dustukten sonra tombstone yuksek kalmaya devam ediyorsa delete agirlikli is yuklerini, write-behind drain'i ve tombstone TTL ayarlarini incele.
5. Throughput kazanmak icin tombstone'u kapatma; bu stale resurrection riskini geri getirir.

## Admin Metrics Export

Admin HTTP artik Prometheus uyumlu scrape endpoint'i de aciyor:

```text
GET /api/prometheus
```

Buradan su metrik aileleri alinabilir:

- write-behind, DLQ, reconciliation ve diagnostics stream uzunluklari
- write-behind worker flush/coalescing/dead-letter sayaclari
- dead-letter recovery replay/failure sayaclari
- planner statistics key sayilari
- Redis used memory, peak memory, compaction pending, payload count ve hard rejection sayilari
- runtime profile etiketi ve switch sayisi

Bu sayede production alerting yalnizca dashboard'a bagli kalmadan Prometheus tarafina da tasinabilir.

## Production Certification Runner

Production test modulu artik certification giris noktasi da iceriyor:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ProductionCertificationMain"
```

Certification raporu sunlari birlestirir:

- representative benchmark kosusu
- gercek Redis/PostgreSQL yigininda restart/recover dogrulamasi
- throughput, backlog, hata, hard rejection, rebuild basarisi ve restart recovery icin acik gate'ler

Uretilen dosyalar:

- `target/cachedb-prodtest-reports/production-certification-report.json`
- `target/cachedb-prodtest-reports/production-certification-report.md`

## Semantics ve Shedding Matrisi

| Entity / Query Sinifi | Persistence Semantigi | Hard-Limit Davranisi | Recovery Yolu |
| --- | --- | --- | --- |
| `customer` | `LATEST_STATE` | cache, index ve learning daha agresif shed edilebilir | otomatik veya admin query-index rebuild |
| `inventory` | `LATEST_STATE` | son stok dogrusunu korumak icin cache ve index yollari agresif shed edilebilir | otomatik veya admin query-index rebuild |
| `cart` | `LATEST_STATE` | mutable session state oldugu icin agresif shedding kabul edilir | otomatik veya admin query-index rebuild |
| `order` | `EXACT_SEQUENCE` | query index daha tutucu tutulabilir; write ordering korunur | persistence siralamasi bozulmadan rebuild |
| exact lookup query'leri | saglikli durumda indexed | namespace policy izin verirse acik kalabilir | degrade degilse rebuild gerekmez |
| text / relation / sort agirlikli query'ler | saglikli durumda indexed | hard-limit altinda once shed edilmesi tercih edilir | rebuild bitene kadar full-scan fallback |

## Onerilen Full Benchmark Plani

Repository artik dogrudan tam olcek suite giris noktasi da iceriyor:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.FullScaleBenchmarkMain" `
  "-Dcachedb.prod.scaleFactor=1.0" `
  "-Dcachedb.prod.redis.uri=redis://default:welcome1@127.0.0.1:6379" `
  "-Dcachedb.prod.postgres.url=jdbc:postgresql://127.0.0.1:5432/postgres"
```

Tam olcek suite su an browse agirlikli, checkout agirlikli, contention, cache-thrash ve backpressure karakterli sekiz farkli `50k TPS` senaryo icerir.

Ayrica kademeli olcek kosusu icin ayri bir giris noktasi vardir:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ScaleLadderBenchmarkMain" `
  "-Dcachedb.prod.scaleLadder=0.10,0.25,0.50,1.0" `
  "-Dcachedb.prod.redis.uri=redis://default:welcome1@127.0.0.1:6379" `
  "-Dcachedb.prod.postgres.url=jdbc:postgresql://127.0.0.1:5432/postgres"
```

### Faz 1: Kontrollu Yuk Rampasi

Amac:

- guvenli throughput araligini bulmak
- ilk kalici degradation noktasini gormek

Onerilen adimlar:

1. `campaign-push-spike` senaryosunu `0.05`, `0.10`, `0.20` `scaleFactor` ile kos.
2. Su metrikleri kaydet:
   - ops/sec
   - ortalama ve p95 gecikme
   - write-behind backlog artis hizi
   - yuk bittikten sonraki drain suresi
   - PostgreSQL CPU ve IO
3. Backlog hedef iyilesme penceresinde bosalamiyorsa artik yuk arttirma.

Basari kriterleri:

- DLQ olusmamasi
- backlog'un hedef surede bosalmasi
- health'in `DEGRADED` seviyesinden tekrar `UP` seviyesine donmesi

### Faz 2: Yazma Agirlikli Esik Testi

Amac:

- write-behind cokus noktasini bulmak

Onerilen senaryolar:

- `write-behind-backpressure-breaker`
- `flash-sale-hot-sku-contention`

Olculecekler:

- queue length egimi
- DLQ olusumu
- recovery worker claim sayisi
- order/inventory persistence lag

Basari kriterleri:

- kalici DLQ birikmemesi
- recovery'nin kontrol edilebilir kalmasi
- stale write'larin yeni durumu ezmemesi

### Faz 3: Browse Firtinasinda Cache Verimi

Amac:

- katalog agirlikli donemlerde Redis hot-set ve page-cache davranisini gormek

Onerilen senaryo:

- `weekend-browse-storm`

Olculecekler:

- hot-set eviction hizi
- page cache hit orani
- planner learned-stat buyumesi
- Redis bellek kullanimi

Basari kriterleri:

- eviction'in kontrol altinda kalmasi
- Redis belleginin butce icinde kalmasi
- uzun taramalarda okuma gecikmesinin sert bicimde artmamasi

### Faz 4: Bilerek Kirma Testleri

Amac:

- sistemi konfor alaninin disina itince recovery davranisini dogrulamak

Onerilen kirma testleri:

- write-behind worker sayisini `1`e dusurmek
- batch size'i agresif sekilde kucultmek
- hot-set limitlerini daraltmak
- checkout ve inventory oranini arttirmak
- yuk altinda PostgreSQL'i kisa sure durdurmak

Beklenen ciktular:

- backlog buyur
- health degrade olur
- recovery yolu gozlemlenebilir kalir
- veri tutarliligi korumalari yine de calisir

## Production Gate Onerisi

Gercek bir e-ticaret kampanya yolu icin DAO katmanina production-ready denmeden once sunlar saglanmali:

- birden fazla olcekte sustained burst testleri kosulmus olmali
- write-behind drain suresi olculup kabul edilmis olmali
- browse storm altinda Redis bellek butcesi dogrulanmis olmali
- DLQ bos kalmali ya da operasyonel olarak geri alinabilir olmali
- hot inventory contention guvensiz persistence lag uretmemeli
- operasyon ekibinin `DEGRADED` ve `DOWN` esikleri net olmali

## Sonraki Somut Ciktilar

1. `5m`, `15m`, `30m` gibi sabit sureli uzun benchmark profilleri ekle.
2. Benchmark raporlarina p95/p99 gecikme ve backlog egimi ekle.
3. Resilience testi icin opsiyonel PostgreSQL pause/fault injection ekle.
4. Kosulari zaman icinde karsilastiracak benchmark karsilastirma dokumani ekle.

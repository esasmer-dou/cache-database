# Production Test Raporu

Bu doküman, `cache-database` e-ticaret DAO modulu için şu ana kadar çalıştırilan production-benzeri smoke test sonuclarini ve bir sonraki full benchmark fazini özetler.

## Güncel Smoke Sonuclari

Doğrulama ortami:

- Redis: `redis://default:welcome1@127.0.0.1:56379`
- PostgreSQL: `jdbc:postgresql://127.0.0.1:55432/postgres`
- PostgreSQL kullanıcı bilgisi: `postgres / postgresql`
- Test sınıfi: `EcommerceProductionScenarioSmokeTest`

Koşulan senaryolar:

| Senaryo | Tip | Islem | Okuma | Yazma | Hata | Ort. Geçikme | Maks Geçikme | Write-Behind Backlog | DLQ | Health |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| `campaign-push-spike-smoke` | LOAD | 72 | 38 | 34 | 0 | 27,224 us | 107,732 us | 434 | 0 | `DEGRADED` |
| `write-behind-backpressure-breaker-smoke` | BREAK | 42 | 10 | 32 | 0 | 48,305 us | 117,131 us | 432 | 0 | `DEGRADED` |

## Bu Sonuclar Ne Soyluyor

Olumlu sinyaller:

- DAO katmanı karisik okuma/yazma trafigini runtime hatasi olmadan isleyebildi.
- Redis-first yol hem dengeli hem de yazma ağırlikli trafige cevap verdi.
- Her iki smoke koşusunda da dead-letter oluşmadi.
- Production test modulu hem makine-okunur hem de insan-okunur rapor üretti.

Görülen darbohaz:

- Her iki koşu sonunda da anlamli write-behind backlog oluştu.
- Bu, kısa vadede asil limitin Redis mutation hızi değil, PostgreSQL flush kapasitesi ve drain hızi olduğunu gösteriyor.
- `BREAK` senaryosunda geçikme, karisik yuk senaryosuna göre belirgin şekilde daha kötü; bu da inventory/order yazma baskisiyla uyumlu.

Bugunku yorum:

- Mimari smoke ölçeginde doğru çalışiyor.
- Patlama trafigi için production hazırligi henuz kanıtlanmış değil.
- Sistem bugunku haliyle aniden kirilmaktan çok, async flush backlog biriktirerek değrade olma egiliminde.

## Production Öncesi Ana Riskler

1. Write-behind saturation.
   PostgreSQL flush worker'lari kampanya piklerinde geride kalabilir ve kalıci backlog üretebilir.

2. Sustained burst altında health değradation.
   DLQ oluşmasa bile backlog çok uzun süre tasinirsa sistem uzun süre `DEGRADED` kalabilir.

3. Hot SKU contention.
   Flash-sale benzeri az sayıda SKU üzerindeki inventory yazmalari geçikmeyi artirabilir ve worker dagilimini bozabilir.

4. Geniş katalog taramasinda cache churn.
   Düşük hot-set butcesiyle büyük browse firtinalari Redis verimini azaltip read-through baskisini arttirabilir.

5. Benchmark boslugu.
   Bugunku doğrulama bilerek kucultulmus smoke testtir. 10k, 25k veya 50k TPS sınıfinda davranis henuz kanıtlanmadi.

## Guardrail Alert ve Runbook Seti

Önerilen production alert seti:

| Alert | Warning Tetik | Critical Tetik | Ana Risk | Ilk Operator Aksiyonu |
| --- | --- | --- | --- | --- |
| `WRITE_BEHIND_BACKLOG` | backlog warning esigini geçer | backlog critical esigini geçer | PostgreSQL drain saturation | producer'lari yavaslat, flush throughput ve drain durumunu kontrol et |
| `COMPACTION_PENDING_BACKLOG` | pending compaction warning esigini geçer | pending compaction critical esigini geçer | Redis memory buyumesi ve stale flush geçikmesi | compaction worker sağligini ve runtime profile switching'i kontrol et |
| `REDIS_MEMORY_PRESSURE` | used memory warning butcesini geçer | used memory critical butcesini geçer | Redis eviction baskisi ve değraded serving | daha sert guardrail profile'a geç, hot-set/page-cache butcelerini daralt |
| `REDIS_HARD_REJECTIONS` | yok | herhangi bir rejected write | bounded-memory korumasi yazılari düşuruyor | kampanya trafigini azalt ve drain kapasitesini geri kazan |
| `QUERY_INDEX_DEGRADED` | namespace değraded isaretlenir | değraded namespace SLA içinde rebuild edilmez | full-scan fallback nedeniyle query latency artar | query-index rebuild tetikle ve hard-limit baskisinin bittigini doğrula |
| `TOMBSTONE_BUILDUP` | tombstone sayisi delete baseline üstunde buyur | tombstone TTL ile bosalmiyor | delete ağırlikli churn veya drain lag | delete trafigini, stale resurrection korumasini ve TTL cleanup'i kontrol et |

Önerilen operator runbook:

1. Backlog ve memory birlikte yukseliyorsa önce Redis sizing değil PostgreSQL drain kapasitesini supheli kabul et.
2. Hard rejection görülurse önce kritik olmayan kampanya trafigini kis; sistem bounded memory korumasi için availability'den feragat ediyor olabilir.
3. Bir namespace değraded query-index modundaysa hard-limit baskisinin düştugunu doğrula ve `/api/query-index/rebuild` tetikle.
4. Pressure düştukten sonra tombstone yüksek kalmaya devam ediyorsa delete ağırlikli is yüklerini, write-behind drain'i ve tombstone TTL ayarlarini incele.
5. Throughput kazanmak için tombstone'u kapatma; bu stale resurrection riskini geri getirir.

## Admin Metrics Export

Admin HTTP artık Prometheus uyumlu scrape endpoint'i de açıyor:

```text
GET /api/prometheus
```

Buradan şu metrik aileleri alinabilir:

- write-behind, DLQ, reconciliation ve diagnostics stream uzunluklari
- write-behind worker flush/coalescing/dead-letter sayaclari
- dead-letter recovery replay/failure sayaclari
- planner statistics key sayilari
- Redis used memory, peak memory, compaction pending, payload count ve hard rejection sayilari
- runtime profile etiketi ve switch sayisi

Bu sayede production alerting yalnızca dashboard'a bağli kalmadan Prometheus tarafına da tasinabilir.

## Production Certification Runner

Production test modulu artık certification giriş noktası da iceriyor:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ProductionCertificationMain"
```

Certification raporu sunlari birlestirir:

- representative benchmark koşusu
- gerçek Redis/PostgreSQL yigininda restart/recover doğrulamasi
- throughput, backlog, hata, hard rejection, rebuild başarisi ve restart recovery için açık gate'ler

Üretilen dosyalar:

- `target/cachedb-prodtest-reports/production-certification-report.json`
- `target/cachedb-prodtest-reports/production-certification-report.md`

## Semantics ve Shedding Matrisi

| Entity / Query Sınıfi | Persistence Semantigi | Hard-Limit Davranisi | Recovery Yolu |
| --- | --- | --- | --- |
| `customer` | `LATEST_STATE` | cache, index ve learning daha agresif shed edilebilir | otomatik veya admin query-index rebuild |
| `inventory` | `LATEST_STATE` | son stok doğrusunu korumak için cache ve index yollari agresif shed edilebilir | otomatik veya admin query-index rebuild |
| `cart` | `LATEST_STATE` | mutable session state olduğu için agresif shedding kabul edilir | otomatik veya admin query-index rebuild |
| `order` | `EXACT_SEQUENCE` | query index daha tutucu tutulabilir; write ordering korunur | persistence sıralamasi bozulmadan rebuild |
| exact lookup query'leri | sağlikli durumda indexed | namespace policy izin verirse açık kalabilir | değrade değilse rebuild gerekmez |
| text / relation / sort ağırlikli query'ler | sağlikli durumda indexed | hard-limit altında önce shed edilmesi tercih edilir | rebuild bitene kadar full-scan fallback |

## Önerilen Full Benchmark Plani

Repository artık doğrudan tam ölçek suite giriş noktası da iceriyor:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.FullScaleBenchmarkMain" `
  "-Dcachedb.prod.scaleFactor=1.0" `
  "-Dcachedb.prod.redis.uri=redis://default:welcome1@127.0.0.1:6379" `
  "-Dcachedb.prod.postgres.url=jdbc:postgresql://127.0.0.1:5432/postgres"
```

Tam ölçek suite şu an browse ağırlikli, checkout ağırlikli, contention, cache-thrash ve backpressure karakterli sekiz farklı `50k TPS` senaryo icerir.

Ayrıca kademeli ölçek koşusu için ayrı bir giriş noktası vardir:

```powershell
mvn -q -pl cachedb-production-tests -am exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.ScaleLadderBenchmarkMain" `
  "-Dcachedb.prod.scaleLadder=0.10,0.25,0.50,1.0" `
  "-Dcachedb.prod.redis.uri=redis://default:welcome1@127.0.0.1:6379" `
  "-Dcachedb.prod.postgres.url=jdbc:postgresql://127.0.0.1:5432/postgres"
```

### Faz 1: Kontrollu Yuk Rampasi

Amac:

- güvenli throughput araligini bulmak
- ilk kalıci değradation noktasıni görmek

Önerilen adimlar:

1. `campaign-push-spike` senaryosunu `0.05`, `0.10`, `0.20` `scaleFactor` ile koş.
2. Şu metrikleri kaydet:
   - ops/seç
   - ortalama ve p95 geçikme
   - write-behind backlog artis hızi
   - yuk bittikten sonraki drain süresi
   - PostgreSQL CPU ve IO
3. Backlog hedef iyilesme penceresinde bosalamiyorsa artık yuk arttirma.

Başari kriterleri:

- DLQ oluşmamasi
- backlog'un hedef sürede bosalmasi
- health'in `DEGRADED` seviyesinden tekrar `UP` seviyesine donmesi

### Faz 2: Yazma Ağırlikli Esik Testi

Amac:

- write-behind çokus noktasıni bulmak

Önerilen senaryolar:

- `write-behind-backpressure-breaker`
- `flash-sale-hot-sku-contention`

Ölçulecekler:

- queue length egimi
- DLQ oluşumu
- recovery worker claim sayisi
- order/inventory persistence lag

Başari kriterleri:

- kalıci DLQ birikmemesi
- recovery'nin kontrol edilebilir kalmasi
- stale write'larin yeni durumu ezmemesi

### Faz 3: Browse Firtinasinda Cache Verimi

Amac:

- katalog ağırlikli donemlerde Redis hot-set ve page-cache davranisini görmek

Önerilen senaryo:

- `weekend-browse-storm`

Ölçulecekler:

- hot-set eviction hızi
- page cache hit orani
- planner learned-stat buyumesi
- Redis bellek kullanımi

Başari kriterleri:

- eviction'in kontrol altında kalmasi
- Redis belleginin butce içinde kalmasi
- uzun taramalarda okuma geçikmesinin sert biçimde artmamasi

### Faz 4: Bilerek Kirma Testleri

Amac:

- sistemi konfor alaninin disina itince recovery davranisini doğrulamak

Önerilen kirma testleri:

- write-behind worker sayisini `1`e düşurmek
- batch size'i agresif şekilde kucultmek
- hot-set limitlerini daraltmak
- checkout ve inventory oranini arttirmak
- yuk altında PostgreSQL'i kısa süre durdurmak

Beklenen çıktular:

- backlog buyur
- health değrade olur
- recovery yolu gözlemlenebilir kalir
- veri tutarliligi korumalari yine de çalışir

## Production Gate Önerisi

Gerçek bir e-ticaret kampanya yolu için DAO katmanına production-ready denmeden önce sunlar sağlanmali:

- birden fazla ölçekte sustained burst testleri koşulmus olmali
- write-behind drain süresi ölçulup kabul edilmis olmali
- browse storm altında Redis bellek butcesi doğrulanmis olmali
- DLQ bos kalmalı ya da operasyonel olarak geri alinabilir olmali
- hot inventory contention güvensiz persistence lag üretmemeli
- operasyon ekibinin `DEGRADED` ve `DOWN` esikleri net olmali

## Sonraki Somut Çıktilar

1. `5m`, `15m`, `30m` gibi sabit süreli uzun benchmark profilleri ekle.
2. Benchmark raporlarina p95/p99 geçikme ve backlog egimi ekle.
3. Resilience testi için opsiyonel PostgreSQL pause/fault injection ekle.
4. Koşulari zaman içinde karşılastiracak benchmark karşılastirma dokümani ekle.

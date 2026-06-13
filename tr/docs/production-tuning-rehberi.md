# Production Tuning Rehberi

English version: [../../docs/production-tuning-guide.md](../../docs/production-tuning-guide.md)

Bu rehber, CacheDB'yi production'a yaklaştırırken hangi ayarın hangi problemi
çözdüğünü anlatır. Tüm property referansı için
[Tuning Parametreleri](tuning-parameters.md) sayfasını kullan; bu belge karar
verme rehberidir.

Amaç:

- Redis memory büyümesini disiplin altına almak
- relation-heavy ekranlarda ilk sayfa maliyetini sınırlamak
- write-behind backlog oluştuğunda sistemi kontrollü tutmak
- çok pod'lu Kubernetes ortamında consumer ve singleton işlerini güvenli yapmak
- mevcut PostgreSQL sisteminden geçişte warm ve compare maliyetini öngörmek

## 30 Saniyelik Özet

| Problem | İlk bakılacak alan |
| --- | --- |
| Redis memory büyüyor | Hot policy, tenant quota, Redis `maxmemory`, projection window |
| Büyük liste yavaş | Projection, ranked projection, route contract |
| Relation yükleme pahalı | `withRelationLimit(...)`, batch loader, summary-first model |
| Yazma kuyruğu birikiyor | Write-behind worker, batch size, flush policy, PostgreSQL pool |
| Çok pod'da aynı iş iki kez çalışıyor | Runtime coordination ve leader lease |
| Eski veri Redis'i kirletiyor | `admitOnRead=false`, `TIME_WINDOW`, cold path |
| Migration warm uzun sürüyor | Batch size, checkpoint/resume, rate limit, route scope |
| Dış sistem PostgreSQL'i değiştiriyor | Outbox/CDC adapter ve apply runner |

## 1. Önce Route'u Sınıflandır

Tuning property seçmeden önce route tipini belirle. Yanlış route tasarımı,
property artırarak kalıcı biçimde düzelmez.

| Route tipi | Önerilen model |
| --- | --- |
| Tek entity detail | Entity repository |
| Küçük ve sınırlı liste | Entity page, guardrail ile |
| Müşteri başına büyük child listesi | Projection window |
| Global top-N veya iş sıralaması | Ranked projection |
| Audit, arşiv, aylık rapor | Kaynak veritabanı cold/reporting path |
| Worker/replay/repair | Doğrudan repository veya explicit SQL |

BEST: Route contract yaz, sonra property ayarla.

ANTI-PATTERN: Büyük listeyi full entity query ile çalıştırıp Redis pool veya CPU
artırarak sorunu çözmeye çalışmak.

## 2. Redis Bellek Modeli

Redis belleği tek bir ayarla yönetilmez. Dört katman birlikte düşünülmelidir.

| Katman | Ne kontrol eder? |
| --- | --- |
| Hot policy | Hangi satır Redis'e kabul edilir? |
| Hot entity limit | Kaç entity sıcak kalabilir? |
| Tenant quota | Tek tenant/müşteri Redis'i tüketebilir mi? |
| Redis `maxmemory` | Altyapıdaki mutlak bellek sınırı |

### Örnek: Son 90 Günlük Siparişler

İstek:

- Sipariş tablosunda milyonlarca kayıt var.
- Kullanıcılar çoğunlukla son 90 günü okuyor.
- Eski siparişler nadiren açılıyor.

BEST:

- `OrderEntity` için `TIME_WINDOW` kullan.
- Zaman kolonu `order_date` olmalı.
- Eski sipariş read-through ile Redis'e alınmamalıysa `admitOnRead=false`
  değerlendir.
- Customer order listesi projection olmalı.
- Redis `maxmemory` ve eviction policy altyapıda ayrıca ayarlanmalı.

Örnek mantık:

```properties
cachedb.config.resourceLimits.defaultCachePolicy.hotPolicy.mode=TIME_WINDOW
cachedb.config.resourceLimits.defaultCachePolicy.hotPolicy.timeColumn=order_date
cachedb.config.resourceLimits.defaultCachePolicy.hotPolicy.hotForSeconds=7776000
```

Not: `7776000` saniye yaklaşık 90 gündür.

### Örnek: Sadece Açık Ticket'lar

İstek:

- Support ticket geçmişi büyük.
- Kullanıcı ekranında sadece açık ve bekleyen işler hızlı olmalı.

BEST:

```properties
cachedb.config.resourceLimits.defaultCachePolicy.hotPolicy.mode=STATE_WINDOW
cachedb.config.resourceLimits.defaultCachePolicy.hotPolicy.stateColumn=status
cachedb.config.resourceLimits.defaultCachePolicy.hotPolicy.stateValues=OPEN,PENDING
```

Ticket kapanınca hot policy artık uymuyorsa cached entity ve index kaydı
temizlenmelidir.

### Örnek: Tenant Bazlı Bellek Sınırı

İstek:

- Tek büyük müşteri tüm Redis memory'yi tüketmemeli.
- Her tenant için sıcak payload bütçesi olmalı.

BEST:

- Route contract içinde tenant quota kullan.
- Hot row limit ve payload memory budget birlikte ayarlanmalı.
- Dashboard'da tenant bazlı accepted/rejected/evicted sayıları izlenmeli.

Karar:

| Sinyal | Yorum |
| --- | --- |
| Tenant accepted yüksek, evicted düşük | Bütçe yeterli |
| Rejected hızlı artıyor | Hot policy çok geniş veya quota düşük |
| Evicted sürekli artıyor | Hot window route ihtiyacından küçük |
| Redis memory tahmini ile gerçek kullanım ayrışıyor | Memory calibration raporunu incele |

## 3. Page Size ve Hot Window

Page size, hot window'dan küçük olmalıdır. Aksi halde bir istek, route'un
tasarlanan sıcak sınırını tek başına aşar.

Örnek:

| Değer | Anlam |
| --- | --- |
| `hotWindowPerRoot=1000` | Müşteri başına son 1000 order summary Redis'te |
| `firstPageSize=50` | UI ilk ekranda 50 satır gösterir |
| `maxColdReadSize=200` | Cold path yanlışlıkla çok büyümesin |

BEST:

- UI page size küçük ve kararlı olmalı.
- Projection window route ihtiyacını karşılamalı.
- Full entity page için guardrail açık olmalı.

ANTI-PATTERN:

- `hotWindow=1000` iken UI'ın tek istekte 5000 satır istemesi
- Büyük listeyi Redis'e koyup sonra uygulama içinde sıralamak

## 4. Relation Tuning

Relation yükleme pahalılaşır çünkü decode, allocation, network ve object graph
oluşturma maliyeti üretir.

BEST:

- Detail ekranında küçük preview için `withRelationLimit(...)` kullan.
- Liste ekranında relation yerine projection kullan.
- Loader batch çalışmalı; parent başına tekil query atmamalı.
- `maxFetchDepth` değeri kontrollü tutulmalı.

Örnek:

```java
OrderEntity order = orderRepository
        .withRelationLimit("orderLines", 8)
        .findById(orderId)
        .orElseThrow();
```

ACCEPTABLE:

- Order detail ekranında ilk 8 satır önizleme.

ANTI-PATTERN:

- Customer listesinde her customer için tüm orders ve tüm order lines yüklemek.

## 5. Projection Tuning

Projection tasarımı, relation-heavy ekranların ana performans aracıdır.

Karar soruları:

- Ekran ilk açılışta hangi kolonları gösteriyor?
- Detay açılmadan full entity gerekiyor mu?
- Sıralama sabit mi?
- Sıralama global mi, parent başına mı?
- Hot window kaç satır?
- Projection refresh gecikmesi kabul edilebilir mi?

Örnek route:

```text
GET /customers/{customerId}/orders
```

BEST model:

- `CustomerOrderSummaryProjection`
- key: `customer_id`
- sort: `order_date DESC, order_id DESC`
- window: müşteri başına 1000
- page size: 50 veya 100
- detail: `OrderEntity.findById(orderId)`

ANTI-PATTERN:

- Her istekte `OrderEntity` full payload okuyup bellekte sıralamak.

## 6. Write-Behind Tuning

Write-behind hattı Redis'ten PostgreSQL'e kalıcı yazım yapar. Bu hattı
ayarlarken yalnızca worker sayısını artırmak yeterli değildir.

İzlenecek sinyaller:

- stream backlog
- pending entry sayısı
- DLQ sayısı
- PostgreSQL flush latency
- retry sayısı
- worker CPU kullanımı
- PostgreSQL connection pool bekleme süresi

İlk ayarlar:

| Alan | Ne zaman artırılır? |
| --- | --- |
| worker threads | CPU ve PostgreSQL pool uygunsa backlog büyüyorsa |
| batch size | Küçük flush'lar çok fazlaysa |
| flush group parallelism | Farklı tablo grupları paralel yazılabiliyorsa |
| retry backoff | Geçici PostgreSQL hatalarında retry fırtınası oluşuyorsa |
| DLQ trim | Uzun süreli hata analizleri için daha fazla kayıt gerekiyorsa |

ANTI-PATTERN:

- PostgreSQL yavaşken worker sayısını sınırsız artırmak.
- DLQ'yu izlememek.
- Retry ve timeout olmadan write-behind çalıştırmak.

## 7. PostgreSQL Tuning

PostgreSQL hâlâ kalıcı doğruluk kaynağıdır. CacheDB Redis-first olsa bile
PostgreSQL bağlantı kalitesi production davranışını belirler.

Dikkat edilecekler:

- connection timeout
- socket timeout
- batch insert rewrite
- prepared statement threshold
- fetch size
- application name
- pool boyutu
- tablo indeksleri

Migration warm sırasında özellikle şu indeksler önemlidir:

- child tablonun relation kolonu
- sort kolonu
- primary key
- filtrelenen status/date kolonları

Örnek:

```sql
CREATE INDEX idx_orders_customer_date
ON orders (customer_id, order_date DESC, order_id DESC);
```

## 8. Redis Client ve Pool Tuning

Redis pool iki farklı trafik tipine hizmet eder:

- foreground repository okuma/yazma yolu
- background worker, admin, stream ve recovery yolu

BEST:

- foreground ve background havuzları ayrı düşün.
- foreground timeout daha düşük, background blocking timeout daha yüksek
  olabilir.
- `testWhileIdle` ve `testOnBorrow` seçenekleri gereksiz `PING` maliyeti
  üretmeyecek şekilde kullanılmalı.
- Pool bekleme süresi SLO'yu aşacak kadar yüksek olmamalı.

ANTI-PATTERN:

- Tüm iş yükleri için tek dev Redis pool açmak.
- Blocking stream read timeout'unu worker block timeout değerinden düşük
  tutmak.

## 9. Kubernetes ve Çok Pod

Çok pod'lu ortamda her pod aynı Redis ve PostgreSQL'e bağlanır. Bu normaldir.
Önemli olan consumer kimliği ve singleton işlerin kontrolüdür.

BEST:

- consumer name pod-unique olmalı
- consumer group ortak kalmalı
- cleanup/report/history gibi loop'lar leader lease ile singleton çalışmalı
- pod sayısı artınca toplam worker kapasitesi ve PostgreSQL pool birlikte
  düşünülmeli
- Redis HA zorunlu production bağımlılığı olarak ele alınmalı

Örnek:

```properties
cachedb.runtime.append-instance-id-to-consumer-names=true
cachedb.runtime.leader-lease-enabled=true
cachedb.runtime.leader-lease-ttl-millis=15000
```

ANTI-PATTERN:

- Tüm pod'larda aynı consumer name kullanmak.
- Her pod'un aynı cleanup job'ını aynı anda çalıştırması.
- Tek Redis instance'ını HA planı olmadan production koordinasyon merkezi yapmak.

## 10. Migration Warm Tuning

Warm işlemi migration'ın en riskli adımlarından biridir; çünkü hem PostgreSQL'i
okur hem Redis'i doldurur.

BEST:

- Önce dry-run çalıştır.
- Warm scope'u tek route ile sınırla.
- Batch size kontrollü başlasın.
- Checkpoint/resume açık olsun.
- Rate limit ile PostgreSQL ve Redis korunmalı.
- Warm sonrası Redis memory calibration raporu okunmalı.

Warm raporunda bakılacaklar:

| Alan | Yorum |
| --- | --- |
| kök satır sayısı | Kaç parent route'a dahil? |
| child satır sayısı | Ne kadar fan-out var? |
| eksik kök id sayısı | Veri bütünlüğü riski var mı? |
| süre | Warm maliyeti kabul edilebilir mi? |
| tahmini Redis memory | Bütçe ile uyumlu mu? |
| gerçek Redis memory | Tahmin kalibre edilmeli mi? |

## 11. Outbox ve CDC Tuning

PostgreSQL CacheDB dışından değişiyorsa outbox/CDC gerekir.

BEST:

- outbox event idempotent olmalı
- checkpoint güvenli tutulmalı
- duplicate event apply edilebilir olmalı
- delete ve update sırası korunmalı
- poison event DLQ veya görünür hata kanalına düşmeli

Henüz dikkat edilmesi gereken sınır:

- Mevcut adapter outbox event'lerini okuyabilir.
- Tam production apply runner ayrıca idempotent upsert/delete/projection refresh
  davranışıyla sertleştirilmelidir.

## 12. Dashboard ve Alarm Sinyalleri

Production'da yalnızca latency değil, kabul/reddetme davranışı da izlenmelidir.

İzlenecek metrikler:

- cache hit/miss
- hot policy accepted/rejected
- tenant quota rejected/evicted
- Redis used memory
- write-behind backlog
- pending claim sayısı
- DLQ sayısı
- projection lag
- warm progress
- side-by-side comparison parity

Alarm örnekleri:

| Sinyal | Aksiyon |
| --- | --- |
| DLQ artıyor | PostgreSQL hata tipini ve retry politikasını incele |
| Projection lag artıyor | Refresh worker ve source write hızını kontrol et |
| Tenant rejected artıyor | Hot policy, tenant quota ve route window uyumunu kontrol et |
| Redis memory hızla artıyor | New key prefix, projection window ve entity TTL/hot policy kontrolü |
| CacheDB p95 kaynak veritabanı baseline'ından kötü | Route projection mı, entity fallback mi bak |

## 13. Production Profil Önerileri

### Küçük Pilot

- 1-3 sıcak route
- projection-required route'larda strict mode
- admin UI sadece iç ağda
- staging compare zorunlu
- Redis HA planı belgeli

### Orta Ölçekli Servis

- route contract envanteri
- tenant quota
- projection lag metriği
- write-behind DLQ alarmı
- benchmark threshold CI gate
- migration coverage raporu

### Büyük Çok Tenant Sistem

- tenant bazlı memory budget
- route bazlı hot window
- staging Redis failover testi
- outbox/CDC apply runner
- canary cutover
- rollback runbook
- kapasite planı ve düzenli memory calibration

## Son Kontrol Listesi

Production'a yaklaşmadan önce:

- Her hot route için route contract var.
- Projection gereken route entity scan'e düşmüyor.
- Page size hot window'dan küçük.
- Tenant quota tek tenant taşmasını engelliyor.
- Redis `maxmemory` ve eviction policy altyapıda tanımlı.
- PostgreSQL indeksleri warm ve query şekline uygun.
- Write-behind backlog ve DLQ izleniyor.
- Outbox/CDC gereksinimi değerlendirildi.
- Migration warm checkpoint/resume destekli.
- Side-by-side comparison veri eşleşmesini kanıtladı.
- Admin UI güvenli gateway veya operasyon ağı arkasında.

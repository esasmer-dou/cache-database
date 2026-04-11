# Cache Database Architecture

Turkce surum: [../tr/docs/architecture.md](../tr/docs/architecture.md)

## 1. Amac

Bu proje klasik `DB-first ORM` yaklasimini tersine cevirir:

- uygulama katmani Redis ile konusur
- entity yasam dongusu Redis'te baslar
- PostgreSQL arka planda kalici depo olarak kullanilir
- flush islemi kullanici isteginden ayrilir

Bu nedenle sistem teknik olarak bir `cache-first persistence engine`dir.

## 2. Cekirdek Ilkeler

### Minimum overhead

- runtime reflection yok
- metadata lookup icin dinamik introspection yok
- compile-time generated metadata kullanilir
- entity codec'leri explicit olur

### N+1 kontrolu

N+1 problemini tamamen sihirli bicimde cozmek yerine API seviyesinde engellemek gerekir.

Kurallar:

- relation getter'lari varsayilan olarak tekil lazy query atmamalidir
- relation yukleme `FetchPlan` ile acikca istenmelidir
- tekil relation erisimleri batch loader uzerinden gruplanmalidir

### Redis-first

- `save`, `find`, `delete`, `query result materialization` once Redis'e gider
- kullaniciya verilen basari sinyali Redis seviyesinde olusur
- PostgreSQL sync islemi daha sonra write-behind iscisi ile yapilir
- Redis 8 uzerinde mutation akisina Redis Functions eklenebilir

### Version ordering

- her entity icin ayri Redis version key tutulur
- mutation sirasinda version atomik artirilir
- stream event'i bu version ile yazilir
- worker Redis'te daha yeni version varsa stale event'i skip eder
- PostgreSQL upsert sadece daha yeni version geldiginde satiri gunceller

### Sicak veri butcesi

Her entity turu icin:

- bir `hot set` limiti olur
- overflow kayitlar page mantigiyla gecici yuklenir
- kullanim bittiginde LRU benzeri politika ile Redis'ten dusurulur
- kalici veri PostgreSQL'de kalmaya devam eder

## 3. Veri Akisi

### Yazma akisi

1. Kullanici entity uzerinde `save()` cagirir.
2. Entity codec ile Redis payload ve kolon haritasi uretilir.
3. Redis Functions aciksa entity key ve stream append atomik bicimde calisir.
4. Fonksiyon kapaliysa entity Redis'e yazilir ve write-behind kuyruguna event eklenir.
5. Worker stream'i okuyup PostgreSQL'e `upsert/delete` uygular.

### Okuma akisi

1. Istek once Redis hot set icinde aranir.
2. Bulunmazsa ileride page loader PostgreSQL'den ilgili pencereyi getirecektir.
3. `FetchPlan` bos degilse kayitli `RelationBatchLoader` preload yapar.

## 4. Tutarlilik Modeli

Sistem `eventual consistency` modelindedir.

Bu su anlama gelir:

- kullanici Redis yazisini gorur
- PostgreSQL commit'i daha sonra gerceklesir
- Redis ve PostgreSQL kisa sureli farkli olabilir

Bu nedenle uretimde kritik konular:

- Redis AOF acilmasi
- durable stream/list kullanimi
- idempotent PostgreSQL yazimi
- retry ve dead-letter stratejisi
- version ordering

## 5. Reflection'siz Mapping Stratejisi

Reflection olmayacagi icin metadata uretimi annotation processor ile yapilmalidir.

Ornek cikti:

- `UserMeta`
- `OrderMeta`
- generated kolon listesi
- relation tanimlari
- id alani bilgisi

Entity codec iki farkli amac tasir:

- Redis payload encode/decode
- PostgreSQL kolon degerleri uretimi

## 6. Moduller

### `cachedb-annotations`

- `@CacheEntity`
- `@CacheId`
- `@CacheColumn`
- `@CacheRelation`

### `cachedb-processor`

- annotation scanning
- generated `*Meta` siniflari

### `cachedb-core`

- repository sozlesmeleri
- metadata sozlesmeleri
- fetch plan
- write operation modeli
- relation API
- cache policy
- runtime config

### `cachedb-storage-redis`

- Redis repository implementasyonu
- key naming stratejisi
- Redis Functions library/load/call katmani
- write-behind enqueue
- stale event skip mantigi

### `cachedb-storage-postgres`

- JDBC tabanli upsert/delete flusher
- version-gated update kurallari

### `cachedb-starter`

- bootstrap
- repository factory
- ortak wiring

## 7. Konfigurasyon Yaklasimi

Sabit kabul birakmamak icin ayarlar initialization asamasinda disaridan verilir.

Baslica runtime/init ayarlari:

- `WriteBehindConfig`
- worker thread sayisi
- batch size
- poll block suresi
- retry sayisi ve backoff
- consumer group ve stream key
- shutdown bekleme suresi
- daemon thread davranisi

- `ResourceLimits`
- varsayilan entity cache policy
- kayitli entity ust siniri
- operation basina kolon ust siniri

- `CachePolicy`
- hot entity limiti
- page size
- LRU etkinligi
- entity TTL
- page TTL

- `KeyspaceConfig`
- Redis key prefix
- entity/page/version segmentleri

- `RedisFunctionsConfig`
- functions etkin/pasif
- library auto-load davranisi
- replace/strict load kurallari
- library ve function isimleri
- template resource veya override source

- `RelationConfig`
- relation batch size
- max fetch depth
- missing preloader davranisi

- `PageCacheConfig`
- read-through page cache davranisi
- missing page loader davranisi
- eviction batch size

- `AdminHttpConfig`
- admin HTTP host/port/backlog
- worker thread sayisi
- CORS ve dashboard davranisi

## 8. Redis Functions Katmani

Redis 8 icin yazma akisi `FCALL` uzerinden yurutulebilir.

Onerilen akis:

1. Java tarafi entity write operation uretir.
2. `FCALL entity_upsert` ile entity key, version increment ve stream append tek atomik islemde yapilir.
3. Worker ayni stream'i okuyup PostgreSQL'e flush eder.

Silme akisi:

1. `FCALL entity_delete`
2. version atomik artirilir
3. entity key silinir
4. delete olayi stream'e yazilir

Bu yaklasimin faydalari:

- write + enqueue arasinda ara durum kalmaz
- tek round-trip ile mutation tamamlanir
- concurrency ve retry davranisi daha deterministik hale gelir

## 9. Relation Batch Loading

`FetchPlan` preload istegini tasir; gercek batch relation yuklemesi entity bazli `RelationBatchLoader` ile yapilir.

Kurallar:

- her entity icin istege bagli loader register edilebilir
- repository `findById/findAll` sonrasinda `FetchPlan` bos degilse loader cagrilir
- preload relation basina degil entity batch'i uzerinden yapilir
- `failOnMissingPreloader=true` ise preload istenip loader yoksa hata verilir

## 10. Acik Tasarim Kararlari

Henuz netlesmesi gereken basliklar:

- entity API tam olarak Active Record mi olacak, yoksa repository + generated facade mi
- relation tanimi hangi duzeyde preload zorunlu kilacak
- query DSL ne kadar genis olacak
- page cache boyutu global mi, entity tipi bazli mi yonetilecek
- write-behind ordering key bazli mi, entity tipi bazli mi olacak
- PostgreSQL tarafinda tombstone / soft-delete standardi gerekli mi
- version alani son kullanici entity modeline yansitilacak mi
- page cache materialization sadece id listesi mi, yoksa tam payload mu tutmali

## 11. Admin ve Diagnostics Yuzeyi

Sistem artik sadece stream bazli recovery degil, ayni zamanda isletimsel bir admin/debug yuzeyi de sunar.

- `CacheDatabaseAdmin`
  - DLQ, reconciliation, archive, diagnostics ve incidents export
  - manual replay/skip/close
  - metrics ve health
- `AdminIncidentWebhookNotifier`
  - persisted incident kayitlarini asenkron webhook cagrilarina cevirir
  - retry/backoff ve queue kapasitesi config ile yonetilir
- `AdminIncidentDeliveryManager`
  - incident kaydini coklu sink'e fan-out eder
  - webhook, Redis queue ve SMTP kanallarini destekler
  - channel bazli delivery snapshot/last error bilgisi tutar
  - her kanal kendi retry/backoff ayarlariyla calisabilir
  - basarisiz delivery denemelerini incident-delivery DLQ stream'ine yazar
- `AdminIncidentDeliveryRecoveryWorker`
  - incident delivery DLQ stream'ini ayri consumer group ile tuketir
  - `XAUTOCLAIM` ile abandoned/pending entry'leri devralabilir
  - replay basariliysa recovery stream'ine `REPLAYED`, degilse `FAILED` kaydi yazar
  - webhook, Redis queue ve SMTP kanallari icin ikinci sans replay hattini saglar
- `CacheDatabaseDebug`
  - query explain plani
  - explain export
- `CacheDatabaseAdminHttpServer`
  - `/api/health`
  - `/api/metrics`
  - `/api/diagnostics`
  - `/api/incidents`
  - `/api/incident-history`
  - `/api/alert-rules`
  - `/api/explain`
  - `/dashboard`

Bu katman JDK `HttpServer` ile calisir; ek framework bagimliligi olmadan lokal operasyon ve gozlemleme yuzeyi verir.

## 12. Planner Istatistikleri

Query planner artik sadece process ici cache'e degil, Redis key-space icinde TTL ile tutulan estimate/histogram verilerine de dayanabilir.

- estimate anahtarlari filter bazli tutulur
- histogram anahtarlari kolon bazli tutulur
- range histogram hesaplamasi esit-genislik yerine rank/quantile benzeri bucket secimiyle daha dengeli olur
- entity reindex/remove akisi planner stats anahtarlarini invalidate eder
- estimate modeli sample size ve selectivity ratio tasir
- text contains icin token selectivity carpimi ile daha iyi intersection tahmini yapilir
- learned statistics anahtarlari query/group ifadesi bazli tutulur
- query execution sonrasi gercek sonuc cardinality'si observe edilip sonraki explain/query planlarina agirlikli yansitilir
- cache warming aciksa filtre/sort histogramlari query execution oncesinde preload edilir
- multi-hop relation filtrelerinde hedef relation index sonucu owner id setine geri map edilerek selectivity hesaplanir
- admin metrics/dashboard planner estimate/histogram/learned key sayilarini gorunur kilabilir

Bu sayede restart sonrasi explain plani tamamen sifirdan baslamak zorunda kalmaz.

## 13. Onerilen Sonraki Adimlar

1. Admin HTTP yuzeyini auth/role modeliyle sertlestirmek
2. Explain ve diagnostics icin daha zengin dashboard/REST filtreleri eklemek
3. Failure injection ve load testlerle Redis/PostgreSQL hattini zorlamak
4. Planner istatistiklerini daha gelismis cardinality modeli ve domain heuristics ile zenginlestirmek

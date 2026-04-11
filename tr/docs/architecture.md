# Cache Database Mimarisi

Bu dosya, teknik mimari dokümanının Türkçe sürümüdür.

Kaynak doküman: [../../docs/architecture.md](../../docs/architecture.md)

## 1. Amaç

Bu proje klasik `DB-first ORM` yaklaşımını tersine çevirir:

- uygulama katmanı Redis ile konuşur
- entity yaşam döngüsü Redis'te başlar
- PostgreSQL arka planda kalıcı depo olarak kullanılır
- flush işlemi kullanıcı isteğinden ayrılır

Bu nedenle sistem teknik olarak bir `cache-first persistence engine`dir.

## 2. Çekirdek İlkeler

### Minimum overhead

- çalışma zamanında reflection yok
- metadata lookup için dinamik introspection yok
- compile-time generated metadata kullanılır
- entity codec'leri explicit olur

### N+1 kontrolü

N+1 problemini tamamen sihirli biçimde çözmek yerine API seviyesinde engellemek gerekir.

Kurallar:

- relation getter'ları varsayılan olarak tekil lazy query atmamalıdır
- relation yükleme `FetchPlan` ile açıkça istenmelidir
- tekil relation erişimleri batch loader üzerinden gruplanmalıdır

### Redis-first

- `save`, `find`, `delete`, `query result materialization` önce Redis'e gider
- kullanıcıya verilen başarı sinyali Redis seviyesinde oluşur
- PostgreSQL sync işlemi daha sonra write-behind işçisi ile yapılır
- Redis 8 üzerinde mutation akışına Redis Functions eklenebilir

### Version ordering

- her entity için ayrı Redis version key tutulur
- mutation sırasında version atomik artırılır
- stream event'i bu version ile yazılır
- worker Redis'te daha yeni version varsa stale event'i skip eder
- PostgreSQL upsert sadece daha yeni version geldiğinde satırı günceller

### Sıcak veri bütçesi

Her entity türü için:

- bir `hot set` limiti olur
- overflow kayıtlar page mantığıyla geçici yüklenir
- kullanım bittikten sonra LRU benzeri politika ile Redis'ten düşürülür
- kalıcı veri PostgreSQL'de kalmaya devam eder

## 3. Veri Akışı

### Yazma akışı

1. Kullanıcı entity üzerinde `save()` çağırır.
2. Entity codec ile Redis payload ve kolon haritası üretilir.
3. Redis Functions açıksa entity key ve stream append atomik biçimde çalışır.
4. Fonksiyon kapalıysa entity Redis'e yazılır ve write-behind kuyruğuna event eklenir.
5. Worker stream'i okuyup PostgreSQL'e `upsert/delete` uygular.

### Okuma akışı

1. İstek önce Redis hot set içinde aranır.
2. Bulunmazsa ileride page loader PostgreSQL'den ilgili pencereyi getirecektir.
3. `FetchPlan` boş değilse kayıtlı `RelationBatchLoader` preload yapar.

## 4. Tutarlılık Modeli

Sistem `eventual consistency` modelindedir.

Bu şu anlama gelir:

- kullanıcı Redis yazısını görür
- PostgreSQL commit'i daha sonra gerçekleşir
- Redis ve PostgreSQL kısa süreli farklı olabilir

Bu nedenle üretimde kritik konular:

- Redis AOF açılması
- durable stream/list kullanımı
- idempotent PostgreSQL yazımı
- retry ve dead-letter stratejisi
- version ordering

## 5. Reflection'sız Mapping Stratejisi

Reflection kullanılmayacağı için metadata üretimi annotation processor ile yapılmalıdır.

Örnek çıktı:

- `UserMeta`
- `OrderMeta`
- generated kolon listesi
- relation tanımları
- id alanı bilgisi

Entity codec iki farklı amaç taşır:

- Redis payload encode/decode
- PostgreSQL kolon değerleri üretimi

## 6. Modüller

### `cachedb-annotations`

- `@CacheEntity`
- `@CacheId`
- `@CacheColumn`
- `@CacheRelation`

### `cachedb-processor`

- annotation scanning
- generated `*Meta` sınıfları

### `cachedb-core`

- repository sözleşmeleri
- metadata sözleşmeleri
- fetch plan
- write operation modeli
- relation API
- cache policy
- runtime config

### `cachedb-storage-redis`

- Redis repository implementasyonu
- key naming stratejisi
- Redis Functions library/load/call katmanı
- write-behind enqueue
- stale event skip mantığı

### `cachedb-storage-postgres`

- JDBC tabanlı upsert/delete flusher
- version-gated update kuralları

### `cachedb-starter`

- bootstrap
- repository factory
- ortak wiring

## 7. Konfigürasyon Yaklaşımı

Sabit kabul bırakmamak için ayarlar initialization aşamasında dışarıdan verilir.

Başlıca runtime/init ayarları:

- `WriteBehindConfig`
  - worker thread sayısı
  - batch size
  - poll block süresi
  - retry sayısı ve backoff
  - consumer group ve stream key
  - shutdown bekleme süresi
  - daemon thread davranışı
- `ResourceLimits`
  - varsayılan entity cache policy
  - kayıtlı entity üst sınırı
  - operation başına kolon üst sınırı
- `CachePolicy`
  - hot entity limiti
  - page size
  - LRU etkinliği
  - entity TTL
  - page TTL
- `KeyspaceConfig`
  - Redis key prefix
  - entity/page/version segmentleri
- `RedisFunctionsConfig`
  - functions etkin/pasif
  - library auto-load davranışı
  - replace/strict load kuralları
  - library ve function isimleri
  - template resource veya override source
- `RelationConfig`
  - relation batch size
  - max fetch depth
  - missing preloader davranışı
- `PageCacheConfig`
  - read-through page cache davranışı
  - missing page loader davranışı
  - eviction batch size
- `AdminHttpConfig`
  - admin HTTP host/port/backlog
  - worker thread sayısı
  - CORS ve dashboard davranışı
- `IncidentEmailConfig`
  - SMTP auth mekanizması
  - implicit TLS / STARTTLS
  - truststore ve certificate pinning ayarları

## 8. Redis Functions Katmanı

Redis 8 için yazma akışı `FCALL` üzerinden yürütülebilir.

Önerilen akış:

1. Java tarafı entity write operation üretir.
2. `FCALL entity_upsert` ile entity key, version increment ve stream append tek atomik işlemde yapılır.
3. Worker aynı stream'i okuyup PostgreSQL'e flush eder.

Silme akışı:

1. `FCALL entity_delete`
2. version atomik artırılır
3. entity key silinir
4. delete olayı stream'e yazılır

Bu yaklaşımın faydaları:

- write + enqueue arasında ara durum kalmaz
- tek round-trip ile mutation tamamlanır
- concurrency ve retry davranışı daha deterministik hale gelir

## 9. Relation Batch Loading

`FetchPlan` preload isteğini taşır; gerçek batch relation yüklemesi entity bazlı `RelationBatchLoader` ile yapılır.

Kurallar:

- her entity için isteğe bağlı loader register edilebilir
- repository `findById/findAll` sonrasında `FetchPlan` boş değilse loader çağrılır
- preload relation başına değil entity batch'i üzerinden yapılır
- `failOnMissingPreloader=true` ise preload istenip loader yoksa hata verilir

## 10. Açık Tasarım Kararları

Henüz netleşmesi gereken başlıklar:

- entity API tam olarak Active Record mü olacak, yoksa repository + generated facade mı
- relation tanımı hangi düzeyde preload zorunlu kılacak
- query DSL ne kadar geniş olacak
- page cache boyutu global mi, entity tipi bazlı mı yönetilecek
- write-behind ordering key bazlı mı, entity tipi bazlı mı olacak
- PostgreSQL tarafında tombstone / soft-delete standardı gerekli mi
- version alanı son kullanıcı entity modeline yansıtılacak mı
- page cache materialization sadece id listesi mi, yoksa tam payload mu tutmalı

## 11. Admin ve Diagnostics Yüzeyi

Sistem artık sadece stream bazlı recovery değil, aynı zamanda işletimsel bir admin/debug yüzeyi de sunar.

- `CacheDatabaseAdmin`
  - DLQ, reconciliation, archive, diagnostics ve incidents export
  - manual replay/skip/close
  - metrics ve health
- `AdminIncidentWebhookNotifier`
  - persisted incident kayıtlarını asenkron webhook çağrılarına çevirir
  - retry/backoff ve queue kapasitesi config ile yönetilir
- `AdminIncidentDeliveryManager`
  - incident kaydını çoklu sink'e fan-out eder
  - webhook, Redis queue ve SMTP kanallarını destekler
  - channel bazlı delivery snapshot/last error bilgisi tutar
  - her kanal kendi retry/backoff ayarlarıyla çalışabilir
  - başarısız delivery denemelerini incident-delivery DLQ stream'ine yazar
- `AdminIncidentDeliveryRecoveryWorker`
  - incident delivery DLQ stream'ini ayrı consumer group ile tüketir
  - `XAUTOCLAIM` ile abandoned/pending entry'leri devralabilir
  - replay başarılıysa recovery stream'ine `REPLAYED`, değilse `FAILED` kaydı yazar
  - webhook, Redis queue ve SMTP kanalları için ikinci şans replay hattını sağlar
- `CacheDatabaseDebug`
  - query explain planı
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

Bu katman JDK `HttpServer` ile çalışır; ek framework bağımlılığı olmadan lokal operasyon ve gözlemleme yüzeyi verir.

## 12. Planner İstatistikleri

Query planner artık sadece process içi cache'e değil, Redis key-space içinde TTL ile tutulan estimate/histogram verilerine de dayanabilir.

- estimate anahtarları filter bazlı tutulur
- histogram anahtarları kolon bazlı tutulur
- range histogram hesaplaması eşit genişlik yerine rank/quantile benzeri bucket seçimiyle daha dengeli olur
- entity reindex/remove akışı planner stats anahtarlarını invalidate eder
- estimate modeli sample size ve selectivity ratio taşır
- text contains için token selectivity çarpımı ile daha iyi intersection tahmini yapılır
- learned statistics anahtarları query/group ifadesi bazlı tutulur
- query execution sonrası gerçek sonuç cardinality'si observe edilip sonraki explain/query planlarına ağırlıklı yansıtılır
- cache warming açıksa filtre/sort histogramları query execution öncesinde preload edilir
- multi-hop relation filtrelerinde hedef relation index sonucu owner id setine geri map edilerek selectivity hesaplanır
- admin metrics/dashboard planner estimate/histogram/learned key sayılarını görünür kılabilir

Bu sayede restart sonrası explain planı tamamen sıfırdan başlamak zorunda kalmaz.

## 13. Önerilen Sonraki Adımlar

1. Admin HTTP yüzeyini auth/role modeliyle sertleştirmek
2. Explain ve diagnostics için daha zengin dashboard/REST filtreleri eklemek
3. Failure injection ve load testlerle Redis/PostgreSQL hattını zorlamak
4. Planner istatistiklerini daha gelişmiş cardinality modeli ve domain heuristics ile zenginleştirmek

# Outbox ve CDC Apply Runner

English version: [../../docs/outbox-cdc-apply-runner.md](../../docs/outbox-cdc-apply-runner.md)

Bu sayfayı, kalıcı kaynak veritabanı CacheDB dışında da değişebiliyorsa ve
Redis'in güncel kalması gerekiyorsa kullan.

Örnek durumlar:

- geçiş sürecinde eski ORM hâlâ kaynak veritabanına yazıyor
- bir back-office batch işi satırları doğrudan güncelliyor
- Debezium veya outbox tablosu başka bir servisten değişiklik yayıyor
- incident sonrası repair/replay işi eski değişiklikleri yeniden işliyor

## Problem

Yazma işlemi CacheDB üzerinden geçerse Redis ve seçilen SQL provider aynı akış
içinde senkron kalır. Fakat başka bir sistem doğrudan kaynak veritabanına
yazarsa Redis bunu kendiliğinden bilemez. Bu değişikliğin CacheDB'ye ayrıca
beslenmesi gerekir.

Güvenli production deseni:

1. Veritabanı değişikliğini outbox tablosu veya CDC stream ile yakala.
2. Değişikliği `ExternalChangeEvent` haline getir.
3. `ExternalChangeApplyRunner` ile uygula.
4. Runner Redis hot entity, index, tombstone ve projection state'ini günceller;
   aynı değişikliği tekrar kaynak veritabanına yazmaz.

## Varsayılan Mod: Sadece Cache

BEST varsayılan:

```java
ExternalChangeApplyRunner runner = ExternalChangeApplyRunner
        .builder(cacheDatabase.session(), cacheDatabase.entityRegistry())
        .mode(ExternalChangeApplyMode.CACHE_ONLY)
        .build();

PostgresOutboxExternalChangeFeedAdapter adapter =
        PostgresOutboxExternalChangeFeedAdapter
                .builder(dataSource)
                .adapterName("orders-hotset")
                .outboxTable("cachedb_outbox")
                .batchSize(200)
                .pollIntervalMillis(500)
                .build();

adapter.start(runner);
```

Microsoft SQL Server için provider'a özel adapter'ı `cachedb-storage-mssql`
modülünden bağla:

```java
MssqlOutboxExternalChangeFeedAdapter adapter =
        MssqlOutboxExternalChangeFeedAdapter
                .builder(mssqlDataSource)
                .adapterName("orders-hotset")
                .outboxTable("cachedb_outbox")
                .checkpointTable("cachedb_outbox_adapter_checkpoint")
                .batchSize(200)
                .pollIntervalMillis(500)
                .build();

adapter.start(runner);
```

`CACHE_ONLY` şu anlama gelir:

- UPSERT, external event payload'ından Redis'i hydrate eder.
- DELETE, Redis tombstone yazar ve hot/index/projection state'ini temizler.
- Write-behind kuyruğu kullanılmaz.
- Kaynak veritabanına tekrar yazılmaz.

Bu davranış, kaynak veritabanından gelen bir event'in yeniden aynı veritabanına
CacheDB komutu gibi yazılmasını ve döngü üretmesini engeller.

## Event Şekli

Varsayılan UPSERT için registered `EntityCodec.fromColumns(...)` tarafından
hydrate edilebilen tam satır payload'ı gerekir.

Örnek:

```java
new ExternalChangeEvent(
        "OrderEntity",
        "10042",
        ExternalChangeType.UPSERT,
        Map.of(
                "order_id", 10042L,
                "customer_id", 501L,
                "order_date", orderDate,
                "order_amount", amount,
                "currency_code", "TRY",
                "status", "PAID"
        ),
        18L,
        Instant.now(),
        "postgres-outbox"
);
```

DELETE, codec id kolonundan doğru Java id tipini çözebiliyorsa sadece id
taşıyabilir:

```java
new ExternalChangeEvent(
        "OrderEntity",
        "10042",
        ExternalChangeType.DELETE,
        Map.of(),
        19L,
        Instant.now(),
        "postgres-outbox"
);
```

## Partial Payload

ANTI-PATTERN:

- `{ "status": "PAID" }` event'i almak
- Redis'te kayıt varsa onunla sessizce merge etmeye çalışmak
- Redis'te kayıt yoksa eksik veriden tam entity üretmek

Partial event için explicit handler yaz:

```java
ExternalChangeApplyRunner runner = ExternalChangeApplyRunner
        .builder(cacheDatabase.session(), cacheDatabase.entityRegistry())
        .handler("OrderEntity", event -> {
            // Kararı bilinçli ver: tam satırı oku, partial event'i reddet
            // veya domain'e özel projection update route'una gönder.
            return ExternalChangeApplyResult.ignored(
                    event,
                    event.id(),
                    ExternalChangeApplyMode.CACHE_ONLY,
                    "Partial order patch, order command route tarafından ele alınıyor"
            );
        })
        .build();
```

BEST: Partial update davranışı explicit command veya handler tarafından
sahiplenilmelidir.

## Version ve Idempotency

Redis repository, external event'i uygulamadan önce mevcut version/tombstone
version değerini kontrol eder.

Davranış:

- Redis'te daha yeni version varsa event atlanır.
- Aynı event tekrar işlenirse güvenli biçimde uygulanabilir.
- Event pozitif version taşımıyorsa stale-event koruması yapılamaz.

BEST: Outbox ve CDC event'leri entity başına monoton artan version taşımalıdır.

ACCEPTABLE: Event id sırası stabildir ve entity stream'inin tek sahibi tek apply
runner'dır.

ANTI-PATTERN: Version bilgisi olmayan ve sıralı gelmeyen external event akışı.

## Checkpoint Davranışı

`PostgresOutboxExternalChangeFeedAdapter`, checkpoint'i yalnızca
`ExternalChangeSink.accept(...)` başarılı döndükten sonra ilerletir.

`MssqlOutboxExternalChangeFeedAdapter` da aynı kuralı uygular. Checkpoint'i
SQL Server üzerinde `MERGE` kullanmadan, lock-guarded update-then-insert
ifadesiyle saklar.

JDBC outbox adapter'larında `pollOnce(...)` okuma, apply ve checkpoint
ilerletme adımlarını tek veritabanı transaction'ı içinde yürütür. Aynı
`adapterName` ile çalışan poller'lar aynı checkpoint satırını paylaşır.
Adapter, sıradaki batch'i okumadan önce checkpoint satırını kilitlediği için iki
Kubernetes pod'u aynı aralığı eşzamanlı uygulamaz.
MSSQL adapter'ı ilk açılıştaki checkpoint tablo oluşturma adımını kısa süreli
SQL Server application lock ile sıraya alır. Checkpoint satırı aynı anda başka
bir pod tarafından oluşturulmuşsa duplicate-key sonucunu başarılı sahiplik
devri kabul eder. Kontrollü production ortamlarında outbox ve checkpoint
tablolarını normal şema migration süreciyle önceden oluşturmak yine daha temiz
yoldur.

Bu bir güvenlik modelidir; işleme kapasitesini artırma modeli değildir. Bir
`adapterName`, tek mantıksal consumer anlamına gelir. Bir route gerçekten
aktif-aktif outbox kapasitesi istiyorsa outbox stream partition edilmeli ve her
partition için ayrı adapter adı ve ayrı checkpoint kullanılmalıdır.

Apply runner başarısız olursa:

- adapter event'i kabul etmiş saymaz
- checkpoint ilerlemez
- sonraki poll aynı event'i yeniden deneyebilir

Bu bilinçli bir production davranışıdır. Outbox ilerlesin diye apply hatasını
gizlemek doğru değildir.

## Production Kontrol Listesi

- Outbox'ta görünebilecek her entity registered olmalı.
- Generated binding kullan veya `EntityCodec.fromColumns(...)` implement et.
- Veritabanı kaynaklı event için varsayılan olarak `CACHE_ONLY` kullan.
- `CACHE_AND_WRITE_BEHIND` yalnızca normal CacheDB write akışından geçmesi
  gereken güvenilir command event'leri için kullanılmalı.
- UPSERT event'inde entity id, event type, version, occurred-at, source ve tam
  satır payload'ı bulunmalı.
- Accepted, ignored, failed ve retried event metrikleri izlenmeli.
- Outbox adapter adı sabit tutulmalı; ad değişirse yeni checkpoint oluşur.
- Kubernetes'te aynı `adapterName` yalnızca pod'lar arasında tek, sıraya alınmış
  mantıksal consumer istendiğinde paylaşılmalı.
- Partitioned adapter adları yalnızca outbox stream açıkça bölündüyse ve her
  partition'ın sahiplik sınırı netse kullanılmalı.

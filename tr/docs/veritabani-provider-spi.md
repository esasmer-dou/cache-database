# Veritabanı Sağlayıcı SPI

English version: [../../docs/database-provider-spi.md](../../docs/database-provider-spi.md)

CacheDB public beta aşamasında hâlâ PostgreSQL odaklıdır. Bu çalışmayla storage
provider tarafına ilk açık SPI katmanı eklendi. Bu yüzden MSSQL desteği artık
"JDBC URL'yi değiştir, aynı kod çalışır" gibi riskli ve yanıltıcı bir yaklaşım
üzerinden ilerlemiyor.

## Karar

BEST: Kalıcı SQL sağlayıcısını bilinçli olarak seçmek ve ona uygun
write-behind flusher'ını açıkça bağlamak.

ACCEPTABLE: Uygulama yalnızca PostgreSQL kullanıyorsa starter'ın varsayılan
PostgreSQL yolunu kullanmak.

ANTI-PATTERN: PostgreSQL flusher'ını MSSQL JDBC URL'sine bağlayıp aynı SQL'in,
aynı retry davranışının ve aynı parametre limitlerinin güvenli olduğunu
varsaymak.

## Mevcut Modül Yapısı

```text
cachedb-storage-jdbc
- JdbcDatabaseDialect
- JdbcWriteBehindSupport
- SqlFailureClassifierSupport
- JdbcOutboxExternalChangeFeedAdapter
- ortak value conversion, row-limit ve batch partition yardımcıları

cachedb-storage-postgres
- PostgresDatabaseDialect
- PostgresWriteBehindFlusher
- PostgresFailureClassifier
- PostgresOutboxDialect
- PostgreSQL ON CONFLICT ve opsiyonel COPY yolu

cachedb-storage-mssql
- MssqlDatabaseDialect
- MssqlWriteBehindFlusher
- MssqlFailureClassifier
- MssqlOutboxExternalChangeFeedAdapter
- SQL Server update/existence/insert yazma yolu
```

`cachedb-starter`, geriye dönük uyumluluk için PostgreSQL flusher'ını varsayılan
tutar. PostgreSQL dışındaki kullanıcılar `WriteBehindFlusherFactory` değerini
açıkça vermelidir.

## PostgreSQL Kullanımı

PostgreSQL kullanan uygulamalarda starter ile ek provider wiring gerekmez:

```java
CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, postgresDataSource)
        .register(db -> {
            // generated entity ve projection kayıtları
        })
        .start();
```

Varsayılan flusher `PostgresWriteBehindFlusher` olarak kalır.

## MSSQL Kullanımı

MSSQL için storage modülünü ve Microsoft SQL Server JDBC driver'ını ekleyin.
CacheDB driver versiyonunu transitively zorlamaz; `DataSource` yönetimi
uygulamanın sorumluluğundadır.

```xml
<dependency>
  <groupId>com.reactor.cachedb</groupId>
  <artifactId>cachedb-storage-mssql</artifactId>
  <version>0.1.0-beta.3</version>
</dependency>

<dependency>
  <groupId>com.microsoft.sqlserver</groupId>
  <artifactId>mssql-jdbc</artifactId>
  <version><!-- platformunuzun onayladığı versiyon --></version>
</dependency>
```

Flusher'ı açıkça bağlayın:

```java
import com.reactor.cachedb.mssql.MssqlWriteBehindFlusher;

CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, mssqlDataSource)
        .writeBehindFlusherFactory(MssqlWriteBehindFlusher::new)
        .register(db -> {
            // generated entity ve projection kayıtları
        })
        .start();
```

Bu açıklık bilinçli bir tasarım kararıdır. Amaç, bir uygulamanın farkında
olmadan PostgreSQL SQL'ini SQL Server üzerinde çalıştırmasını engellemektir.

Veritabanı kaynaklı event'ler için MSSQL outbox adapter'ını da açıkça kullan:

```java
import com.reactor.cachedb.mssql.MssqlOutboxExternalChangeFeedAdapter;

MssqlOutboxExternalChangeFeedAdapter adapter =
        MssqlOutboxExternalChangeFeedAdapter
                .builder(mssqlDataSource)
                .adapterName("orders-hotset")
                .outboxTable("cachedb_outbox")
                .checkpointTable("cachedb_outbox_adapter_checkpoint")
                .batchSize(200)
                .build();

adapter.start(externalChangeApplyRunner);
```

## MSSQL Yazma Semantiği

MSSQL flusher varsayılan olarak `MERGE` kullanmaz. Public beta için daha güvenli
yol şudur:

1. Tek transaction açılır.
2. Isolation seviyesi `SERIALIZABLE` yapılır.
3. Version guard ile `UPDATE ... WITH (UPDLOCK, HOLDLOCK)` denenir.
4. Satır güncellenmediyse aynı lock hint'leriyle satır var mı diye bakılır.
5. Satır yoksa insert yapılır.
6. Bütün batch commit edilir veya tek parça roll back edilir.

Bu tasarım, maksimum bulk throughput yerine correctness ve idempotency tarafını
önceliklendirir. Bulk copy, table-valued parameter ve MSSQL'e özel outbox
checkpoint SQL'i ayrı GA sertleştirme işleridir.

Büyük `flushBatch(...)` çağrıları `WriteBehindConfig.maxFlushBatchSize()`
değerine göre parçalanır. Böylece SQL Server, Redis'ten gelen bütün batch için
tek ve büyük bir serializable transaction tutmaz. Sonraki parçalardan biri hata
alırsa önceki parçalar commit edilmiş olabilir; version guard retry davranışını
idempotent tutar.

## Önemli Provider Farkları

| Alan | PostgreSQL | MSSQL |
| --- | --- | --- |
| Upsert | `INSERT ... ON CONFLICT` | version guard'lı update/existence/insert transaction |
| Bulk load | opsiyonel `COPY` yolu | bu beta SPI'da açık değil |
| Parametre limiti | 65.535 parametre | 2.100 parametre |
| Geçici tablo | PostgreSQL temp table semantiği | SQL Server `#temp` semantiği, henüz bağlanmadı |
| Hata sınıflandırma | SQLSTATE odaklı | SQL Server vendor code odaklı |
| Production durumu | varsayılan public beta provider | açıkça seçilen beta provider, GA değil |

## Mevcut MSSQL Kapısı

MSSQL şu anda write-behind semantiğini bilinçli beta testlerine açar; production
sertifikalı değildir.

Provider evidence lane ile artık doğrulananlar:

- yalnızca unit-level SQL recorder değil, gerçek SQL Server integration lane
- canlı SQL Server üzerinde parameter-limit ve batch-size regresyon testleri
- MSSQL outbox/checkpoint adapter
- SQL Server metadata üstünde migration discovery, warm ve side-by-side comparison
- MSSQL kalıcı storage iken çok pod'lu apply runner smoke testi

MSSQL için GA demeden önce hâlâ kapanması gerekenler:

- stale version, duplicate id, deadlock, timeout ve lock-conflict testlerinin
  smoke ölçeği dışında, daha yüksek concurrency ile doğrulanması
- daha uzun SQL Server soak/restart/retry kanıtı
- aynı outbox stream'ini aktif-aktif poll eden pod'lar için sahiplik stratejisi
- gerçekçi tablo hacimleriyle MSSQL migration warm ve comparison kanıtı
- dashboard ve raporlarda PostgreSQL ile MSSQL storage metriklerini ayıran etiketler

Bu kapılar tamamlanana kadar MSSQL açıkça seçilen bir beta provider olarak kalır.

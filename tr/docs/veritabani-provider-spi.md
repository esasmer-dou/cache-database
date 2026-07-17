# Veritabanı Sağlayıcı SPI

English version: [../../docs/database-provider-spi.md](../../docs/database-provider-spi.md)

CacheDB, PostgreSQL'i varsayılan kalıcı SQL provider olarak kullanır. Bunun
yanında storage provider tarafında açık bir SPI katmanı vardır ve MSSQL kendi
SQL Server evidence hattı olan açık bir provider olarak desteklenir. Bu yüzden
MSSQL desteği artık "JDBC URL'yi değiştir, aynı kod çalışır" gibi riskli ve
yanıltıcı bir yaklaşım üzerinden ilerlemiyor.

## Karar

BEST: Kalıcı SQL sağlayıcısını bilinçli olarak seçmek ve ona uygun
write-behind flusher'ını açıkça bağlamak.

ACCEPTABLE: Uygulama bilinçli olarak varsayılan PostgreSQL provider yolunu
kullanıyorsa starter'ın varsayılan wiring'ini kullanmak.

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
- MssqlWriteBehindOptions
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
  <version>0.5.0</version>
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
import com.reactor.cachedb.mssql.MssqlWriteBehindOptions;

MssqlWriteBehindOptions mssqlOptions = MssqlWriteBehindOptions.dedicatedWorkerPoolDefaults()
        .toBuilder()
        .lockTimeoutMillis(5_000)
        .queryTimeoutSeconds(10)
        .build();

CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, mssqlDataSource)
        .writeBehindFlusherFactory(MssqlWriteBehindFlusher.factory(mssqlOptions))
        .register(db -> {
            // generated entity ve projection kayıtları
        })
        .start();
```

Bu açıklık bilinçli bir tasarım kararıdır. Amaç, bir uygulamanın farkında
olmadan PostgreSQL SQL'ini SQL Server üzerinde çalıştırmasını engellemektir.

Spring Boot kullanıyorsan MSSQL modülü ve Microsoft JDBC driver uygulama
classpath'inde olmalı; provider seçimini de açıkça yapmalısın:

```yaml
cachedb:
  sql:
    provider: mssql
    mssql:
      lock-timeout-millis: 5000
      query-timeout-seconds: 10
      transaction-isolation: serializable
      restore-lock-timeout-after-transaction: true
```

CacheDB, SQL Server connection pool'unu uygulamanın başka parçalarıyla
paylaşıyorsa `restore-lock-timeout-after-transaction=true` güvenli seçimdir.
CacheDB write-behind için ayrı bir `DataSource` kullanıyorsan
`MssqlWriteBehindOptions.dedicatedWorkerPoolDefaults()` daha az ek SQL çağrısı
yapar; her transaction sonunda `SELECT @@LOCK_TIMEOUT` ile eski ayarı okumaz.

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

MSSQL flusher varsayılan olarak `MERGE` kullanmaz. Daha güvenli provider yolu
şudur:

1. Tek transaction açılır.
2. Açıksa SQL Server için sonlu `LOCK_TIMEOUT` uygulanır.
3. Session, update, existence, insert ve delete statement'larına JDBC query timeout uygulanır.
4. Varsayılan isolation seviyesi `SERIALIZABLE` yapılır.
5. Version guard ile `UPDATE ... WITH (UPDLOCK, HOLDLOCK)` denenir.
6. Satır güncellenmediyse aynı lock hint'leriyle satır var mı diye bakılır.
7. Satır yoksa insert yapılır.
8. Bütün batch commit edilir veya tek parça roll back edilir.
9. Shared-pool modu açıksa önceki `LOCK_TIMEOUT` değeri geri yüklenir.

Bu tasarım, maksimum toplu yazma kapasitesi yerine doğruluk ve idempotency
tarafını önceliklendirir. Bulk copy ve table-valued parameter kullanımı ayrı GA
sertleştirme işleridir.

Büyük `flushBatch(...)` çağrıları `WriteBehindConfig.maxFlushBatchSize()`
değerine göre parçalanır. Böylece SQL Server, Redis'ten gelen bütün batch için
tek ve büyük bir serializable transaction tutmaz. Sonraki parçalardan biri hata
alırsa önceki parçalar commit edilmiş olabilir; version guard retry davranışını
idempotent tutar.

## Önemli Provider Farkları

| Alan | PostgreSQL | MSSQL |
| --- | --- | --- |
| Upsert | `INSERT ... ON CONFLICT` | version guard'lı update/existence/insert transaction |
| Bulk load | opsiyonel `COPY` yolu | varsayılan olarak bilinçli şekilde kapalı; önce route bazlı batch kullanılmalı |
| Parametre limiti | 65.535 parametre | 2.100 parametre |
| Geçici tablo | PostgreSQL temp table semantiği | SQL Server `#temp` semantiği, henüz bağlanmadı |
| Hata sınıflandırma | SQLSTATE odaklı | SQL Server vendor code odaklı |
| Timeout sözleşmesi | PostgreSQL driver/socket ayarları | `MssqlWriteBehindOptions` ile JDBC driver/pool ayarları birlikte |
| Spring Boot provider seçimi | varsayılan | modül classpath'teyse `cachedb.sql.provider=mssql` |
| Production durumu | varsayılan provider yolu | SQL Server CI kanıtı olan açık provider; HA topolojisi uygulama ortamında kanıtlanmalı |

## Mevcut MSSQL Kapısı

MSSQL, write-behind, outbox, migration planner ve çok pod'lu apply-runner
davranışı için açık provider olarak kullanılabilir. Provider seviyesindeki claim
artık SQL Server doğruluk ve regresyon kanıtlarıyla CI hattına bağlıdır. Kalan
sınır "provider çalışıyor mu?" sorusu değildir; tüketen uygulamanın kendi SQL
Server topolojisini, veri hacmini, route envanterini ve rollback planını
kanıtlamasıdır.

Provider evidence lane ile artık doğrulananlar:

- yalnızca unit-level SQL recorder değil, gerçek SQL Server integration lane
- canlı SQL Server üzerinde parameter-limit ve batch-size regresyon testleri
- yüksek hacimli write-behind yükü, eski sürüm ve silme kontrolleri
- aynı primary key üzerinde eşzamanlı yazma yarışı, duplicate-id ve stale-version
  baskısı
- provider seviyesinde sonlu lock/query timeout seçenekleri
- retryable timeout, deadlock ve lock-conflict hata sınıflandırması
- bloklanan satır üzerinde canlı SQL Server lock-timeout sınıflandırması
- MSSQL outbox/checkpoint adapter
- Spring Boot için `cachedb.sql.provider=mssql` ile açık provider wiring'i
- gerçekçi windowed tablo hacmiyle SQL Server metadata üstünde migration
  discovery, warm ve side-by-side comparison
- MSSQL kalıcı storage iken çok pod'lu apply runner smoke testi
- pod'lar eşzamanlı başlarken checkpoint tablosu bootstrap adımının kilit ile
  korunması
- eşzamanlı polling sırasında checkpoint satırı bootstrap adımının duplicate-key
  sonucunu güvenli kabul etmesi
- aynı `adapterName` ile çalışan eşzamanlı poller'ların checkpoint satırı
  kilidiyle korunması
- provider adıyla etiketlenen kalıcı SQL yazma performans kırılımları
  (`mssql:*`)
- tek node SQL Server container restart/reconnect regresyonu
- lokal Docker listener-failover preflight: sabit bir TCP listener arkasında iki
  SQL Server container, backend değişince eski JDBC connection'ın geçersiz
  kalması ve yeni JDBC connection'ın ikinci backend'e gitmesi

Belirli bir MSSQL production topolojisi için hâlâ kanıtlanması gerekenler:

- provider evidence hattını uygulamanın onayladığı SQL Server sürümü, JDBC
  driver'ı, connection pool ayarı, şema hacmi ve indeksleriyle çalıştırmak
- ortak Always On ortamında failover tetikleyemiyorsan staging öncesinde lokal
  listener-failover preflight'i çalıştırmak:

  ```powershell
  pwsh ./tools/ci/run-local-mssql-listener-failover-evidence.ps1
  ```

  Bu komut, listener benzeri sabit bir endpoint üzerinden yeniden bağlanma
  davranışını kanıtlar. Always On replikasyonunu, quorum davranışını, read-only
  routing'i veya yönetilen failover politikasını sertifikalandırmaz.
- production boyutuna yakın veri ve gerçek trafik karışımıyla daha uzun SQL
  Server soak/retry testi yapmak
- uygulamanın availability iddiası SQL Server HA veya Always On içeriyorsa bunu
  staging ortamında ayrıca kanıtlamak
- route aktif-aktif polling kapasitesi istiyorsa partitioned outbox ownership
  tasarlamak; aynı `adapterName` güvenlik için bilinçli olarak sıraya alınır
- uygulamanın migration envanterindeki her production route için Migration
  Planner warm ve comparison çalıştırmak

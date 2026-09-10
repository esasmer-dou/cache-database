# Oracle Database Provider

English version: [../../docs/oracle-provider.md](../../docs/oracle-provider.md)

CacheDB, Oracle Database 19c ve üzerini JDBC Thin sürücüsüyle destekler. Provider;
sınırlı kaynak okumalarını, Redis öncelikli write-behind yazmayı, warm ve
uzlaştırmayı, outbox/checkpoint okumayı, migration keşfi ve karşılaştırmasını,
ayrıca Redis bellek tahminini kapsar. PostgreSQL SQL'ini Oracle JDBC adresine
yönlendiren bir çözüm değildir.

## Destek Sözleşmesi

| Alan | Sözleşme |
| --- | --- |
| Veritabanı | Oracle Database 19c veya üzeri; CI, Oracle Database Free 23 kullanır |
| Java | Java 17 veya üzeri; örnek ve CI Java 21 kullanır |
| Sürücü | Oracle starter ile gelen `com.oracle.database.jdbc:ojdbc17` |
| JDBC adresi | `jdbc:oracle:thin:@//sunucu:1521/servis` biçimindeki Thin servis adı |
| Kimlik | Redis komutu kabul etmeden önce üretilir |
| Sürüm | Açık sayısal sürüm kolonu; eski yazılar reddedilir |
| Boş metin | Varsayılan `REJECT` veya açık `NORMALIZE_TO_NULL` kararı |
| Okuma sınırı | Sınırlı satır; `IN` değerleri 900 öğelik gruplara ayrılır |
| Çalışma zamanı DDL'i | Outbox/checkpoint için varsayılan olarak kapalıdır |

## Spring Boot Kurulumu

CacheDB BOM'u içe aktar ve yalnızca bir provider starter ekle:

```xml
<dependency>
    <groupId>com.reactor.cachedb</groupId>
    <artifactId>cachedb-spring-boot-starter-oracle</artifactId>
</dependency>
```

Starter, desteklenen `ojdbc17` çalışma zamanı sürücüsünü getirir. Classpath'te
tek Oracle JDBC sürümü tut. Platform ekibi başka bir onaylı sürüm yönetiyorsa
transitive sürücüyü dışla ve tam olarak o kombinasyonu uygulamanın sertifika
hattında test et.

```yaml
spring:
  datasource:
    url: jdbc:oracle:thin:@//db.example.internal:1521/ORDER_SERVICE
    username: cachedb_app
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 12
      minimum-idle: 2
      connection-timeout: 3000
      validation-timeout: 1500

cachedb:
  sql:
    provider: ORACLE
    oracle:
      query-timeout-seconds: 10
      transaction-isolation: READ_COMMITTED
      duplicate-race-retries: 2
      empty-string-policy: REJECT
```

Classpath'te yalnızca Oracle provider varsa `AUTO` da çalışır. Canlı ortam
ayarında `ORACLE` değerini açıkça yazmak daha güvenlidir. Böylece yanlışlıkla
ikinci provider eklendiğinde başlangıç durur ve `cachedb:doctor` build
sözleşmesini kontrol eder.

## Plain Java Kurulumu

```xml
<dependency>
    <groupId>com.reactor.cachedb</groupId>
    <artifactId>cachedb-storage-oracle</artifactId>
</dependency>
```

```java
OracleWriteBehindOptions options = OracleWriteBehindOptions.builder()
        .queryTimeoutSeconds(10)
        .transactionIsolation(Connection.TRANSACTION_READ_COMMITTED)
        .duplicateRaceRetries(2)
        .emptyStringPolicy(OracleWriteBehindOptions.EmptyStringPolicy.REJECT)
        .build();

CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, oracleDataSource)
        .writeBehindFlusherFactory(OracleWriteBehindFlusher.factory(options))
        .register(registry -> {
            // Üretilen entity, projection ve route kayıtları.
        })
        .start();
```

## Entity Sözleşmesi

CacheDB, Oracle yazması tamamlanmadan önce komutu Redis'te kabul eder. Bu nedenle
kalıcı kimlik o anda bilinmelidir. Kimliği uygulamada veya Redis komut yolunda
üret. CacheDB kabulünden sonra Oracle sequence, identity kolonu veya trigger ile
kimlik belirlenmesine güvenme.

Değiştirilebilen her tabloda sayısal bir sürüm kolonu bulunmalıdır. Provider bu
kolonu sürüm kontrollü `MERGE` ve silme ifadelerinde kullanır. Aynı ya da daha
eski sürümle yapılan retry, daha yeni kalıcı satırı ezemez.

```sql
CREATE TABLE orders (
    order_id NUMBER(19) PRIMARY KEY,
    customer_id NUMBER(19) NOT NULL,
    status VARCHAR2(24) NOT NULL,
    order_amount NUMBER(19, 4) NOT NULL,
    entity_version NUMBER(19) DEFAULT 0 NOT NULL,
    deleted VARCHAR2(16)
);

CREATE INDEX idx_orders_customer_status
    ON orders(customer_id, status, order_id);
```

Foreign key'ler veritabanı bütünlüğünü korur. `@CacheRelation`, ilişkili
modellerin CacheDB tarafından nasıl yükleneceğini ve birleştirileceğini anlatır;
Oracle foreign key oluşturmaz ve onun yerini almaz.

## Şema Hazırlama

CacheDB, şemayı doğrulamadan veya DDL üretmeden önce sağlayıcıya özel şema
dialect'ini çözer. Oracle identifier'ları Oracle metadata harf düzeniyle
eşleştirilir; üretilen DDL `NUMBER`, `VARCHAR2` ve Oracle timestamp tiplerini
kullanır. Geliştirme ortamındaki `CREATE_IF_MISSING`, temel veri tiplerinden
oluşan entity tablolarını oluşturabilir. Canlı ortamda migration ve
`VALIDATE_ONLY` kullanmak daha güvenlidir.

Şema hazırlama hataları uygulama başlangıcını durdurur. Tablo veya zorunlu kolon
eksikse, veritabanı ürünü desteklenmiyorsa ya da Oracle DDL'i reddederse
`SchemaBootstrapException` oluşur. Partition, gelişmiş indeks, LOB, sanal kolon,
trigger, PL/SQL veya tablespace/storage kuralı için genel şema hazırlamayı
kullanma; bunları gözden geçirilmiş Oracle migration'larında tut.

## Desteklenen Değer Biçimleri

| Java değeri | Önerilen Oracle kolonu |
| --- | --- |
| `int` / `Integer` | `NUMBER(10)` |
| `long` / `Long` | `NUMBER(19)` |
| `BigInteger` | `NUMBER(38, 0)` |
| `boolean` / `Boolean` | `NUMBER(1)` |
| `BigDecimal` | `NUMBER(precision, scale)` |
| `double` / `Double` | `BINARY_DOUBLE` |
| `float` / `Float` | `BINARY_FLOAT` |
| `String` | uzunluğu sınırlı `VARCHAR2` |
| `Instant` / `OffsetDateTime` | `TIMESTAMP WITH TIME ZONE` |
| `LocalDateTime` | `TIMESTAMP` |
| `LocalDate` | `DATE` |

Genel ORM yüzeyi; PL/SQL prosedür çağrılarını, Oracle UDT/OBJECT, `ARRAY`,
`STRUCT`, `XMLTYPE`, `SDO_GEOMETRY`, LOB streaming ve vendor'a özgü toplu
API'leri bilinçli olarak kapsamaz. Bu işlemleri sınırlı süre, satır ve bellek
sözleşmesi olan açık bir JDBC adapter veya kaynak komutunda tut.

## Boş Metin Kuralı

Oracle boş karakter değerini `NULL` olarak saklar. Sessiz dönüşüm, "değer yok"
ile "değer var ama boş" durumlarını ayıran bir iş kuralını bozabilir. Varsayılan
policy, SQL çalışmadan önce boş Java metnini reddeder:

```yaml
cachedb.sql.oracle.empty-string-policy: REJECT
```

`NORMALIZE_TO_NULL` değerini yalnızca veritabanı ve API sözleşmesi bu iki durumu
aynı kabul ediyorsa seç. Bu karar kalıcı yazmalara uygulanır; API girişinde
yanlışlıkla gelen boş değerler yine doğrulanmalıdır.

## Yazma ve Okuma Davranışı

Write-behind worker, işlemleri SQL biçimine göre gruplar ve prepared statement
batch'lerini sınırlı transaction'larda çalıştırır. Upsert, sürüm kontrollü tek
satırlı `MERGE` kullanır. İlk insert sırasında oluşan eşzamanlı `ORA-00001`
yarışında kalıcı sürüm okunur ve yalnızca güvenliyse retry yapılır. Silme de aynı
sürüm kontrolünü taşır. Erişilebilirlik, timeout, serialization, deadlock ve
kilit hataları; constraint, veri, şema, yetki ve eski sürüm hatalarından ayrı
sınıflandırılır.

Kaynak okumaları Oracle `OFFSET ... FETCH NEXT`, deterministik kimlik sıralaması,
JDBC sorgu süresi, fetch-size ve en fazla satır sınırı kullanır. Oracle tek bir
`IN` listesinde en fazla 1.000 ifade kabul ettiği için CacheDB, değerleri 900'lük
gruplara ayırıp `OR` ile birleştirir. Bu davranış `ORA-01795` hatasını önler;
sınırsız sorguyu güvenli hâle getirmez.

## Outbox ve Harici Değişiklikler

Başka bir uygulama aynı Oracle tablolarına yazıyorsa değişiklikleri outbox/CDC
ile yayımla veya ölçülmüş bir uzlaştırma gecikmesini açıkça kabul et. Periyodik
warm tek başına olay aktarım mekanizması değildir.

Checkpoint tablosunu migration ile kur:

```sql
CREATE TABLE cachedb_outbox_adapter_checkpoint (
    adapter_name VARCHAR2(200) PRIMARY KEY,
    last_event_id NUMBER(19) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
```

```java
OracleOutboxExternalChangeFeedAdapter adapter =
        OracleOutboxExternalChangeFeedAdapter.builder(dataSource)
                .adapterName("orders-active-set")
                .outboxTable("cachedb_outbox")
                .checkpointTable("cachedb_outbox_adapter_checkpoint")
                .batchSize(200)
                .createCheckpointTable(false)
                .build();

adapter.start(externalChangeApplyRunner);
```

Aynı `adapterName` değerini kullanan poller'lar checkpoint satırında sıraya
girer. Bu, güvenli bir çok pod sahiplik modelidir; aktif-aktif throughput
sağlamaz. Ayrı partition'lar ancak çakışmayan açık bir sahiplik sözleşmesiyle
kullanılmalıdır.

## Havuz ve Batch Ayarı

Hikari değerlerini belirlemeden önce SQL oturum bütçesini hesapla:

```text
toplam uygulama oturumu = replica sayısı * maximumPoolSize
gerekli veritabanı payı = toplam uygulama oturumu
                         + migration/operasyon payı
                         + failover yeniden bağlanma payı
```

Küçük yazma transaction'larıyla başla. Redo üretimini ve satır kilidi süresini
ölç; `maxFlushBatchSize` değerini yalnızca p95 kalıcılık gecikmesi ve veritabanı
yükü bütçe içinde kalıyorsa artır. Warm ve arşiv worker'larını ayrıca sınırla;
write-behind için gereken bütün oturumları tüketmelerine izin verme.

JDBC statement timeout, ağ bağlantı ve okuma sürelerinin yerini tutmaz. Bu
süreleri Oracle JDBC adresinde/DataSource'ta platformun failover politikasına
göre ayarla. Kubernetes readiness, kalıcı yazma kuyruğuna ve bağlantının
toparlanmasına duyarlı olmalıdır. Liveness ise Oracle geçici olarak
erişilemediğinde sağlıklı uygulama sürecini gereksiz yere yeniden başlatmamalıdır.

## Migration Planner

Oracle şema keşfi, bağlı kullanıcının güncel şemasıyla sınırlıdır ve Oracle
sistem şemalarını dışarıda bırakır. Planner; tablo/view ve foreign key keşfi,
route adayı üretimi, sınırlı warm/dry-run, kaynak ile CacheDB üyelik/sıralama
karşılaştırması ve Oracle istatistiklerinden Redis bellek tahmini yapabilir.
İstatistik yoksa sınırlı JDBC örneklemesine döner.

İstatistikler eski olabilir. Bellek bütçesini onaylamadan önce staging warm
sonrasındaki gerçek Redis `MEMORY USAGE` ölçümüyle tahmini karşılaştır.

## Kanıt ve HA Sınırı

Tek instance provider hattını Docker üzerinde çalıştır:

```powershell
pwsh ./tools/ci/run-oracle-provider-evidence.ps1 -RestartOracleContainer
```

Hat; canlı yazma/okuma doğruluğunu, eski sürümü, eşzamanlı insert yarışını,
1.201 değerlik sorgunun parçalanmasını, outbox/checkpoint okumayı, çok pod
checkpoint sahipliğini, migration warm/compare akışını, gecikmeli ağ davranışını,
throughput eşiğini ve container restart/reconnect akışını kapsar.

### Yerel fiziksel Data Guard hattı

Repository'de, iki Oracle 19c Enterprise instance'ı kullanan ve veritabanı
rollerini gerçekten değiştiren ayrı bir fiziksel Data Guard hattı da bulunur.
Bu hat için şunlar gerekir:

- En az 14 GiB bellek, 6 CPU ve yaklaşık 30 GiB boş Docker disk alanı ayrılmış Docker Desktop
- Java 21 ve Maven
- lisansı kabul edilerek önceden hazırlanmış yerel `oracle/database:19.3.0-ee` image'ı
- public `haproxy:2.9` image'ı

Runner Oracle image'ını indirmez ve senin adına lisans kabul etmez. Image'ı
Oracle lisans koşullarına göre hazırladıktan sonra şu komutu çalıştır:

Varsayılan `19.3.0-ee` imajı, framework sözleşmesini Oracle 19c taban sürümüyle
kanıtlar; güncel Release Update (RU) için kanıt oluşturmaz. Sürüm onayında
`-OracleImage` parametresine kurumun lisanslı, onaylı ve güncel RU ile yamalanmış
imajını ver; rapordaki imaj kimliğini de kanıtla birlikte sakla.

```powershell
pwsh ./tools/ci/run-local-oracle-dataguard-evidence.ps1 `
  -MavenExecutable C:\apache-maven-3.9.9\bin\mvn.cmd
```

Runner primary ve fiziksel standby veritabanlarını kurar; force logging,
standby redo log, flashback ve Data Guard Broker ayarlarını açar. Broker'ın eksik
redo boşluğu raporlamadığını doğruladıktan sonra şu kontrolleri sırayla çalıştırır:

1. broker ile planlı switchover
2. eski JDBC bağlantısının reddedilmesi ve Hikari bağlantı havuzunun, iki listener
   adresi ile tek servis adı içeren Oracle JDBC tanımı üzerinden toparlanması
3. yeni primary üzerinde Oracle provider kanıtlarının tamamı
4. broker ile eski rollere dönüş
5. primary container'ın zorla durdurulması ve broker ile acil failover
6. ikinci Hikari toparlanması ve provider kanıtlarının yeniden çalıştırılması
7. eski primary'nin yeniden standby yapılması; ardından `No Gap`, raporlanan sıfır
   apply/transport gecikmesi ve switchover/failover hazırlığının doğrulanması

Raporlar `target/cachedb-local-oracle-dataguard-reports/` altında oluşur.
`Oracle Data Guard Evidence` workflow'u da aynı komutu `oracle-dataguard`
etiketli Windows self-hosted runner üzerinde elle çalıştırabilir.

`-UseExistingTopology` yalnızca
`com.reactor.cachedb.owner=oracle-dg-evidence` etiketi taşıyan container ve
network ile çalışır. Plansız test primary container'ı zorla durdurduğu için
runner'ın herhangi bir Oracle container'ını hedeflemesine izin verilmez.

Bu akışta redo taşıma, redo uygulama ve rol değişimi gerçek fiziksel Data Guard
üzerinde gerçekleşir. Hikari testi, iki listener adresi ile tek sabit servis adı
içeren Oracle JDBC bağlantı tanımını kullanır; proxy olmadan eski bağlantının
reddedilmesini ve yeni bağlantının diğer adrese geçmesini doğrular. Provider
testlerinin tamamı ise gecikmeli ağ testine kontrol edilebilir tek upstream
sağlamak için, Broker yeni primary'yi doğruladıktan sonra ayrıca HAProxy kullanır.
Bu mekanizmaların hiçbiri Oracle Clusterware servis taşıma, FAN/ONS, FCF veya
Application Continuity kanıtı değildir.

| Sınıflandırma | Anlamı |
| --- | --- |
| BEST | Aynı kanıtı uygulamanın gerçek staging RAC veya Data Guard topolojisinde; production bağlantı tanımı, havuz, ağ yolu, zaman aşımı ve iş yüküyle yeniden çalıştır. |
| ACCEPTABLE | Gerçek staging kontrolünden önce framework düzeyinde yerel fiziksel Data Guard kanıtını kullan. |
| ANTI-PATTERN | İki single-instance Data Guard container'ı ve yerel bağlantı tanımından RAC, SCAN, FAN/ONS, Application Continuity veya production RTO/RPO sonucu çıkarmak. |

Oracle RAC container kurulumu; hazırlanmış bir Linux host, cluster ağı, storage
ve bu Docker Desktop hattından çok daha fazla bellek ister. Ayrıntılar için
[Oracle RAC container gereksinimlerine](https://github.com/oracle/docker-images/blob/main/OracleDatabase/RAC/OracleRealApplicationClusters/docs/developers/README.md)
bak. Fast Connection Failover için de Oracle HA olayları ve UCP gibi bir Oracle
bağlantı havuzu gerekir. Hikari reconnect kanıtı FCF değildir. Ayrıntılar:
[Oracle UCP Fast Connection Failover](https://docs.oracle.com/en/database/oracle/oracle-database/21/jjucp/fast-connection-failover.html).

Eski primary üzerinde devam eden transaction'ın hata vermesi beklenir.
Toparlanma; eski bağlantının reddedilmesi, yeni bağlantının yeni primary'ye
ulaşması ve uygulamanın idempotent komutu yeniden denemesi demektir. JDBC'nin
eski transaction'ı fark ettirmeden sürdürmesi anlamına gelmez.

Yerel hat, asenkron redo taşıyan Data Guard `MaxPerformance` modunu kullanır.
İşaret kayıtlarının eşleşmesi ve koşu sonunda görülen `No Gap` durumu, yalnızca o
koşunun doğruluk kanıtıdır; sıfır RPO garantisi değildir. Canlı ortam RPO değeri,
seçilen koruma modu, taşıma politikası, ağ ve hata modeline göre belirlenmelidir.

## Canlı Ortam Kontrol Listesi

- [ ] Tek Oracle provider starter ve tek onaylı JDBC sürümü bulunuyor.
- [ ] Değiştirilebilen her entity, uygulama tarafından üretilen kimliğe ve sayısal sürüm kolonuna sahip.
- [ ] Boş metin policy'si açık ve API/SQL testleriyle doğrulandı.
- [ ] Route koşulları ve deterministik sıralama sonları uygun indekslere sahip.
- [ ] Warm/source sınırları, timeout, Redis bellek bütçesi ve coverage ölçüldü.
- [ ] Bütün pod'lardaki Hikari oturumları Oracle servis bütçesine sığıyor.
- [ ] Fiziksel Data Guard kanıt hattı, sürüm adayının aynı koduyla geçiyor.
- [ ] Failover sonrasında yedeklilik geri getirildi; eski primary yeniden standby yapıldı ve Broker boşluk raporlamıyor.
- [ ] Hikari reconnect veya UCP/FCF modeli açıkça seçildi; iki model aynı iddiada karıştırılmıyor.
- [ ] Outbox/checkpoint DDL'i migration ile yönetiliyor; harici yazıcılar kapsanıyor.
- [ ] Canlı ortam, gözden geçirilmiş Oracle migration'larını ve başlangıçta şema doğrulamasını kullanıyor.
- [ ] Gerçek staging ortamında servis taşıma, veri eşitliği, gecikmeli ağ, reconnect, canary, idempotent retry ve rollback kanıtlandı.
- [ ] PL/SQL/UDT/LOB gibi özel işlemler açık adapter'larda ve ayrı testlerde tutuluyor.

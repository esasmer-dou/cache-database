# Spring Boot Starter

Bu proje iki farklı şekilde kullanılabilir:

- `cachedb-examples` içindeki standalone/demo çalışma biçimi
- başka bir Java uygulamasına kütüphane olarak gömülü kullanım

Bu doküman ikinci yolu anlatır. Odak noktası, CacheDB'yi mevcut Spring Boot
uygulamasına eklemek ve yönetim panelini aynı uygulama portu üzerinden
yayınlamaktır.

Production'da hangi kullanım yüzeyinin seçileceği için ayrıca
[Production Reçeteleri](./production-recipes.md) dokümanına bak.
Geleneksel ORM kullanımına karşı daha üst seviyede konumlandırma için ayrıca [CacheDB Bir ORM Alternatifi Olarak](./orm-alternative.md) dokümanına bak.
Mevcut SQL veritabanı + ORM geçişleri için ayrıca [Geçiş Planlayıcı](./migration-planner.md) dokümanına bak.
Planlayıcı, seçilen akış için Redis çalışma setini dry-run modunda
hesaplayabilir veya staging ortamında gerçekten önceden ısıtabilir. Ayrıca
bağlı kaynak veritabanı şemasını inceleyip kök/çocuk tablo adaylarını çıkarabilir,
binding'e hazır kod iskeleti üretebilir ve canlıya geçmeden önce kaynak veritabanı sonucuyla CacheDB sonucunu yan yana karşılaştırabilir.
Açık beta repo hijyeni ve release hazırlığı için ayrıca [Açık Beta Hazırlık Durumu](./public-beta-readiness.md) ve [Release Checklist](./release-checklist.md) dokümanlarına bak.

## Önerilen Production Başlangıcı

Çoğu ekip için önerilen varsayılan yol şu:

1. `CacheDatabase` bean'ini Spring Boot otomatik oluştursun.
2. Üretilmiş registrar'lar entity'leri otomatik kaydetsin.
3. Uygulama kodunda `GeneratedCacheModule.using(session)...` ile başla.
4. Yalnızca ölçümle kanıtlanmış darboğazları `*CacheBinding.using(session)...` veya doğrudan repository seviyesine indir.

Bu yol, başlangıç maliyetini düşük tutar ve CacheDB'nin birinci önceliği olan
düşük çalışma zamanı ek yükü hedefinden ödün vermez.

## En Hızlı Giriş Yolları

### Düz Java, en az seremoni

```java
JedisPooled jedis = new JedisPooled("redis://127.0.0.1:6379");
DataSource dataSource = ...;

try (CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, dataSource)
        .production()
        .keyPrefix("app-cache")
        .register(com.reactor.cachedb.examples.entity.GeneratedCacheBindings::register)
        .start()) {
    // repository kullanımı burada
}
```

Üretilen binding sınıfları artık daha düşük ceremony ile kullanılabilir:

```java
UserEntityCacheBinding.register(cacheDatabase);
OrderEntityCacheBinding.register(cacheDatabase);

EntityRepository<UserEntity, Long> users = UserEntityCacheBinding.repository(cacheDatabase);
List<UserEntity> açtiveUsers = users.query(
        QueryFilter.eq("status", "ACTIVE"),
        50,
        QuerySort.asc("username")
);
```

Relation ve page loader tanımları artık doğrudan entity üzerinden verilebilir:

```java
@CacheEntity(
        table = "cachedb_example_users",
        redisNamespace = "users",
        relationLoader = UserOrdersRelationBatchLoader.class,
        pageLoader = UserPageLoader.class
)
public class UserEntity {
}
```

Bu tanımdan sonra generated binding loader'ları kendi kurar:

```java
UserEntityCacheBinding.register(cacheDatabase);
```

Böylece çoğu uygulama kodunda `new UserOrdersRelationBatchLoader()` veya `new UserPageLoader()` seremonisi ortadan kalkar. `DemoOrderLinesRelationBatchLoader(EntityRepository<DemoOrderLineEntity, Long>)` gibi repository bağımlı loader'lar da generated binding tarafından constructor injection ile kurulabilir.

Processor artık package seviyesinde toplu registrar da üretir:

```java
CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, dataSource)
        .production()
        .keyPrefix("app-cache")
        .register(com.reactor.cachedb.examples.entity.GeneratedCacheBindings::register)
        .start();
```

Bu da çoğu uygulamada `ExampleBindings` benzeri elle yazılmış toplayıcı register sınıflarına olan ihtiyacı ortadan kaldırır.

Bu yol şu durumlarda uygundur:

- küçük bir API yüzeyi istiyorsan
- iyi varsayılanlarla hızlı başlamak istiyorsan
- sonra gerekirse tam config builder yoluna inmek istiyorsan

### Spring Boot, en az seremoni

```yaml
spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/app
    username: app
    password: app

cachedb:
  enabled: true
  profile: production
  redis:
    uri: redis://127.0.0.1:6379
```

Bu ayarlarla şunlar hazır gelir:

- `CacheDatabase` bean'i oluşur
- foreground ve background Redis havuzları ayrılır
- yönetim paneli aynı porttan yayınlanır
- production odaklı write-behind ve guardrail varsayılanları gelir
- worker consumer adları için otomatik runtime `instanceId` çözülür
- cleanup/report/history benzeri tekil döngüler için Redis leader lease devreye girer

### Çok Pod'lu Kubernetes Varsayılanları

Spring Boot starter artık tipik çok pod senaryosunu varsayılan olarak daha güvenli kurar:

- write-behind, DLQ replay, projection refresh ve incident-delivery DLQ worker'ları ortak consumer group kullanmaya devam eder
- consumer name'lere otomatik olarak pod-unique `instanceId` soneki eklenir
- cleanup/report/history benzeri singleton döngüler Redis leader lease ile tek pod'da aktif tutulur

Varsayılan `instanceId` çözme sırası:

1. `cachedb.runtime.instance-id`
2. `CACHE_DB_INSTANCE_ID`
3. `HOSTNAME`
4. `POD_NAME`
5. `COMPUTERNAME`
6. üretilen UUID

Önerilen Kubernetes başlangıcı:

```yaml
cachedb:
  enabled: true
  profile: production
  redis:
    uri: redis://redis:6379
    background:
      enabled: true
  runtime:
    append-instance-id-to-consumer-names: true
    leader-lease-enabled: true
    leader-lease-segment: coordination:leader
```

`cachedb.runtime.instance-id` değerini ancak uygulama seviyesinde açık bir kimlik istediğin durumda ver. Kubernetes'te çoğu zaman varsayılan hostname/pod-name çözümü doğru seçimdir.

Aynı host üzerinde local multi-instance smoke koşarken bunun tersini yap: her
process için açık `cachedb.runtime.instance-id` ver ya da
[run-multi-instance-coordination-smoke.ps1](../../tools/ops/cluster/run-multi-instance-coordination-smoke.ps1)
script'ini kullan. Tek workstation genelde tek `HOSTNAME` paylaştığı için,
sadece hostname çözümüyle ilerlemek gerçek pod davranışında görmeyeceğin
consumer kimliği sorunlarını saklayabilir.

## Kullanım Modları

### 1. Düz Java kütüphanesi

Bu yol, `CacheDatabase` nesnesini kendin bootstrap etmek istediğinde uygundur.

Minimum parçalar:

- `cachedb-annotations`
- `cachedb-processor`
- `cachedb-starter`
- kendi `DataSource` tanımın
- kendi `JedisPooled` tanımın

### 2. Spring Boot starter

Bu yol, Spring Boot'un şu işleri üstlenmesini istediğinde uygundur:

- `CacheDatabase` bean'ini oluşturmak
- `JedisPooled` bean'ini config'ten kurmak
- mevcut Spring `DataSource` bean'ini kullanmak
- CacheDB yönetim panelini Spring Boot web sunucusunun aynı portundan yayınlamak
- yönetim paneli sayfasını Thymeleaf ile render etmek

Yönetim paneli varsayılan olarak şu taban yolundan açılır:

- `/cachedb-admin`
- `/cachedb-admin/migration-planner`

Yani ikinci bir public admin portu açılmaz. Uygulamanın host ve port'u neyse
yönetim paneli de aynı porttan servis edilir.

## Minimum Bağımlılıklar

### Hangi DataSource Bağımlılığını Eklemeliyim?

CacheDB Spring Boot starter, Spring JDBC veya JPA auto-configuration yerine
geçmez. Uygulamanın zaten sahip olduğu `DataSource` bean'ini kullanır.

| Uygulamada zaten ne var? | `spring-boot-starter-jdbc` eklenmeli mi? | Neden |
| --- | --- | --- |
| `spring-boot-starter-data-jpa` | Hayır | JPA zaten Spring `DataSource` yolunu oluşturur |
| `spring-boot-starter-jdbc` | Hayır | Gerekli `DataSource` yolu zaten vardır |
| Elle tanımlanmış `DataSource` bean'i | Hayır | CacheDB bu bean'i kullanabilir |
| JDBC/JPA/DataSource yok | Evet | Spring'in `DataSource` oluşturması için JDBC yolu gerekir |

Gerekli sözleşme basittir: CacheDB autoconfiguration çalıştığında uygulamada
kullanılabilir tek bir Spring `DataSource` olmalı ya da hangi `DataSource`'un
kullanılacağı açıkça seçilmelidir.

### Düz Java

```xml
<dependencies>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-annotations</artifactId>
        <version>${cachedb.version}</version>
    </dependency>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-starter</artifactId>
        <version>${cachedb.version}</version>
    </dependency>
    <dependency>
        <groupId>redis.clients</groupId>
        <artifactId>jedis</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>com.reactor.cachedb</groupId>
                        <artifactId>cachedb-processor</artifactId>
                        <version>${cachedb.version}</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### Spring Boot

```xml
<dependencies>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-annotations</artifactId>
        <version>${cachedb.version}</version>
    </dependency>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-spring-boot-starter</artifactId>
        <version>${cachedb.version}</version>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>com.reactor.cachedb</groupId>
                        <artifactId>cachedb-processor</artifactId>
                        <version>${cachedb.version}</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

Notlar:

- `cachedb-spring-boot-starter`, JDBC starter yerine geçmez.
- Spring tarafında yine bir `DataSource` gerekir.
- Dependency örnekleri PostgreSQL'i gösterir; çünkü varsayılan provider
  PostgreSQL'dir. MSSQL seçersen `cachedb-storage-mssql`, Microsoft SQL Server
  JDBC driver'ı ve açık `MssqlWriteBehindFlusher` wiring'i gerekir.
- `JedisPooled` bean'i yoksa starter bunu `cachedb.redis.uri` değerinden oluşturur.
- Eski `cachedb.redis-uri` alias'i geriye uyumluluk için çalışmaya devam eder.
- `cachedb.profile` şu değerleri kabul eder: `default`, `development`, `production`, `benchmark`, `memory-constrained`, `minimal-overhead`.
- üretilmiş package registrar'ları `ServiceLoader` ile otomatik keşfedilir; normal Spring Boot yolunda entity binding'leri için elle `register(...)` çağrısı gerekmez
- tamamen manuel binding registration istiyorsan `cachedb.registration.enabled=false` kullanabilirsin
- `cachedb.runtime.append-instance-id-to-consumer-names=true` çok pod'lu güvenli varsayılandır; consumer group'ları ortak bırakır ama consumer adlarını pod-unique yapar
- `cachedb.runtime.leader-lease-enabled=true` cleanup/report/history döngülerini Redis leader lease altına alır; böylece her pod aynı singleton işi koşmaz

## İlk Çalışan Düz Java Örneği

```java
JedisPooled jedis = new JedisPooled("redis://127.0.0.1:6379");
DataSource dataSource = ...;

CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, dataSource)
        .development()
        .keyPrefix("app-cache")
        .register(com.reactor.cachedb.examples.entity.GeneratedCacheBindings::register)
        .start();
```

Bu kurulum şu katmanları açmış olur:

- Redis merkezli repository/session çalışma zamanı
- write-behind worker'lar
- istenirse standalone yönetim paneli

Aşağıdaki durumlarda tam `CacheDatabaseConfig.builder()` yoluna inmek mantıklıdır:

- schema bootstrap davranışını değiştireceksen
- write-behind detaylarını el ile ayarlayacaksan
- guardrail ayarlarını özel yapacaksan
- page cache veya projection refresh davranışını ince ayarlayacaksan

## ORM Benzeri Query ve Fetch Ergonomisi

Public read API artık `QuerySpec.builder()` zorunluluğunu azaltan daha doğal bir akış sunuyor:

```java
List<OrderEntity> orders = OrderEntityCacheBinding.repository(cacheDatabase)
        .withRelationLimit("orderLines", 8)
        .query(
                QuerySpec.where(QueryFilter.eq("customer_id", customerId))
                        .orderBy(QuerySort.desc("total_amount"), QuerySort.desc("line_item_count"))
                        .limitTo(24)
        );
```

Bu türdeki temel kolaylıklar:

- üretilmiş `*CacheBinding` sınıfları varsayılan olarak kaydedilebiliyor
- üretilmiş `*CacheBinding.repository(session)` artık açık bir cache policy istemiyor
- `EntityRepository` ve `ProjectionRepository` için kısa `query(...)` overload'ları var
- `QuerySpec.where(...).orderBy(...).limitTo(...).fetching(...)` immutable fluent akış sunuyor
- entity içindeki `@CacheNamedQuery` işaretli statik metodlar `UserEntityCacheBinding.activeUsers(...)` gibi helper'lar üretir
- entity içindeki `@CacheFetchPreset` işaretli statik metodlar `DemoOrderEntityCacheBinding.orderLinesPreviewRepository(...)` gibi helper'lar üretir
- entity içindeki `@CachePagePreset` işaretli statik metodlar `UserEntityCacheBinding.usersPage(...)` gibi helper'lar üretir
- entity içindeki `@CacheSaveCommand` ve `@CacheDeleteCommand` işaretli statik metodlar `UserEntityCacheBinding.activateUser(...)` ve `UserEntityCacheBinding.deleteUser(...)` gibi helper'lar üretir

Örnek:

```java
@CacheEntity(table = "cachedb_example_users", redisNamespace = "users")
public class UserEntity {

    @CacheNamedQuery("activeUsers")
    public static QuerySpec açtiveUsersQuery(int limit) {
        return QuerySpec.where(QueryFilter.eq("status", "ACTIVE"))
                .orderBy(QuerySort.asc("username"))
                .limitTo(limit);
    }

    @CacheFetchPreset("ordersPreview")
    public static FetchPlan ordersPreviewFetchPlan(int relationLimit) {
        return FetchPlan.of("orders").withRelationLimit("orders", relationLimit);
    }
}

List<UserEntity> açtiveUsers = UserEntityCacheBinding.activeUsers(session, 25);
EntityRepository<UserEntity, Long> previewRepository =
        UserEntityCacheBinding.ordersPreviewRepository(session, 8);
List<UserEntity> users = UserEntityCacheBinding.usersPage(session, 0, 25);
UserEntity açtivated = UserEntityCacheBinding.activateUser(session, 41L, "alice");
UserEntityCacheBinding.deleteUser(session, 41L);

var userOps = UserEntityCacheBinding.using(session);
List<UserEntity> groupedUsers = userOps.queries().activeUsers(25);
EntityRepository<UserEntity, Long> groupedPreviewRepository = userOps.fetches().ordersPreview(8);
List<UserEntity> groupedPage = userOps.pages().usersPage(0, 25);
UserEntity groupedActivated = userOps.commands().activateUser(41L, "alice");
userOps.deletes().deleteUser(41L);

var domain = com.reactor.cachedb.examples.entity.GeneratedCacheModule.using(session);
List<UserEntity> domainUsers = domain.users().queries().activeUsers(25);
```

İlişki yükü ağır ekranlarda önerilen desen:

- önce özet sorgu
- sonra açık detay okuması
- tam nesne grafiği yerine gerekirse `withRelationLimit(...)` ile ön izleme

## Minimal Overhead Modu

CacheDB'yi gömülü kütüphane gibi kullanıyor ve yönetim paneli ya da yönetim
telemetrisine ihtiyacın yoksa, açık bir minimal-overhead profili kullanman daha
doğru olur.

Düz Java:

```java
CacheDatabaseConfig config = CacheDatabaseProfiles.minimalOverhead();

CacheDatabase cacheDatabase = new CacheDatabase(jedis, dataSource, config);
cacheDatabase.start();
```

Bu profil neleri kapatır:

- admin monitoring worker'ları
- monitoring history buffer'ları
- alert route history buffer'ları
- performance history buffer'ları
- incident delivery manager
- admin report worker
- standalone admin HTTP server

Çalışmaya devam edenler:

- Redis merkezli repository/session çalışma zamanı
- write-behind
- dead-letter recovery
- schema bootstrap/validation akışı

Semeru JDK 21 ile alınan odaklı benchmark sonucu:

- benchmark scripti: `tools/ops/benchmark/measure-admin-monitoring-overhead.ps1`
- `disabledThreadDelta=0`
- `enabledIncidentThreadDelta=1`
- `activeMinusNoopBytes=4259840`

Bu ölçüm tam uygulama benchmark'ı değildir. Yalnızca admin kapalıyken ek admin
thread açılmadığını ve aktif collector yolunun no-op yola göre daha fazla heap
tuttuğunu gösteren hedefli bir doğrulamadır.

## İlk Çalışan Spring Boot Örneği

```java
@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

`application.yml`:

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/app
    username: app
    password: app

cachedb:
  enabled: true
  profile: production
  redis:
    uri: redis://127.0.0.1:6379
  admin:
    enabled: true
    http-enabled: true
    base-path: /cachedb-admin
    dashboard-enabled: true
    title: Benim CacheDB Admin
```

Bu kurulumla:

- Spring Boot uygulaman aynı `server.port` üzerinde çalışmaya devam eder
- CacheDB yönetim paneli aynı porttan yayınlanır; çünkü `http-enabled` açıkça verilmiştir
- dış yönetim paneli URL'i şu olur:
  - `http://127.0.0.1:8080/cachedb-admin`

Üretim erişim kuralı:

- `/cachedb-admin/**` yollarını gateway veya operasyon ağı arkasında tut
- platform standardın buysa TLS'i gateway veya reverse proxy üzerinde sonlandır
- gateway authentication kullan veya CacheDB token auth için `cachedb.admin.auth-enabled=true` tanımla

## Üretim İçin Varsayılan Redis Topolojisi

Spring Boot starter artık ayrılmış Redis havuzlarını varsayılan üretim
reçetesi olarak kabul eder.

Varsayılan davranış:

- repository veri yolu `cachedb.redis.pool.*` havuzunu kullanır
- worker/admin/telemetry arka plan yolu `cachedb.redis.background.pool.*` havuzunu kullanır
- background Redis URI ayarlanmazsa foreground URI kullanılır

Varsayılan havuz boyutları:

- foreground: `maxTotal=64`, `maxIdle=16`, `minIdle=4`
- background: `maxTotal=24`, `maxIdle=8`, `minIdle=2`
- foreground timeout'ları: `connection=2000ms`, `read=5000ms`, `blockingRead=15000ms`
- background timeout'ları: `connection=2000ms`, `read=10000ms`, `blockingRead=30000ms`

Varsayılan konfigurasyon:

```yaml
cachedb:
  redis:
    uri: redis://127.0.0.1:6379
    pool:
      max-total: 64
      max-idle: 16
      min-idle: 4
    background:
      enabled: true
      uri: redis://127.0.0.1:6379
      pool:
        max-total: 24
        max-idle: 8
        min-idle: 2
```

Neden önemli:

- repository okumaları write-behind/recovery/admin trafiğiyle aynı havuzda birikmez
- karışık yük altında p95 okuma gecikmesi daha stabil olur
- foreground SLA ile background bakım trafiği daha temiz ayrışır
- worker stream read komutları foreground ile aynı kısa read timeout'u kullanmaz; bu da blocking Redis komutlarında sahte `SocketTimeoutException: Read timed out` gürültüsünü azaltır

Eski tek-pool davranışını istiyorsan:

```yaml
cachedb:
  redis:
    background:
      enabled: false
```

Eğer kendi foreground `JedisPooled` bean'ini veriyorsan ve ayrılmış havuz yapısı
da istiyorsan, ek olarak `cacheDbBackgroundJedisPooled` adlı bean'i de expose et.

## Spring Boot İçinde Minimal Overhead

Spring entegrasyonu kalsın ama yönetim paneli ve admin monitoring ek yükü
olmasın istiyorsan:

```yaml
cachedb:
  enabled: true
  redis:
    uri: redis://127.0.0.1:6379
  admin:
    enabled: false
```

Bu durumda:

- CacheDB runtime çalışmaya devam eder
- Spring Boot `/cachedb-admin/*` yollarını yayınlamaz
- `CacheDatabase` içinde admin monitoring kapatılır
- performance collection no-op collector yoluna geçer
- admin tarafı history ve delivery worker'ları başlamaz

## Starter Neleri Oluşturur

Eksikse starter şu bean ve bileşenleri oluşturur:

- `JedisPooled`
- `CacheDatabaseConfig`
- `CacheDatabase`
- `cachedb.admin.http-enabled=true` ise Spring Boot servlet konteyneri içinde aynı porttan çalışan native CacheDB admin API servlet'i
- `cachedb.admin.http-enabled=true` ise aynı base path altında Thymeleaf ile render edilen yönetim paneli sayfası

Bu tasarım sayesinde yönetim paneli aynı porttan gelir ve Spring Boot modunda
ikinci bir dahili HTTP sunucusu açılmaz.

## Yönetim Panelinin Spring Boot Portundan Yayınlanması

Davranış:

- dış kullanıcı Boot uygulamasının base path'i altından yönetim paneline erişir
- yönetim paneli içindeki API çağrıları da bu base path'e göre çalışır
- admin yolları Spring Boot servlet konteyneri içinde doğrudan dispatch edilir
- base path'in kökü ve `/dashboard` sayfası Thymeleaf ile render edilir
- `/dashboard-v3` eski bookmark'lar için legacy redirect olarak kalır
- `/api/*` aynı portta native admin servlet tarafından servis edilir

Admin HTTP açıkça etkinleştirildiğinde dış URL'ler:

- yönetim paneli: `/cachedb-admin`
- health JSON: `/cachedb-admin/api/health`
- metrics JSON: `/cachedb-admin/api/metrics`

## Spring Boot İçinde CacheDatabaseConfig Özelleştirme

Bir bean ekleyebilirsin:

```java
@Bean
CacheDatabaseConfigCustomizer cacheDatabaseConfigCustomizer() {
    return (builder, properties) -> builder
            .relations(RelationConfig.builder()
                    .batchSize(1000)
                    .maxFetchDepth(2)
                    .failOnMissingPreloader(true)
                    .build())
            .writeBehind(WriteBehindConfig.builder()
                    .workerThreads(4)
                    .batchSize(250)
                    .build());
}
```

Bu yol, Boot autoconfiguration'ı korurken config'i ihtiyaca göre
sertleştirmek için kullanılır.

Projection refresh örneği:

```java
@Bean
CacheDatabaseConfigCustomizer cacheDatabaseProjectionCustomizer() {
    return (builder, properties) -> builder
            .projectionRefresh(ProjectionRefreshConfig.builder()
                    .enabled(true)
                    .streamKey("cachedb:stream:projection-refresh")
                    .consumerGroup("cachedb-projection-refresh")
                    .batchSize(250)
                    .claimIdleMillis(45_000)
                    .build());
}
```

Bu yol, durable Redis Stream tabanlı projection refresh davranışını sadece `-Dcachedb.config.projectionRefresh.*` flag'lerine bağlı kalmadan Spring Boot içinden kurmak istediğinde kullanılır.

Operasyonel yüzeyler:

- `GET /cachedb-admin/api/projection-refresh`
- `GET /cachedb-admin/api/projection-refresh/failed?limit=20`
- `POST /cachedb-admin/api/projection-refresh/replay?entryId=<dead-letter-entry-id>`

Birlikte gelen araçlar:

- [list-projection-refresh-failures.ps1](../../tools/ops/projection/list-projection-refresh-failures.ps1)
- [replay-projection-refresh-failure.ps1](../../tools/ops/projection/replay-projection-refresh-failure.ps1)

## Production Read Deseni

İlişki yükü yüksek ekranlarda büyük eager graph yüklemek yerine
`özet sorgu + açık detay okuması` desenini tercih et.

Doğru desen:

1. siparişleri `orderLines` yüklemeden özet olarak sorgula
2. listeyi özet alanlarla render et
3. detay gerekince ayrıca yükle
4. preload istiyorsan bile `FetchPlan.withRelationLimit(...)` ile ilişkiyi sınırla

Örnek:

```java
ProjectionRepository<DemoOrderReadModelPatterns.OrderSummaryReadModel, Long> summaryRepository =
        DemoOrderEntityCacheBinding.orderSummary(orderRepository);

List<DemoOrderReadModelPatterns.OrderSummaryReadModel> summaries =
        readPatterns.findCustomerOrderSummaries(customerId, 24);

DemoOrderReadModelPatterns.OrderDetailReadModel detail =
        readPatterns.loadOrderDetail(orderId, 12);

List<DemoOrderReadModelPatterns.OrderLinePreviewReadModel> nextPage =
        readPatterns.loadRemainingOrderLines(orderId, 12, 50);
```

Sınırlı preload örneği:

```java
DemoOrderEntity order = orderRepository
        .withFetchPlan(FetchPlan.of("orderLines").withRelationLimit("orderLines", 8))
        .findById(orderId)
        .orElseThrow();
```

Projection repository örneği:

```java
ProjectionRepository<DemoOrderReadModelPatterns.OrderLinePreviewReadModel, Long> linePreviewRepository =
        DemoOrderLineEntityCacheBinding.orderLinePreview(orderLineRepository);
```

Projection'a özel index ve refresh:

- her projection kendi Redis namespace'i ve query index seti ile çalışır
- projection cache ısındığında okuma tarafı temel entity payload'ını decode etmek zorunda kalmaz
- `EntityProjection.asyncRefresh()` projection bakımını foreground write path dışına iter
- async refresh artık Redis Stream tabanlı durable worker ile çalışır
- refresh event'leri process restart sonrasında kaybolmaz; Redis consumer group üzerinden birden fazla uygulama node'u tarafından işlenebilir
- model tasarım gereği hala eventual consistency tabanlıdır
- ama henüz poison queue, replay tooling veya ayrık admin telemetrisi olan tam bir projection platformu değildir
- entity içindeki statik bir metoda `@CacheProjectionDefinition` koyarsan generated binding tarafında `DemoOrderEntityCacheBinding.orderSummary(...)` gibi projection helper'ları oluşur
- entity içindeki statik bir metoda `@CacheNamedQuery` koyarsan aynı generated binding hem entity repository hem projection repository için query helper üretir
- entity içindeki statik bir metoda `@CacheFetchPreset` koyarsan generated binding `withFetchPlan(...)` glue kodu yazmadan preview/detail repository helper'ları üretir
- entity içindeki statik bir metoda `@CachePagePreset` koyarsan generated binding `new PageWindow(...)` glue kodu yazmadan tekrar kullanılabilir page/window helper'ları üretir
- entity içindeki statik bir metoda `@CacheSaveCommand` veya `@CacheDeleteCommand` koyarsan generated binding derleme zamanında write command helper'ları üretir

Örnek:

```java
public static final EntityProjection<DemoOrderEntity, OrderSummaryReadModel, Long> ORDER_SUMMARY_PROJECTION =
        EntityProjection.of(
                "order-summary",
                codec,
                OrderSummaryReadModel::id,
                List.of("id", "customer_id", "status", "line_item_count", "total_amount"),
                projection -> Map.of(
                        "id", projection.id(),
                        "customer_id", projection.customerId(),
                        "status", projection.status(),
                        "line_item_count", projection.lineItemCount(),
                        "total_amount", projection.totalAmount()
                ),
                order -> new OrderSummaryReadModel(...)
        ).asyncRefresh();
```

Referans örnek:

- [DemoOrderReadModelPatterns.java](../../cachedb-examples/src/main/java/com/cachedb/examples/demo/DemoOrderReadModelPatterns.java)

Production tarafında neden önemli:

- Redis key/value okuma hızlı olsa bile ilişki yükü yüksek sorgu yine aday filtreleme, decode, sort ve nesne grafiği oluşturma maliyeti öder
- pahalı kısım genellikle tek bir `GET` değil, ne kadar büyük veri grafiği yüklediğindir
- daha küçük özet sorguları p95'i gerçek sıcak repository yoluna daha yakın tutar

Projection refresh tuning ayarları `cachedb.config.projectionRefresh.*` altındadır.

En kritik varsayılanlar:

- `enabled=true`
- `streamKey=cachedb:stream:projection-refresh`
- `consumerGroup=cachedb-projection-refresh`
- `batchSize=100`
- `recoverPendingEntries=true`
- `claimIdleMillis=30000`

Tam tablo için:

- [tuning-parameters.md](./tuning-parameters.md)

## Önerilen Sonraki Adım

Starter'ı bağladıktan sonra tipik sonraki adımlar şunlardır:

- entity ve relation loader'larını kaydetmek
- pahalı liste akışları için page loader tanımlamak
- admin explain ekranı ile fetch planlarını doğrulamak
- `/cachedb-admin` üzerinden yönetim paneli erişimini kontrol etmek

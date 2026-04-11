# Spring Boot Starter

Bu proje iki farkli sekilde kullanilabilir:

- `cachedb-examples` icindeki standalone/demo calisma bicimi
- baska bir Java uygulamasina kutuphane olarak gomulu kullanim

Bu dokuman ikinci yolu anlatir. Odak noktasi Spring Boot ve admin UI'in ayni port uzerinden yayinlanmasidir.

Production surface secimi ve karar rehberi icin ayrica [Production Recipes](./production-recipes.md) dokumanina bak.
Geleneksel ORM kullanimina karsi daha ust seviyede konumlandirma icin ayrica [CacheDB Bir ORM Alternatifi Olarak](./orm-alternative.md) dokumanina bak.
Public beta repo hijyeni ve release hazirligi icin ayrica [Public Beta Readiness](./public-beta-readiness.md) ve [Release Checklist](./release-checklist.md) dokumanlarina bak.

## Onerilen Production Baslangici

Cogu ekip icin onerilen varsayilan yol su:

1. `CacheDatabase` bean'ini Spring Boot otomatik olustursun
2. generated registrar'lar entity'leri otomatik register etsin
3. uygulama kodunda `GeneratedCacheModule.using(session)...` ile basla
4. sadece olculmus hotspot'lari `*CacheBinding.using(session)...` veya dogrudan repository tarafina indir

Bu yol hem en kolay baslangici verir hem de projenin birinci onceligi olan dusuk runtime overhead hedefinden odun vermez.

## En Hizli Giris Yollari

### Duz Java, en az seremoni

```java
JedisPooled jedis = new JedisPooled("redis://127.0.0.1:6379");
DataSource dataSource = ...;

try (CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, dataSource)
        .production()
        .keyPrefix("app-cache")
        .register(com.reactor.cachedb.examples.entity.GeneratedCacheBindings::register)
        .start()) {
    // repository kullanimi burada
}
```

Uretilen binding siniflari artik daha dusuk ceremony ile kullanilabilir:

```java
UserEntityCacheBinding.register(cacheDatabase);
OrderEntityCacheBinding.register(cacheDatabase);

EntityRepository<UserEntity, Long> users = UserEntityCacheBinding.repository(cacheDatabase);
List<UserEntity> activeUsers = users.query(
        QueryFilter.eq("status", "ACTIVE"),
        50,
        QuerySort.asc("username")
);
```

Relation ve page loader tanimlari artik dogrudan entity uzerinden verilebilir:

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

Bu tanimdan sonra generated binding loader'lari kendi kurar:

```java
UserEntityCacheBinding.register(cacheDatabase);
```

Boylece cogu uygulama kodunda `new UserOrdersRelationBatchLoader()` veya `new UserPageLoader()` seremonisi ortadan kalkar. `DemoOrderLinesRelationBatchLoader(EntityRepository<DemoOrderLineEntity, Long>)` gibi repository bagimli loader'lar da generated binding tarafindan constructor injection ile kurulabilir.

Processor artik package seviyesinde toplu registrar da uretir:

```java
CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, dataSource)
        .production()
        .keyPrefix("app-cache")
        .register(com.reactor.cachedb.examples.entity.GeneratedCacheBindings::register)
        .start();
```

Bu da cogu uygulamada `ExampleBindings` benzeri elle yazilmis toplayici register siniflarina ihtiyaci ortadan kaldirir.

Bu yol su durumlarda uygundur:

- kucuk bir API yuzeyi istiyorsan
- iyi varsayilanlarla hizli baslamak istiyorsan
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

Bu kadar ayarla:

- `CacheDatabase` bean olusur
- split foreground/background Redis pool devreye girer
- admin UI ayni porttan yayinlanir
- production odakli write-behind ve guardrail varsayilanlari gelir
- worker consumer adlari icin otomatik runtime `instanceId` cozulur
- cleanup/report/history benzeri tekil loop'lar icin Redis leader lease devreye girer

### Cok Pod'lu Kubernetes Varsayilanlari

Spring Boot starter artik tipik cok pod senaryosunu varsayilan olarak daha guvenli kurar:

- write-behind, DLQ replay, projection refresh ve incident-delivery DLQ worker'lari ortak consumer group kullanmaya devam eder
- consumer name'lere otomatik olarak pod-unique `instanceId` soneki eklenir
- cleanup/report/history benzeri singleton loop'lar Redis leader lease ile tek pod'da aktif tutulur

Varsayilan `instanceId` cozme sirasi:

1. `cachedb.runtime.instance-id`
2. `CACHE_DB_INSTANCE_ID`
3. `HOSTNAME`
4. `POD_NAME`
5. `COMPUTERNAME`
6. uretilen UUID

Onerilen Kubernetes baslangici:

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

`cachedb.runtime.instance-id` degerini ancak uygulama seviyesinde acik bir kimlik istedigin durumda ver. Kubernetes'te cogu zaman varsayilan hostname/pod-name cozumu dogru secimdir.

Ayni host uzerinde local multi-instance smoke kosarken ise bunun tersini yap: her process icin acik `cachedb.runtime.instance-id` ver ya da [../tools/ops/cluster/run-multi-instance-coordination-smoke.ps1](../tools/ops/cluster/run-multi-instance-coordination-smoke.ps1) script'ini kullan. Tek workstation genelde tek `HOSTNAME` paylastigi icin, sadece hostname cozumu ile gitmek gercek pod davranisinda gormeyecegin consumer kimligi sorunlarini saklayabilir.

## Kullanim Modlari

### 1. Duz Java kutuphanesi

Bu yol, `CacheDatabase` nesnesini kendin bootstrap etmek istediginde uygundur.

Minimum parcalar:

- `cachedb-annotations`
- `cachedb-processor`
- `cachedb-starter`
- kendi `DataSource` tanimin
- kendi `JedisPooled` tanimin

### 2. Spring Boot starter

Bu yol, Spring Boot'un su isleri ustlenmesini istediginde uygundur:

- `CacheDatabase` bean'ini olusturmak
- `JedisPooled` bean'ini config'ten kurmak
- mevcut Spring `DataSource` bean'ini kullanmak
- CacheDB admin UI'ini Spring Boot web sunucusunun ayni portundan yayinlamak
- admin dashboard sayfasini Thymeleaf ile render etmek

Admin UI varsayilan olarak su taban yolundan acilir:

- `/cachedb-admin`

Yani ikinci bir public admin portu acilmaz. Uygulamanin host ve port'u neyse admin UI da ayni porttan servis edilir.

## Minimum Bagimliliklar

### Duz Java

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

- `cachedb-spring-boot-starter`, JDBC starter yerine gecmez.
- Spring tarafinda yine bir `DataSource` gerekir.
- `JedisPooled` bean'i yoksa starter bunu `cachedb.redis.uri` degerinden olusturur.
- Eski `cachedb.redis-uri` alias'i geriye uyumluluk icin calismaya devam eder.
- `cachedb.profile` su degerleri kabul eder: `default`, `development`, `production`, `benchmark`, `memory-constrained`, `minimal-overhead`.
- generated package registrar'lari `ServiceLoader` ile otomatik kesfedilir; normal Spring Boot yolunda entity binding'leri icin elle `register(...)` cagrisi gerekmez
- tamamen manuel binding registration istiyorsan `cachedb.registration.enabled=false` kullanabilirsin
- `cachedb.runtime.append-instance-id-to-consumer-names=true` cok pod'lu guvenli varsayilandir; consumer group'lari ortak birakir ama consumer adlarini pod-unique yapar
- `cachedb.runtime.leader-lease-enabled=true` cleanup/report/history loop'larini Redis leader lease altina alir; boylece her pod ayni singleton isi kosmaz

## Ilk Calisan Duz Java Ornegi

```java
JedisPooled jedis = new JedisPooled("redis://127.0.0.1:6379");
DataSource dataSource = ...;

CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, dataSource)
        .development()
        .keyPrefix("app-cache")
        .register(com.reactor.cachedb.examples.entity.GeneratedCacheBindings::register)
        .start();
```

Bu kurulum su katmanlari acmis olur:

- Redis-first repository/session runtime
- write-behind worker'lar
- istenirse standalone admin UI

Asagidaki durumlarda tam `CacheDatabaseConfig.builder()` yoluna inmek mantiklidir:

- schema bootstrap davranisini degistireceksen
- write-behind detaylarini el ile ayarlayacaksan
- guardrail ayarlarini ozel yapacaksan
- page cache veya projection refresh davranisini ince ayarlayacaksan

## ORM Benzeri Query ve Fetch Ergonomisi

Public read API artik `QuerySpec.builder()` zorunlulugunu azaltan daha dogal bir akis sunuyor:

```java
List<OrderEntity> orders = OrderEntityCacheBinding.repository(cacheDatabase)
        .withRelationLimit("orderLines", 8)
        .query(
                QuerySpec.where(QueryFilter.eq("customer_id", customerId))
                        .orderBy(QuerySort.desc("total_amount"), QuerySort.desc("line_item_count"))
                        .limitTo(24)
        );
```

Bu turdaki temel kolayliklar:

- generated `*CacheBinding` siniflari default register edilebiliyor
- generated `*CacheBinding.repository(session)` artik explicit cache policy istemiyor
- `EntityRepository` ve `ProjectionRepository` icin kisa `query(...)` overload'lari var
- `QuerySpec.where(...).orderBy(...).limitTo(...).fetching(...)` immutable fluent akis sunuyor
- entity icindeki `@CacheNamedQuery` isaretli statik metodlar `UserEntityCacheBinding.activeUsers(...)` gibi helper'lar uretir
- entity icindeki `@CacheFetchPreset` isaretli statik metodlar `DemoOrderEntityCacheBinding.orderLinesPreviewRepository(...)` gibi helper'lar uretir
- entity icindeki `@CachePagePreset` isaretli statik metodlar `UserEntityCacheBinding.usersPage(...)` gibi helper'lar uretir
- entity icindeki `@CacheSaveCommand` ve `@CacheDeleteCommand` isaretli statik metodlar `UserEntityCacheBinding.activateUser(...)` ve `UserEntityCacheBinding.deleteUser(...)` gibi helper'lar uretir

Ornek:

```java
@CacheEntity(table = "cachedb_example_users", redisNamespace = "users")
public class UserEntity {

    @CacheNamedQuery("activeUsers")
    public static QuerySpec activeUsersQuery(int limit) {
        return QuerySpec.where(QueryFilter.eq("status", "ACTIVE"))
                .orderBy(QuerySort.asc("username"))
                .limitTo(limit);
    }

    @CacheFetchPreset("ordersPreview")
    public static FetchPlan ordersPreviewFetchPlan(int relationLimit) {
        return FetchPlan.of("orders").withRelationLimit("orders", relationLimit);
    }
}

List<UserEntity> activeUsers = UserEntityCacheBinding.activeUsers(session, 25);
EntityRepository<UserEntity, Long> previewRepository =
        UserEntityCacheBinding.ordersPreviewRepository(session, 8);
List<UserEntity> users = UserEntityCacheBinding.usersPage(session, 0, 25);
UserEntity activated = UserEntityCacheBinding.activateUser(session, 41L, "alice");
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

Relation agir ekranlarda onerilen desen:

- once summary query
- sonra explicit detail fetch
- tam object graph yerine gerekirse `withRelationLimit(...)` ile preview

## Minimal Overhead Modu

CacheDB'yi gomulu kutuphane gibi kullaniyor ve admin UI ya da admin telemetrisine ihtiyacin yoksa, acik bir minimal-overhead profili kullanman daha dogru olur.

Duz Java:

```java
CacheDatabaseConfig config = CacheDatabaseProfiles.minimalOverhead();

CacheDatabase cacheDatabase = new CacheDatabase(jedis, dataSource, config);
cacheDatabase.start();
```

Bu profil neleri kapatir:

- admin monitoring worker'lari
- monitoring history buffer'lari
- alert route history buffer'lari
- performance history buffer'lari
- incident delivery manager
- admin report worker
- standalone admin HTTP server

Neler calismaya devam eder:

- Redis-first repository/session runtime
- write-behind
- dead-letter recovery
- schema bootstrap/validation akisi

Semeru JDK 21 ile aldigimiz odakli benchmark sonucu:

- benchmark scripti: `tools/ops/benchmark/measure-admin-monitoring-overhead.ps1`
- `disabledThreadDelta=0`
- `enabledIncidentThreadDelta=1`
- `activeMinusNoopBytes=4259840`

Bu olcum tam uygulama benchmark'i degil; admin kapali modda ek admin thread acilmadigini ve aktif collector yolunun no-op yola gore daha fazla heap tuttugunu gosteren hedefli bir dogrulamadir.

## Ilk Calisan Spring Boot Ornegi

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
    base-path: /cachedb-admin
    dashboard-enabled: true
    title: Benim CacheDB Admin
```

Bu kurulumla:

- Spring Boot uygulaman ayni `server.port` uzerinde calismaya devam eder
- CacheDB admin UI ayni porttan yayinlanir
- public dashboard URL'i su olur:
  - `http://127.0.0.1:8080/cachedb-admin`

## Uretim Icin Varsayilan Redis Topolojisi

Spring Boot starter artik split Redis pool yapisini varsayilan uretim recetesi olarak kabul eder.

Varsayilan davranis:

- repository veri yolu `cachedb.redis.pool.*` havuzunu kullanir
- worker/admin/telemetry arka plan yolu `cachedb.redis.background.pool.*` havuzunu kullanir
- background Redis URI ayarlanmazsa foreground URI kullanilir

Varsayilan pool boyutlari:

- foreground: `maxTotal=64`, `maxIdle=16`, `minIdle=4`
- background: `maxTotal=24`, `maxIdle=8`, `minIdle=2`
- foreground timeout'lari: `connection=2000ms`, `read=5000ms`, `blockingRead=15000ms`
- background timeout'lari: `connection=2000ms`, `read=10000ms`, `blockingRead=30000ms`

Varsayilan konfigurasyon:

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

Neden onemli:

- repository okumalari write-behind/recovery/admin trafigiyle ayni havuzda birikmez
- karisik yuk altinda p95 okuma gecikmesi daha stabil olur
- foreground SLA ile background bakim trafigi daha temiz ayrisir
- worker stream read komutlari foreground ile ayni kisa read timeout'u kullanmaz; bu da blocking Redis komutlarinda sahte `SocketTimeoutException: Read timed out` gurultusunu azaltir

Eski tek-pool davranisini istiyorsan:

```yaml
cachedb:
  redis:
    background:
      enabled: false
```

Eger kendi foreground `JedisPooled` bean'ini veriyorsan ve split pool da istiyorsan, ek olarak `cacheDbBackgroundJedisPooled` adli bean'i de expose et.

## Spring Boot Icinde Minimal Overhead

Spring entegrasyonu kalsin ama admin UI ve admin monitoring overhead'i olmasin istiyorsan:

```yaml
cachedb:
  enabled: true
  redis:
    uri: redis://127.0.0.1:6379
  admin:
    enabled: false
```

Bu durumda:

- CacheDB runtime calismaya devam eder
- Spring Boot `/cachedb-admin/*` yollarini yayinlamaz
- `CacheDatabase` icinde admin monitoring kapatilir
- performance collection no-op collector yoluna gecer
- admin tarafi history ve delivery worker'lari baslamaz

## Starter Neleri Olusturur

Eksikse starter su bean ve bilesenleri olusturur:

- `JedisPooled`
- `CacheDatabaseConfig`
- `CacheDatabase`
- Spring Boot servlet konteyneri icinde ayni porttan calisan native CacheDB admin API servlet'i
- ayni base path altinda Thymeleaf ile render edilen dashboard sayfasi

Bu tasarim sayesinde admin UI ayni porttan gelir ve Spring Boot modunda ikinci bir dahili HTTP sunucusu acilmaz.

## Admin UI'in Spring Boot Portundan Yayinlanmasi

Davranis:

- dis kullanici Boot uygulamasinin base path'i altindan admin UI'a erisir
- dashboard icindeki API cagrilari da bu base path'e gore calisir
- admin route'lari Spring Boot servlet konteyneri icinde dogrudan dispatch edilir
- base path'in koku ve `/dashboard` sayfasi Thymeleaf ile render edilir
- `/dashboard-v3` eski bookmark'lar icin legacy redirect olarak kalir
- `/api/*` ayni portta native admin servlet tarafindan servis edilir

Varsayilan dis URL'ler:

- dashboard: `/cachedb-admin`
- health JSON: `/cachedb-admin/api/health`
- metrics JSON: `/cachedb-admin/api/metrics`

## Spring Boot Icinde CacheDatabaseConfig Ozellestirme

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

Bu yol, Boot autoconfiguration'i korurken config'i ihtiyaca gore sertlestirmek icin kullanilir.

Projection refresh ornegi:

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

Bu yol, durable Redis Stream tabanli projection refresh davranisini sadece `-Dcachedb.config.projectionRefresh.*` flag'lerine bagli kalmadan Spring Boot icinden kurmak istediginde kullanilir.

Operasyonel yuzeyler:

- `GET /cachedb-admin/api/projection-refresh`
- `GET /cachedb-admin/api/projection-refresh/failed?limit=20`
- `POST /cachedb-admin/api/projection-refresh/replay?entryId=<dead-letter-entry-id>`

Birlikte gelen araclar:

- [list-projection-refresh-failures.ps1](/E:/ReactorRepository/cache-database/tools/ops/projection/list-projection-refresh-failures.ps1)
- [replay-projection-refresh-failure.ps1](/E:/ReactorRepository/cache-database/tools/ops/projection/replay-projection-refresh-failure.ps1)

## Production Read Deseni

Relation-heavy ekranlarda buyuk eager graph yuklemek yerine `summary query + explicit detail fetch` desenini tercih et.

Dogru desen:

1. siparisleri `orderLines` yuklemeden ozet olarak sorgula
2. listeyi ozet alanlarla render et
3. detay gerekince ayrica yukle
4. preload istiyorsan bile `FetchPlan.withRelationLimit(...)` ile iliskiyi sinirla

Ornek:

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

Sinirli preload ornegi:

```java
DemoOrderEntity order = orderRepository
        .withFetchPlan(FetchPlan.of("orderLines").withRelationLimit("orderLines", 8))
        .findById(orderId)
        .orElseThrow();
```

Projection repository ornegi:

```java
ProjectionRepository<DemoOrderReadModelPatterns.OrderLinePreviewReadModel, Long> linePreviewRepository =
        DemoOrderLineEntityCacheBinding.orderLinePreview(orderLineRepository);
```

Projection-specific index ve refresh:

- her projection kendi Redis namespace'i ve query index seti ile calisir
- projection cache isindiginda okuma tarafi tam base entity payload decode etmek zorunda kalmaz
- `EntityProjection.asyncRefresh()` projection bakimini foreground write path disina iter
- async refresh artik Redis Stream tabanli durable worker ile calisir
- refresh event'leri process restart sonrasinda kaybolmaz; Redis consumer group uzerinden birden fazla uygulama node'u tarafindan islenebilir
- model tasarim geregi hala eventual consistency tabanlidir
- ama henuz poison queue, replay tooling veya ayrik admin telemetrisi olan tam bir projection platformu degildir
- entity icindeki statik bir metoda `@CacheProjectionDefinition` koyarsan generated binding tarafinda `DemoOrderEntityCacheBinding.orderSummary(...)` gibi projection helper'lari olusur
- entity icindeki statik bir metoda `@CacheNamedQuery` koyarsan ayni generated binding hem entity repository hem projection repository icin query helper uretir
- entity icindeki statik bir metoda `@CacheFetchPreset` koyarsan generated binding `withFetchPlan(...)` glue kodu yazmadan preview/detail repository helper'lari uretir
- entity icindeki statik bir metoda `@CachePagePreset` koyarsan generated binding `new PageWindow(...)` glue kodu yazmadan reuse edilebilir page/window helper'lari uretir
- entity icindeki statik bir metoda `@CacheSaveCommand` veya `@CacheDeleteCommand` koyarsan generated binding compile-time write command helper'lari uretir

Ornek:

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

Referans ornek:

- [DemoOrderReadModelPatterns.java](../../cachedb-examples/src/main/java/com/cachedb/examples/demo/DemoOrderReadModelPatterns.java)

Production tarafinda neden onemli:

- Redis key/value okuma hizli olsa bile relation-heavy query yine aday filtreleme, decode, sort ve object graph materialization maliyeti oder
- pahali kisim genellikle tek bir `GET` degil, ne kadar buyuk graph hydrate ettigindir
- daha kucuk summary query'ler p95'i gercek repository hot path'e daha yakin tutar

Projection refresh tuning ayarlari `cachedb.config.projectionRefresh.*` altindadir.

En kritik varsayilanlar:

- `enabled=true`
- `streamKey=cachedb:stream:projection-refresh`
- `consumerGroup=cachedb-projection-refresh`
- `batchSize=100`
- `recoverPendingEntries=true`
- `claimIdleMillis=30000`

Tam tablo icin:

- [tuning-parameters.md](./tuning-parameters.md)

## Onerilen Sonraki Adim

Starter'i bagladiktan sonra tipik sonraki adimlar sunlardir:

- entity ve relation loader'larini kaydetmek
- pahali liste akislari icin page loader tanimlamak
- admin explain UI ile fetch planlarini dogrulamak
- `/cachedb-admin` uzerinden admin UI erisimini kontrol etmek

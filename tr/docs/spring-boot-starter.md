# Spring Boot Starter

Bu proje iki farklı şekilde kullanılabilir:

- `cachedb-examples` içindeki standalone/demo çalışma biçimi
- başka bir Java uygulamasına kütüphane olarak gömülü kullanım

Bu doküman ikinci yolu anlatır. Odak noktası, Spring Boot ve admin UI'in aynı port üzerinden yayınlanmasıdır.

Production surface seçimi ve karar rehberi için ayrıca [Production Recipes](./production-recipes.md) dokümanına bak.
Geleneksel ORM kullanımına karşı daha üst seviyede konumlandırma için ayrıca [CacheDB Bir ORM Alternatifi Olarak](./orm-alternative.md) dokümanına bak.
Public beta repo hijyeni ve release hazırlığı için ayrıca [Public Beta Readiness](./public-beta-readiness.md) ve [Release Checklist](./release-checklist.md) dokümanlarına bak.

## Önerilen Production Başlangıcı

Çoğu ekip için önerilen varsayılan yol şu:

1. `CacheDatabase` bean'ini Spring Boot otomatik oluştursun
2. generated registrar'lar entity'leri otomatik register etsin
3. uygulama kodunda `GeneratedCacheModule.using(session)...` ile başla
4. sadece ölçülmüş hotspot'ları `*CacheBinding.using(session)...` veya doğrudan repository tarafına indir

Bu yol hem en kolay başlangıcı verir hem de projenin birinci önceliği olan düşük runtime overhead hedefinden ödün vermez.

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
List<UserEntity> activeUsers = users.query(
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
- iyi varsayılanlarla hızli başlamak istiyorsan
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

- `CacheDatabase` bean oluşur
- split foreground/background Redis pool devreye girer
- admin UI aynı porttan yayınlanir
- production odaklı write-behind ve guardrail varsayılanlari gelir
- worker consumer adları için otomatik runtime `instanceId` çözulur
- cleanup/report/history benzeri tekil loop'lar için Redis leader lease devreye girer

### Çok Pod'lu Kubernetes Varsayılanlari

Spring Boot starter artık tipik çok pod senaryosunu varsayılan olarak daha güvenli kurar:

- write-behind, DLQ replay, projection refresh ve incident-delivery DLQ worker'lari ortak consumer group kullanmaya devam eder
- consumer name'lere otomatik olarak pod-unique `instanceId` soneki eklenir
- cleanup/report/history benzeri singleton loop'lar Redis leader lease ile tek pod'da aktif tutulur

Varsayılan `instanceId` çözme sırası:

1. `cachedb.runtime.instance-id`
2. `CACHE_DB_INSTANCE_ID`
3. `HOSTNAME`
4. `POD_NAME`
5. `COMPUTERNAME`
6. üretilen UUID

Önerilen Kubernetes başlangici:

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

`cachedb.runtime.instance-id` değerini ancak uygulama seviyesinde açık bir kimlik istedigin durumda ver. Kubernetes'te çoğu zaman varsayılan hostname/pod-name çözumu doğru seçimdir.

Aynı host üzerinde local multi-instance smoke koşarken ise bunun tersini yap: her process için açık `cachedb.runtime.instance-id` ver ya da [../tools/ops/cluster/run-multi-instance-coordination-smoke.ps1](../tools/ops/cluster/run-multi-instance-coordination-smoke.ps1) script'ini kullan. Tek workstation genelde tek `HOSTNAME` paylastigi için, sadece hostname çözumu ile gitmek gerçek pod davranisinda görmeyecegin consumer kimliği sorunlarini saklayabilir.

## Kullanım Modlari

### 1. Düz Java kütüphanesi

Bu yol, `CacheDatabase` nesnesini kendin bootstrap etmek istediginde uygundur.

Minimum parcalar:

- `cachedb-annotations`
- `cachedb-processor`
- `cachedb-starter`
- kendi `DataSource` tanımin
- kendi `JedisPooled` tanımin

### 2. Spring Boot starter

Bu yol, Spring Boot'un şu isleri üstlenmesini istediginde uygundur:

- `CacheDatabase` bean'ini oluşturmak
- `JedisPooled` bean'ini config'ten kurmak
- mevcut Spring `DataSource` bean'ini kullanmak
- CacheDB admin UI'ini Spring Boot web sunucusunun aynı portundan yayınlamak
- admin dashboard sayfasini Thymeleaf ile render etmek

Admin UI varsayılan olarak şu taban yolundan açılir:

- `/cachedb-admin`

Yani ikinci bir public admin portu açılmaz. Uygulamanin host ve port'u neyse admin UI da aynı porttan servis edilir.

## Minimum Bağımliliklar

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
- `JedisPooled` bean'i yoksa starter bunu `cachedb.redis.uri` değerinden oluşturur.
- Eski `cachedb.redis-uri` alias'i geriye uyumluluk için çalışmaya devam eder.
- `cachedb.profile` şu değerleri kabul eder: `default`, `development`, `production`, `benchmark`, `memory-constrained`, `minimal-overhead`.
- generated package registrar'lari `ServiceLoader` ile otomatik kesfedilir; normal Spring Boot yolunda entity binding'leri için elle `register(...)` çağrısı gerekmez
- tamamen manuel binding registration istiyorsan `cachedb.registration.enabled=false` kullanabilirsin
- `cachedb.runtime.append-instance-id-to-consumer-names=true` çok pod'lu güvenli varsayılandir; consumer group'lari ortak birakir ama consumer adlarıni pod-unique yapar
- `cachedb.runtime.leader-lease-enabled=true` cleanup/report/history loop'larini Redis leader lease altına alir; boylece her pod aynı singleton isi koşmaz

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

Bu kurulum şu katmanlari acmis olur:

- Redis-first repository/session runtime
- write-behind worker'lar
- istenirse standalone admin UI

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

Bu turdaki temel kolayliklar:

- generated `*CacheBinding` sınıflari default register edilebiliyor
- generated `*CacheBinding.repository(session)` artık explicit cache policy istemiyor
- `EntityRepository` ve `ProjectionRepository` için kısa `query(...)` overload'lari var
- `QuerySpec.where(...).orderBy(...).limitTo(...).fetching(...)` immutable fluent akış sunuyor
- entity içindeki `@CacheNamedQuery` isaretli statik metodlar `UserEntityCacheBinding.activeUsers(...)` gibi helper'lar üretir
- entity içindeki `@CacheFetchPreset` isaretli statik metodlar `DemoOrderEntityCacheBinding.orderLinesPreviewRepository(...)` gibi helper'lar üretir
- entity içindeki `@CachePagePreset` isaretli statik metodlar `UserEntityCacheBinding.usersPage(...)` gibi helper'lar üretir
- entity içindeki `@CacheSaveCommand` ve `@CacheDeleteCommand` isaretli statik metodlar `UserEntityCacheBinding.activateUser(...)` ve `UserEntityCacheBinding.deleteUser(...)` gibi helper'lar üretir

Örnek:

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

Relation ağır ekranlarda önerilen desen:

- önce summary query
- sonra explicit detail fetch
- tam object graph yerine gerekirse `withRelationLimit(...)` ile preview

## Minimal Overhead Modu

CacheDB'yi gömülü kütüphane gibi kullanıyor ve admin UI ya da admin telemetrisine ihtiyaçin yoksa, açık bir minimal-overhead profili kullanman daha doğru olur.

Düz Java:

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

Neler çalışmaya devam eder:

- Redis-first repository/session runtime
- write-behind
- dead-letter recovery
- schema bootstrap/validation akışi

Semeru JDK 21 ile aldigimiz odaklı benchmark sonucu:

- benchmark scripti: `tools/ops/benchmark/measure-admin-monitoring-overhead.ps1`
- `disabledThreadDelta=0`
- `enabledIncidentThreadDelta=1`
- `activeMinusNoopBytes=4259840`

Bu ölçum tam uygulama benchmark'i değil; admin kapali modda ek admin thread açılmadigini ve aktif collector yolunun no-op yola göre daha fazla heap tuttugunu gösteren hedefli bir doğrulamadir.

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
    base-path: /cachedb-admin
    dashboard-enabled: true
    title: Benim CacheDB Admin
```

Bu kurulumla:

- Spring Boot uygulaman aynı `server.port` üzerinde çalışmaya devam eder
- CacheDB admin UI aynı porttan yayınlanir
- public dashboard URL'i şu olur:
  - `http://127.0.0.1:8080/cachedb-admin`

## Üretim İçin Varsayılan Redis Topolojisi

Spring Boot starter artık split Redis pool yapısini varsayılan üretim reçetesi olarak kabul eder.

Varsayılan davranis:

- repository veri yolu `cachedb.redis.pool.*` havuzunu kullanır
- worker/admin/telemetry arka plan yolu `cachedb.redis.background.pool.*` havuzunu kullanır
- background Redis URI ayarlanmazsa foreground URI kullanılir

Varsayılan pool boyutlari:

- foreground: `maxTotal=64`, `maxIdle=16`, `minIdle=4`
- background: `maxTotal=24`, `maxIdle=8`, `minIdle=2`
- foreground timeout'lari: `connection=2000ms`, `read=5000ms`, `blockingRead=15000ms`
- background timeout'lari: `connection=2000ms`, `read=10000ms`, `blockingRead=30000ms`

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
- karisik yuk altında p95 okuma geçikmesi daha stabil olur
- foreground SLA ile background bakim trafiği daha temiz ayrisir
- worker stream read komutlari foreground ile aynı kısa read timeout'u kullanmaz; bu da blocking Redis komutlarinda sahte `SocketTimeoutException: Read timed out` gürültüsunu azaltir

Eski tek-pool davranışını istiyorsan:

```yaml
cachedb:
  redis:
    background:
      enabled: false
```

Eğer kendi foreground `JedisPooled` bean'ini veriyorsan ve split pool da istiyorsan, ek olarak `cacheDbBackgroundJedisPooled` adli bean'i de expose et.

## Spring Boot İçinde Minimal Overhead

Spring entegrasyonu kalsin ama admin UI ve admin monitoring overhead'i olmasın istiyorsan:

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
- Spring Boot `/cachedb-admin/*` yollarini yayınlamaz
- `CacheDatabase` içinde admin monitoring kapatilir
- performance collection no-op collector yoluna geçer
- admin tarafi history ve delivery worker'lari başlamaz

## Starter Neleri Oluşturur

Eksikse starter şu bean ve bilesenleri oluşturur:

- `JedisPooled`
- `CacheDatabaseConfig`
- `CacheDatabase`
- Spring Boot servlet konteyneri içinde aynı porttan çalışan native CacheDB admin API servlet'i
- aynı base path altında Thymeleaf ile render edilen dashboard sayfasi

Bu tasarım sayesinde admin UI aynı porttan gelir ve Spring Boot modunda ikinci bir dahili HTTP sunucusu açılmaz.

## Admin UI'in Spring Boot Portundan Yayınlanmasi

Davranis:

- dış kullanıcı Boot uygulamasinin base path'i altından admin UI'a erisir
- dashboard içindeki API çağrılari da bu base path'e göre çalışir
- admin route'lari Spring Boot servlet konteyneri içinde doğrudan dispatch edilir
- base path'in koku ve `/dashboard` sayfasi Thymeleaf ile render edilir
- `/dashboard-v3` eski bookmark'lar için legacy redirect olarak kalir
- `/api/*` aynı portta native admin servlet tarafından servis edilir

Varsayılan dış URL'ler:

- dashboard: `/cachedb-admin`
- health JSON: `/cachedb-admin/api/health`
- metrics JSON: `/cachedb-admin/api/metrics`

## Spring Boot İçinde CacheDatabaseConfig Özellestirme

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

Bu yol, Boot autoconfiguration'i korurken config'i ihtiyaça göre sertlestirmek için kullanılir.

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

Bu yol, durable Redis Stream tabanlı projection refresh davranışını sadece `-Dcachedb.config.projectionRefresh.*` flag'lerine bağlı kalmadan Spring Boot içinden kurmak istediginde kullanılir.

Operasyonel yüzeyler:

- `GET /cachedb-admin/api/projection-refresh`
- `GET /cachedb-admin/api/projection-refresh/failed?limit=20`
- `POST /cachedb-admin/api/projection-refresh/replay?entryId=<dead-letter-entry-id>`

Birlikte gelen araclar:

- [list-projection-refresh-failures.ps1](/E:/ReactorRepository/cache-database/tools/ops/projection/list-projection-refresh-failures.ps1)
- [replay-projection-refresh-failure.ps1](/E:/ReactorRepository/cache-database/tools/ops/projection/replay-projection-refresh-failure.ps1)

## Production Read Deseni

Relation-heavy ekranlarda büyük eager graph yüklemek yerine `summary query + explicit detail fetch` desenini tercih et.

Doğru desen:

1. siparişleri `orderLines` yüklemeden özet olarak sorgula
2. listeyi özet alanlarla render et
3. detay gerekince ayrıca yükle
4. preload istiyorsan bile `FetchPlan.withRelationLimit(...)` ile ilişkiyi sinirla

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

Sinirli preload örneği:

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

Projection-specific index ve refresh:

- her projection kendi Redis namespace'i ve query index seti ile çalışir
- projection cache isindiginda okuma tarafi tam base entity payload decode etmek zorunda kalmaz
- `EntityProjection.asyncRefresh()` projection bakimini foreground write path dışına iter
- async refresh artık Redis Stream tabanlı durable worker ile çalışir
- refresh event'leri process restart sonrasında kaybolmaz; Redis consumer group üzerinden birden fazla uygulama node'u tarafından işlenebilir
- model tasarım geregi hala eventual consistency tabanlıdir
- ama henüz poison queue, replay tooling veya ayrik admin telemetrisi olan tam bir projection platformu değildir
- entity içindeki statik bir metoda `@CacheProjectionDefinition` koyarsan generated binding tarafında `DemoOrderEntityCacheBinding.orderSummary(...)` gibi projection helper'ları oluşur
- entity içindeki statik bir metoda `@CacheNamedQuery` koyarsan aynı generated binding hem entity repository hem projection repository için query helper üretir
- entity içindeki statik bir metoda `@CacheFetchPreset` koyarsan generated binding `withFetchPlan(...)` glue kodu yazmadan preview/detail repository helper'ları üretir
- entity içindeki statik bir metoda `@CachePagePreset` koyarsan generated binding `new PageWindow(...)` glue kodu yazmadan reuse edilebilir page/window helper'ları üretir
- entity içindeki statik bir metoda `@CacheSaveCommand` veya `@CacheDeleteCommand` koyarsan generated binding compile-time write command helper'ları üretir

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

- Redis key/value okuma hızli olsa bile relation-heavy query yine aday filtreleme, decode, sort ve object graph materialization maliyeti oder
- pahalı kisim genellikle tek bir `GET` değil, ne kadar büyük graph hydrate ettiğindir
- daha küçük summary query'ler p95'i gerçek repository hot path'e daha yakın tutar

Projection refresh tuning ayarları `cachedb.config.projectionRefresh.*` altındadir.

En kritik varsayılanlar:

- `enabled=true`
- `streamKey=cachedb:stream:projection-refresh`
- `consumerGroup=cachedb-projection-refresh`
- `batchSize=100`
- `recoverPendingEntries=true`
- `claimIdleMillis=30000`

Tam tablo için:

- [tuning-parameters.md](./tuning-parameters.md)

## Önerilen Sonraki Adim

Starter'i bağladiktan sonra tipik sonraki adimlar şunlardir:

- entity ve relation loader'larini kaydetmek
- pahalı liste akışları için page loader tanımlamak
- admin explain UI ile fetch planlarıni doğrulamak
- `/cachedb-admin` üzerinden admin UI erisimini kontrol etmek

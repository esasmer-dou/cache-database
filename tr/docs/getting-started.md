# Başlangıç Rehberi

English version: [../../docs/getting-started.md](../../docs/getting-started.md)

Bu rehber, CacheDB'yi sıfırdan bir projeye eklemek veya mevcut SQL veritabanı
üzerinde çalışan bir uygulamada kontrollü biçimde denemek için izlenecek yolu
anlatır. Starter tarafında varsayılan provider PostgreSQL'dir; MSSQL açıkça
seçilen ve kendi SQL Server evidence hattı olan provider olarak kullanılır.

Hedef, ilk gün şunları başarmaktır:

- dependency'leri doğru eklemek
- Redis ve SQL `DataSource` bağlantısını tanımlamak
- ilk entity'yi compile-time binding ile üretmek
- ilk save/read/delete akışını çalıştırmak
- relation ve projection kararını yanlış başlatmamak
- mevcut ORM sistemi varsa Migration Planner ile güvenli geçiş planı üretmek

## 1. Başlamadan Önce Karar Ver

| Durum | Başlangıç yolu |
| --- | --- |
| Yeni Spring Boot servisi | `cachedb-spring-boot-starter` |
| JPA kullanan mevcut Spring Boot servisi | Starter + mevcut `DataSource` |
| Spring kullanmayan Java servisi | `cachedb-starter` |
| Mevcut SQL veritabanı + ORM sistemi | Önce Migration Planner |
| Sadece birkaç hot endpoint var | Önce tek route pilotu |
| Çok ilişkili dashboard veya liste var | Önce projection/read-model tasarımı |

BEST: İlk denemeyi tek kritik route ile yap.

ANTI-PATTERN: Tüm tabloları modelleyip tüm trafiği bir anda CacheDB'ye almak.

## 2. Spring Boot Dependency'leri

Spring Boot kullanıyorsan çoğu ekip için önerilen yol budur. `0.10.1`, kimlik
doğrulaması istemeyen CacheDB Maven deposunda değişmez paket olarak yayımlanır.

```xml
<properties>
    <cachedb.version>0.10.1</cachedb.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.reactor.cachedb</groupId>
            <artifactId>cachedb-bom</artifactId>
            <version>${cachedb.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-spring-boot-starter-postgres</artifactId>
    </dependency>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-annotations</artifactId>
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

Şirket parent POM'u bu tanımları sağlamıyorsa aynı anonim adresi dependency ve
plugin repository listelerine ekle:

```xml
<repositories>
    <repository>
        <id>cachedb-public</id>
        <url>https://esasmer-dou.github.io/cache-database/maven2</url>
    </repository>
</repositories>
<pluginRepositories>
    <pluginRepository>
        <id>cachedb-public</id>
        <url>https://esasmer-dou.github.io/cache-database/maven2</url>
    </pluginRepository>
</pluginRepositories>
```

Token veya CacheDB kaynak kodunu yerelde derlemek gerekmez.

JDBC kuralı:

- Projede `DataSource` yoksa `spring-boot-starter-jdbc` eklenmelidir.
- Projede `spring-boot-starter-data-jpa` varsa çoğu durumda `DataSource` zaten
  vardır; sadece CacheDB için JDBC starter'ı tekrar ekleme.
- Seçtiğin SQL provider'a ait JDBC driver runtime dependency olarak kalmalıdır.
- `cachedb-processor` annotation processor olarak tanımlanmalıdır.
- Örnek PostgreSQL provider starter'ını kullanır. SQL Server için bunu
  `cachedb-spring-boot-starter-mssql` ile değiştir.
- Yalnızca bir provider starter ekle. `AUTO`, tek provider'ı bulur; classpath
  belirsizse başlangıcı durdurur.
- Yönetim ekranı gerekiyorsa `cachedb-spring-boot-starter-admin` paketini ayrıca
  ekle.

## 3. Plain Java Dependency'leri

Spring Boot kullanmıyorsan:

```xml
<properties>
    <cachedb.version>0.10.1</cachedb.version>
</properties>

<dependencies>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-starter</artifactId>
        <version>${cachedb.version}</version>
    </dependency>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-annotations</artifactId>
        <version>${cachedb.version}</version>
    </dependency>
    <dependency>
        <groupId>redis.clients</groupId>
        <artifactId>jedis</artifactId>
        <version>5.2.0</version>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <version>42.7.4</version>
    </dependency>
</dependencies>
```

Plain Java'da `CacheDatabase` yaşam döngüsünü sen başlatır ve kapatırsın.

## 4. Bağlantıları Tanımla

Spring Boot:

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
  registration:
    source: jdbc
    fail-on-unknown-entity: true
    entities:
      CustomerEntity:
        hot-entity-limit: 50000
        page-size: 100
        entity-ttl-seconds: 0
        page-ttl-seconds: 60
        hot-policy:
          mode: STATE_WINDOW
          state-column: status
          state-values: [ACTIVE]
  admin:
    http-enabled: true
```

Düz Java düşük seviyeli kayıt örneği (Spring Boot kullanıcıları generated
repository'yi enjekte etmelidir):

```java
JedisPooled jedis = new JedisPooled("redis://127.0.0.1:6379");
DataSource dataSource = createDataSource();

try (CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, dataSource)
        .production()
        .keyPrefix("app-cache")
        .register(com.example.cache.GeneratedCacheModule::registerJdbcBacked)
        .start()) {
    // uygulama kodu
}
```

Production notu: Admin UI açıksa `/cachedb-admin/**` doğrudan internete
açılmamalıdır. Gateway, reverse proxy veya CacheDB token auth arkasında
çalışmalıdır.

## 5. İlk Entity'yi Yaz

Örnek tablo:

```sql
CREATE TABLE customers (
    customer_id BIGINT PRIMARY KEY,
    tax_number VARCHAR(32) NOT NULL,
    customer_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL
);
```

Entity:

```java
@CacheEntity(table = "customers", redisNamespace = "customers")
public class CustomerEntity {
    @CacheId(column = "customer_id")
    public Long customerId;

    @CacheColumn("tax_number")
    public String taxNumber;

    @CacheColumn("customer_type")
    public String customerType;

    @CacheColumn("status")
    public String status;

    public CustomerEntity() {
    }
}
```

Önemli:

- Persisted field'lar `private` veya `final` olmamalıdır.
- Tablo ve kolon adları açıkça yazılmalıdır.
- Entity küçük tutulmalıdır.
- İlişki alanları ancak ihtiyaç varsa eklenmelidir.

Uygulamanın kullanacağı repository sözleşmesini tanımla. Application service
içinde binding veya projection repository'si elle oluşturma:

```java
@CacheRepository(entity = CustomerEntity.class)
public interface CustomerRepository extends CacheDbRepository<CustomerEntity, Long> {
    @CacheLookup(idParameter = "customerId")
    HotLookup<CustomerEntity> detail(Long customerId);

    @HotRoute(value = "active-customers", pageSize = 100, hotWindow = 50_000)
    @CacheRouteQuery(
            predicates = @CachePredicate(field = "status", constants = "ACTIVE"),
            orderBy = @CacheOrder(field = "customerId"),
            windowParameter = "window"
    )
    HotWindow<CustomerEntity> active(WindowRequest window);
}
```

Processor sözleşmeyi kontrol eder ve Spring bean'ini üretir.
`CustomerRepository` interface'ini doğrudan enjekte et.

## 6. İlk Save ve Read

```java
CustomerEntity customer = new CustomerEntity();
customer.customerId = 1001L;
customer.taxNumber = "1234567890";
customer.customerType = "RETAIL";
customer.status = "ACTIVE";

WriteReceipt<CustomerEntity, Long> receipt = customers.save(customer);

CustomerEntity loaded = customers.detail(1001L).orElseThrow(status ->
        new IllegalStateException("Müşteri Redis'te kullanıma hazır değil: " + status)
);
```

Beklenen davranış:

- `save`, policy tarafından kabul edilen entity’yi Redis’e yazar.
- Kalıcı yazım seçilen SQL write-behind hattına alınır.
- `detail` yalnızca Redis'ten okur. `NOT_CACHED`, SQL'de kayıt olmadığı
  anlamına gelmez.
- Entity etkin veri politikasına uymuyorsa Redis’e kabul edilmeyebilir.

## 7. İlk Delete

```java
WriteReceipt<CustomerEntity, Long> receipt = customers.deleteById(1001L);
```

Davranış:

- Redis tarafında entity ve index kayıtları temizlenir.
- Tombstone davranışı eski cached değerlerin yanlış servis edilmesini engeller.
- Delete işlemi seçilen SQL write-behind hattına alınır.
- Tekrar çağrıldığında idempotent davranmalıdır.

## 8. İlk Query

Küçük ve kontrollü query:

```java
HotWindow<CustomerEntity> activeCustomers = customers.active(WindowRequest.first(100));
```

Kural:

- Route sınırlı olmalı ve cursor penceresi kullanmalıdır.
- Büyük liste ekranlarında full entity query yerine projection düşünülmelidir.
- `activeCustomers.coverage()` production sözleşmesinin bir parçasıdır.

Arşiv veya etkin veri kümesinin dışındaki okumalar için sınırlı bir
`@SourceRoute` tanımla. Önceden doldurulması gereken route için aynı sorgudan
bir `@WarmRoute` üret. Tam örnek
[Deklaratif Repository Kullanımı](deklaratif-repositoryler.md) sayfasındadır.

## 9. Relation'ı Doğru Başlat

Örnek: müşteri ve sipariş.

```java
@CacheEntity(table = "orders", redisNamespace = "orders")
public class OrderEntity {
    @CacheId(column = "order_id")
    public Long orderId;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheColumn("order_date")
    public Instant orderDate;

    @CacheColumn("order_amount")
    public BigDecimal orderAmount;
}
```

Ana entity üzerinde tip güvenli ve sınırlı ilişki metadata'sını tanımla.
Processor standart partitioned loader'ı üretir:

```java
@CacheEntity(table = "customers", redisNamespace = "customers")
public class CustomerEntity {
    @CacheRelation(
            target = OrderEntity.class,
            // OrderEntity.customerId alanı orders.customer_id kolonuna map edilir.
            mappedBy = "customerId",
            kind = CacheRelation.RelationKind.ONE_TO_MANY,
            batchLoadOnly = true,
            maxRowsPerParent = 100,
            parentBatchSize = 32,
            orderBy = {"orderDate DESC", "orderId DESC"}
    )
    public List<OrderEntity> orders;
}
```

Bu annotation CacheDB metadata'sıdır; veritabanı foreign key'i değildir. DB'de
`orders.customer_id -> customers.customer_id` constraint'i olsa bile bu
annotation yoksa CacheDB relation preload yapmaz. Annotation ile üretilen loader
var ama DB foreign key yoksa CacheDB `mappedBy` üzerinden relation'ı
yükleyebilir; fakat kalıcı veri bütünlüğü artık senin sorumluluğundadır. Özel
yükleme mantığı gerekiyorsa `@CacheEntity.relationLoader` kullan.

Önizleme sınırını repository sözleşmesine koy ve enjekte edilen arayüzü kullan:

```java
@CacheLookup(idParameter = "customerId", relation = "orders",
        relationLimitParameter = "orderPreview", maxRelationRows = 25)
HotLookup<CustomerEntity> detail(Long customerId, int orderPreview);

CustomerEntity customer = customers.detail(customerId, 10)
        .orElseThrow(status -> mapHotLookupFailure(customerId, status));
```

Bu, yalnızca küçük preview için kabul edilebilir. Müşteri başına yüzlerce veya
binlerce sipariş gösterilecekse projection kullan.

## 10. İlk Projection Kararı

Örnek ihtiyaç:

- müşteri detay ekranında son 10 sipariş gösterilecek
- müşteri başına son 1000 sipariş özeti Redis'te tutulacak
- sipariş detayına girilince full `OrderEntity` okunacak

Doğru model:

```text
CustomerOrderSummary
- order_id
- customer_id
- order_date
- order_amount
- currency_code
- status
```

Bu model, full `OrderEntity` yerine ilk liste ekranının ihtiyaç duyduğu küçük
payload'u taşır.

BEST: Liste projection, detay entity.

ANTI-PATTERN: Liste ekranında her siparişin tüm satırlarını ve tüm ilişkilerini
yüklemek.

## 11. Mevcut SQL Veritabanı + ORM Uygulamasında İlk Deneme

Mevcut sistemde doğrudan kod yazmaya başlamadan önce Migration Planner kullan:

1. Uygulamayı admin UI açık şekilde başlat.
2. `/cachedb-admin/migration-planner` ekranına git.
3. "Kaynak veritabanı şemasını keşfet" adımını çalıştır.
4. Önerilen route adaylarından birini seç.
5. Adayı forma uygula.
6. Planı oluştur.
7. Scaffold üret.
8. Dry-run warm çalıştır.
9. Staging warm çalıştır.
10. Side-by-side comparison çalıştır.
11. Raporu indir.

Karar kuralı:

- Veri eşleşmesi yoksa canlıya geçme.
- Projection gerekli route entity fallback ile çalışıyorsa canlıya geçme.
- CacheDB p95 route hedefini karşılamıyorsa hot policy/projection/route
  contract tasarımını düzelt.

## 12. Lokal Doğrulama

En az şu komutu çalıştır:

```powershell
mvn -q -DskipTests package
```

Türkçe doküman kalite kontrolü:

```powershell
pwsh tools\ci\check-tr-docs.ps1
```

Production evidence için:

```powershell
pwsh tools\ci\run-production-evidence.ps1
pwsh tools\ci\run-production-scenario-certification.ps1
```

## 13. İlk Gün Sonunda Elinde Ne Olmalı?

En az şunları tamamlamış olmalısın:

- bir entity compile ediliyor
- generated binding oluşuyor
- Redis ve SQL `DataSource` bağlantısı çalışıyor
- save/read/delete akışı denenmiş
- ilk kritik route seçilmiş
- büyük liste varsa projection ihtiyacı belirlenmiş
- mevcut sistemden geçiliyorsa Migration Planner raporu alınmış
- production için admin exposure, Redis HA ve route contract notları yazılmış

## Sonraki Okuma

- [Kavramlar ve Kabuller](kavramlar-ve-kabuller.md)
- [Kullanım Senaryosu Örnekleri](use-case-examples.md)
- [Spring Boot Starter](spring-boot-starter.md)
- [Production Tuning Rehberi](production-tuning-rehberi.md)
- [Geçiş Planlayıcı](migration-planner.md)
- [Production Reçeteleri](production-recipes.md)

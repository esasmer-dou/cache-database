# CacheDB PostgreSQL Örneği

[English](README.md) | Türkçe

[![Consumer build](https://github.com/esasmer-dou/sample-cache-database-postgresql/actions/workflows/consumer-build.yml/badge.svg?branch=main)](https://github.com/esasmer-dou/sample-cache-database-postgresql/actions/workflows/consumer-build.yml)
[![CacheDB 0.14.0](https://img.shields.io/badge/CacheDB-0.14.0-0b7285.svg)](https://github.com/esasmer-dou/cache-database/releases/tag/v0.14.0)

Bu proje, CacheDB'nin Redis 8 ve PostgreSQL ile nasıl kullanılacağını gösteren
bir Spring Boot REST API örneğidir. Canlı ortamda verilmesi gereken kararları
açıkça gösterir: operasyonel yollar Redis'teki sınırlı aktif veri setini
kullanır, kalıcı geçmiş PostgreSQL'de tutulur, büyüyen listeler ise bütün nesne
ağacı yerine projection üzerinden okunur.

> Bu sürüm, genel kullanıma açık CacheDB Maven deposundaki değişmez `0.14.0`
> paketini kullanır. GitHub token'ı veya yerel CacheDB kaynak kodu gerekmez.

Hızlı başlangıcı tamamladığında canlı ortama benzeyen tek bir yolu baştan sona
doğrulamış olacaksın: kalıcı PostgreSQL verisini oluşturacak, sınırlı arşiv
yolunu okuyacak, karşılık gelen Redis projection'ını hazırlayacak, aktif yolu
çağıracak ve kapsam ile kalıcılık sinyallerini inceleyeceksin. Redis'te kayıt
bulunmaması arka planda gizli bir PostgreSQL sorgusu başlatmaz.

## CacheDB 0.14.0'a Yükseltme

Örnek POM; BOM, sağlayıcı starter'ı, annotation processor ve doctor plugin'ini
`0.14.0` ile eşitler. Binding'lerin yeniden üretilmesi için örnek projeyi derleyin.
POM ayrıca `jedis.version=5.2.0` ayarını içerir. Bu ayar olmadan Spring Boot parent,
gerekli pipeline API'sini içermeyen eski sürücüyü seçebilir.

- Mevcut REST, arşiv, projection ve ön yükleme yollarının açık davranışı korunur.
- Mevcut SQL worker'larını yükseltmeden önce sağlayıcınıza ait `cachedb_write_fence` migration'ını uygulayın, eski worker'ları devreden çıkarın ve sürüm sayaçlarını koruyun. Ayrı metadata tablosu, geciken eski yazmanın silinmiş kaydı yeniden oluşturmasını engeller; iş tabloları değişmez. [Kalıcı yazma sürümleri](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/kalici-yazma-surumleri.md) rehberini izleyin.
- Tipli snapshot, Jackson'sız codec ve atomik ürün aktarımı çalışan katalog senaryosunda kullanılır. Ayrı API/worker profilleri de bulunur. [Çalışan 0.14 senaryolarını](#çalışan-014-özellik-senaryoları) adım adım izleyin.
- Katı 50 ms CI hedefi, ilk kullanım dâhil üretilen codec/snapshot senaryosu içindir. REST, SQL, ağ veya canlı sistem SLA'sı değildir; eski mapper API'lerinin uyumluluğu ve tüm JUnit metot süreleri ayrıca raporlanır.
- `hotEntityLimit=0` entity kabulünü kapatır. `-1` değerini yalnızca bilinçli bir sınırsız kayıt politikası için kullanın. Varsayılan 1.000 olarak korunur.
- SQL kolonunuz NULL alabiliyorsa `Boolean` kullanın; primitive `boolean`, SQL NULL değerini reddeder.
- Atomik katalog aktarımı ve indeksli snapshot sayfaları açık endpoint'lerle gösterilir. Redis Cluster dağılımı isteğe bağlı framework altyapısıdır; yerel Compose tek Redis kullanır.

Bu API'leri eklemeden önce [sürüm notlarını](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/releases/v0.14.0.md)
ve [sınırlı çalışma sözleşmelerini](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/sinirli-calisma-islemleri.md)
okuyun. Paketin kullanılabilir olması, uygulamanızın canlı ortam topolojisini doğrulamaz.

## Buradan Başla

| Hedefin | İlgili bölüm |
| --- | --- |
| Örneği çalıştırmak | [Hızlı Başlangıç](#hızlı-başlangıç) |
| Redis ve PostgreSQL davranışını anlamak | [Çalışma Zamanı Sözleşmesi](#çalışma-zamanı-sözleşmesi) |
| Deklaratif Java API'sini görmek | [Kod Üzerinden Akış](#kod-üzerinden-akış) |
| PostgreSQL'deki mevcut veriyi Redis'e hazırlamak | [Mevcut Veriyi Hazırlama](#mevcut-veriyi-hazırlama) |
| Cache sınırlarını belirlemek | [Kullanım Senaryosuna Göre Ayar](#kullanım-senaryosuna-göre-ayar) |
| Tüm yolları denemek | [API Kataloğu](#api-kataloğu) veya [Postman](#postman) |
| Tipli snapshot, atomik aktarım ve ayrı worker'ları çalıştırmak | [Çalışan 0.14 Özellik Senaryoları](#çalışan-014-özellik-senaryoları) |
| Canlı ortam hazırlığını kanıtlamak | [Production Sertifikası](#production-sertifikası) |
| Canlı ortam geçişini hazırlamak | [Canlı Ortam Kontrol Listesi](#canlı-ortam-kontrol-listesi) |
| Başlangıç veya veri yolu sorununu çözmek | [Sorun Giderme](#sorun-giderme) |

## Bu Örnek Ne Öğretiyor?

Örnek, basit bir CRUD uygulamasından daha geniş bir alanı kapsar:

- müşteriler sipariş verir; siparişlerin çok sayıda satırı olabilir
- ürün uygunluğu katalog ve düşük stok ekranlarını besler
- gönderiler aktif, istisna, hareket ve arşiv yollarına ayrılır
- destek talepleri operasyon paneline veri sağlar
- rapor işleri ve denetim olaylarında anlık iş yükü ile kalıcı geçmiş ayrılır

Ürünün sınırı da aynı açıklıkla gösterilir:

| Sınıflandırma | Anlamı |
| --- | --- |
| **BEST** | Sınırlı bir operasyonel yol tanımla, entity veya projection verisini hazırla, ölç ve arşiv/geçmiş okumalarını PostgreSQL'de tut. |
| **ACCEPTABLE** | Redis'teki aktif veri setinin dışında kalan ve seyrek okunan veri için sınırlı bir PostgreSQL yolu kullan. |
| **ANTI-PATTERN** | CacheDB'yi şeffaf bir cache gibi görüp Redis'te bulunmayan her sorgunun otomatik SQL çalıştırmasını ve Redis'i doldurmasını bekleme. |

CacheDB, hangi ekran ve komutların öngörülebilir düşük gecikmeye ihtiyaç
duyduğunu bilen ekipler için güçlü bir çözümdür. Temel iş yükü bütün veritabanı
üzerinde sınırsız ve anlık sorgular çalıştırmak olan uygulamalar için uygun
değildir.

## Mimari

```mermaid
flowchart LR
    Client["REST istemcisi"] --> API["Controller"]
    API --> Service["Uygulama servisi"]
    Service --> Repo["Üretilen CacheDB repository"]
    Repo -->|"HotRoute / CacheLookup"| Redis[(Redis 8 aktif veri seti)]
    Repo -->|"SourceRoute"| PostgreSQL[(PostgreSQL kalıcı geçmiş)]
    Repo -->|"Komut"| Stream["Redis Stream write-behind"]
    Stream --> Worker["Sınırlı kalıcılık işçisi"]
    Worker --> PostgreSQL
    PostgreSQL -->|"WarmRoute"| Warm["Ön yükleme / backfill işi"]
    Warm --> Redis
```

Uygulama kodu repository interface'lerine bağımlıdır. Annotation processor;
implementasyonları, codec'leri, indeksleri, projection binding'lerini ve Spring
bean'lerini derleme sırasında üretir. Entity keşfi için çalışma zamanı
reflection'ı kullanılmaz.

## Kısa Sözlük

| Terim | Bu örnekteki anlamı |
| --- | --- |
| Entity | SQL kolonlarına ve Redis namespace'ine eşlenen komut/detay modeli |
| Projection | `OrderSummary` gibi küçük ve ekrana özel okuma modeli |
| Aktif veri seti | Redis'te bilinçli olarak tutulan sınırlı veri kümesi |
| Aktif yol (`HotRoute`) | Redis'teki aktif veri setini okuyan repository metodu |
| Kaynak yolu (`SourceRoute`) | PostgreSQL'i açıkça ve sınırlı biçimde okuyan repository metodu |
| Ön yükleme (`warm/backfill`) | PostgreSQL'den Redis'e kontrollü veri hazırlama işi |
| Route coverage | Gerekli kapsamın ve pencerenin Redis'te hazır olduğunu gösteren kanıt |
| Write-behind | Redis'in kabul ettiği komutun PostgreSQL'e asenkron yazılması |
| Write receipt | Kimlik, sürüm ve kalıcılık durumunu izlemek için kullanılan komut sonucu |

## Gereksinimler

- JDK 21
- Maven 3.9+
- Docker Desktop veya uyumlu bir Docker Engine
- Hazır yük testi için PowerShell 7+

Yerel araçları kontrol et:

```powershell
java -version
mvn -version
docker version
docker compose version
```

## Bağımlılık Modeli

Örnek proje CacheDB'yi Maven artifact'leri üzerinden kullanır. Sample build'i
framework kaynak kodunu kendi içinde derlemez.

```xml
<properties>
    <java.version>21</java.version>
<cachedb.version>0.14.0</cachedb.version>
<jedis.version>5.2.0</jedis.version>
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
                <release>${java.version}</release>
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

Yönetim ekranı gerekiyorsa `cachedb-spring-boot-starter-admin` ekle. JPA veya
başka bir starter uygulama için zaten `DataSource` oluşturuyorsa
`spring-boot-starter-jdbc` bağımlılığını tekrar eklemen gerekmez. CacheDB'nin
ihtiyacı; çalışan bir `DataSource`, tek bir veritabanı starter'ı, annotations
artifact'i ve annotation processor'dır.

Maven, normal bağımlılıkları ve build plugin'lerini farklı repository
listelerinden çözümler. Bu nedenle genel kullanıma açık CacheDB deposu iki
bölümde de tanımlanır:

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
        <releases><enabled>true</enabled></releases>
        <snapshots><enabled>false</enabled></snapshots>
    </pluginRepository>
</pluginRepositories>
```

`repositories`; BOM, starter ve kütüphaneleri çözümler.
`pluginRepositories` ise `cachedb-maven-plugin` eklentisini çözümler. Adres
herkese açıktır; Maven `settings.xml`, kullanıcı adı veya token gerekmez.

## Hızlı Başlangıç

### 1. Yayımlanmış CacheDB paketini çözümle

Sample projesini doğrudan doğrula. Maven; BOM, starter, annotation processor ve
doctor plugin'ini değişmez `0.14.0` paketinden kimlik bilgisi istemeden çözümler:

```powershell
mvn -U -DskipTests validate
```

Build çıktısında `CacheDB doctor` ve
`OK: CacheDB build contract is consistent` satırları görünmelidir. Build,
yerel olarak kurulmuş bir CacheDB checkout'una bağımlı olmamalıdır.

### 2. Redis ve PostgreSQL'i başlat

```powershell
docker compose up -d
docker compose ps
```

Compose dosyası şu servisleri açar:

| Servis | Adres | Yerel kullanım amacı |
| --- | --- | --- |
| Redis 8.2.1 | `127.0.0.1:56379` | Aktif entity, projection, indeks, stream, lease ve telemetry verileri |
| PostgreSQL 16 | `127.0.0.1:55432` | Kalıcı doğruluk kaynağı |

### 3. API'yi demo profiliyle başlat

Yerel şema kurulumu, seed ve ön yükleme endpoint'leri, periyodik warm ve yönetim
ekranı için `demo` profili zorunludur.

Bu örnekte veritabanı sağlayıcısına özel `schema.sql` dosyasını Spring çalıştırır.
CacheDB bu betikten sonra başlar ve sonucu `VALIDATE_ONLY` modunda doğrular; DDL
yönetimi için Spring ile yarışmaz.

```powershell
$env:SPRING_PROFILES_ACTIVE = "demo"
mvn spring-boot:run
```

Bash karşılığı:

```bash
SPRING_PROFILES_ACTIVE=demo mvn spring-boot:run
```

### 4. Hazırlık durumunu kontrol et

```powershell
Invoke-RestMethod http://127.0.0.1:8091/actuator/health/readiness
```

Durum `UP` olmadan ilerleme. Readiness; Redis, PostgreSQL ve write-behind
durumunu birlikte değerlendirir. Liveness ise yalnızca uygulama sürecinin
çalıştığını gösterir.

### 5. Kalıcı demo verisini oluştur

Seed işlemi sınırlı ve dağıtık bir iş olarak çalışır; `202 Accepted` döner. Tek
HTTP isteğini açık tutmak yerine işi durum endpoint'inden izle:

```powershell
$seed = Invoke-RestMethod -Method Post `
  -Uri "http://127.0.0.1:8091/api/demo/seed?customers=20&ordersPerCustomer=40&linesPerOrder=4"

do {
    Start-Sleep -Milliseconds 250
    $seedState = Invoke-RestMethod "http://127.0.0.1:8091/api/warm/jobs/$($seed.jobId)"
} while ($seedState.status -in @("QUEUED", "RUNNING"))

if ($seedState.status -ne "COMPLETED") {
    throw ($seedState | ConvertTo-Json -Depth 8)
}
```

`COMPLETED`, seed işinin tamamlandığını gösterir. Production geçişinde SQL
kalıcılığının sağlıklı olduğunu doğrulamak için ayrıca readiness durumunu ve
write-behind kuyruğunu izlemelisin.

Seed işlemi kalıcı demo kayıtlarını oluşturur; ancak bütün Redis route'larını
hazır kabul etmez. İlgili warm işi tamamlanıp coverage kaydı oluşmadan hızlı
erişim listesi bilinçli olarak `503 Service Unavailable` döner. Böylece boş ya
da eksik bir Redis penceresi, tam iş sonucu sanılmaz.

### 6. Müşteri sipariş projection'ını hazırla

```powershell
$warm = Invoke-RestMethod -Method Post `
  -Uri "http://127.0.0.1:8091/api/warm/orders/customer/1?limit=100&projectionOnly=true"

do {
    Start-Sleep -Milliseconds 250
    $warmState = Invoke-RestMethod "http://127.0.0.1:8091/api/warm/jobs/$($warm.jobId)"
} while ($warmState.status -in @("QUEUED", "RUNNING"))

if ($warmState.status -ne "COMPLETED") {
    throw ($warmState | ConvertTo-Json -Depth 8)
}
```

### 7. Aktif yol ile arşiv yolunu karşılaştır

```powershell
# Redis projection yolu
$page = Invoke-RestMethod "http://127.0.0.1:8091/api/customers/1/orders?limit=10"
$page.items

# nextCursor varsa offset kullanmadan devam et
if ($page.nextCursor) {
    Invoke-RestMethod "http://127.0.0.1:8091/api/customers/1/orders?limit=10&after=$($page.nextCursor)"
}

# Sınırlı PostgreSQL yolu
Invoke-RestMethod "http://127.0.0.1:8091/api/orders/archive?customerId=1&limit=10"
```

### 8. Operasyon araçlarını aç

- Yönetim ekranı: `http://127.0.0.1:8091/cachedb-admin`
- Güncel ayarlar: `http://127.0.0.1:8091/api/tuning`
- Periyodik warm durumu: `http://127.0.0.1:8091/api/warm/schedules`

PostgreSQL verisini silmeden yerel servisleri durdur:

```powershell
docker compose down
```

`docker compose down -v` komutunu yalnızca yerel PostgreSQL volume'unu bilinçli
olarak silmek istediğinde kullan.

## Çalışma Zamanı Sözleşmesi

| İşlem | Ana veri yolu | Veri Redis'in dışındaysa | Kalıcılık ve güvenlik kuralı |
| --- | --- | --- | --- |
| `save`, update, soft delete | Önce Redis, sonra PostgreSQL write-behind | Veri kabul politikası izin veriyorsa komut Redis'e girer | `202 Accepted`, SQL commit anlamına gelmez; işlem makbuzunu ve readiness ölçümlerini izle |
| Entity detayı | Redis entity sorgusu | Açık bir `unavailable/not-found` sonucu döner; kendiliğinden sınırsız SQL çalıştırmaz | Entity yolunu hazırla veya sınırlı source-detail yolu oluştur |
| Büyüyen liste veya panel | Redis projection | Tam route kapsamı hazır değilse `completeItems()` çağrısı `503 Service Unavailable` döner | Aynı kapsamı hazırlayan warm işini çalıştır, `COMPLETED` durumunu bekle ve geçişten önce coverage doğrula |
| Arşiv, dışa aktarma, denetim geçmişi | Sınırlı PostgreSQL source route | PostgreSQL'i doğrudan okur | Satır sınırı, deterministik sıralama, indeks ve timeout kullan |
| PostgreSQL'deki mevcut kayıt | Warm/backfill PostgreSQL'den okur ve Redis'i doldurur | Uygulama açılırken otomatik içe aktarma yapılmaz | Önce dry-run, ardından sınırlı warm ve coverage kontrolü yap |
| CacheDB dışından PostgreSQL yazısı | Önce PostgreSQL değişir | Bir değişiklik akışı yoksa Redis eski kalabilir | Outbox/CDC kullan; periyodik warm olay aktarımının yerine geçmez |

Aktif veri seti, veritabanının ikinci ve tam kopyası değildir. Redis belleğini;
entity veri yükleri, projection'lar, indeksler, stream durumu, lease kayıtları ve
operasyon metadata'sıyla birlikte hesaplamalısın.

## Kod Üzerinden Akış

Bu bölümdeki kısa parçalar öğretici kesitlerdir; tam kaynak dosyaları bağlantılarda bulunur.
[Çalışan 0.14 senaryolarında](#çalışan-014-özellik-senaryoları) ise import ve constructor'ları dâhil tam, derlenen Java dosyaları yer alır.

### 1. Entity: kalıcı veri şekli

[`OrderEntity`](src/main/java/com/example/cachedb/sample/domain/OrderEntity.java),
SQL kolonlarını, Redis namespace'ini, bölümlenmiş indeksi ve sınırlı ilişkiyi
tanımlar:

```java
@CacheEntity(table = "sample_orders", redisNamespace = "sample-orders")
@CachePartitionedIndex(partitionBy = "customer_id", sortBy = "order_date")
public class OrderEntity {
    @CacheId(column = "order_id")
    public Long orderId;

    @CacheColumn("customer_id")
    public Long customerId;

    @CacheColumn("order_date")
    public Long orderDate;

    @CacheRelation(
            target = OrderLineEntity.class,
            mappedBy = "orderId",
            kind = CacheRelation.RelationKind.ONE_TO_MANY,
            batchLoadOnly = true,
            maxRowsPerParent = 50,
            parentBatchSize = 16,
            orderBy = "lineNumber ASC"
    )
    public List<OrderLineEntity> lines;
}
```

Veritabanındaki foreign key kalıcı ilişki bütünlüğünü korur.
`@CacheRelation` ise CacheDB'ye ilişkinin nasıl ve hangi sınırla yükleneceğini
söyler. Biri olmadan diğeri teknik olarak bulunabilir; canlı ortam modelinde
çoğunlukla ikisine de ihtiyaç vardır.

### 2. Projection: ekranın ihtiyacı olan şekil

[`OrderSummary`](src/main/java/com/example/cachedb/sample/readmodel/OrderSummary.java),
`OrderEntity`'den küçüktür ve sipariş satırı veri yüklerini içermez:

```java
@CacheProjectionRecord(
        source = OrderEntity.class,
        id = "orderId",
        name = "order-summary",
        rankedBy = {"order_date", "priority_score"},
        refresh = CacheProjectionRecord.Refresh.ASYNC
)
public record OrderSummary(
        Long orderId,
        Long customerId,
        Long orderDate,
        BigDecimal orderAmount,
        String currencyCode,
        String orderType,
        String status,
        Integer lineCount,
        Double priorityScore
) {
}
```

Komut ve seçilmiş detay için entity; liste, zaman çizelgesi, panel, top-N ve
global sıralı yol için projection kullan.

### 3. Repository: yol sözleşmesi

[`OrderRepository`](src/main/java/com/example/cachedb/sample/repository/OrderRepository.java)
yolu tanımlar; implementasyonu processor üretir:

```java
@CacheRepository(entity = OrderEntity.class)
@CacheRepositoryDefaults(
        hotPopulation = HotRoute.Population.DECLARED_WARM,
        sourceMaxRows = 500,
        sourceTimeoutSeconds = 15
)
public interface OrderRepository extends CacheDbRepository<OrderEntity, Long> {

    @HotRoute(
            value = "customer-order-timeline",
            projection = OrderSummary.class,
            pageSize = 100,
            hotWindow = 1_000,
            memoryBudgetBytes = CacheMemoryBudget.MIB_16,
            coverageScopeParameter = "customerId"
    )
    @CacheRouteQuery(
            predicates = {
                    @CachePredicate(field = "customerId"),
                    @CachePredicate(field = "status", operator = CachePredicate.Operator.NE, constants = "DELETED")
            },
            orderBy = {
                    @CacheOrder(field = "orderDate", direction = CacheOrder.Direction.DESC),
                    @CacheOrder(field = "orderId", direction = CacheOrder.Direction.DESC)
            }
    )
    CursorPage<OrderSummary> customerTimeline(long customerId, WindowRequest window);

    @WarmRoute(
            value = "warm-customer-order-timeline",
            from = "customerTimeline",
            maxRows = 1_000
    )
    CacheWarmPlan warmCustomerTimeline(
            long customerId,
            int maxRows,
            CacheWarmTarget target
    );
}
```

Yol sözleşmesi; sayfa boyutunu, aktif pencereyi, bellek bütçesini, sıralamayı,
coverage kapsamını ve warm sınırını tek yerde görünür kılar. Processor; alanla
aynı adı taşıyan predicate parametresini, tek `WindowRequest` parametresini,
warm satır sınırını, warm hedefini ve warm coverage kapsamını çıkarır. Birden
fazla aday varsa sessiz seçim yapmaz; derlemeyi açık bir hatayla durdurur.

### 4. Uygulama servisi: iş akışının yönetimi

[`CustomerApplicationService`](src/main/java/com/example/cachedb/sample/application/customer/CustomerApplicationService.java),
Redis client'ı veya generated binding sınıfı yerine interface kullanır:

```java
@Service
public final class CustomerApplicationService {
    private final CustomerRepository customers;
    private final OrderRepository orders;

    public CustomerEntity detail(long customerId, int orderPreview) {
        return SampleHotLookups.require(
                "Customer",
                customerId,
                customers.detail(customerId, orderPreview)
        );
    }

    public CursorPage<OrderSummary> orderTimeline(long customerId, int limit, String after) {
        return orders.customerTimeline(customerId, WindowRequest.of(limit, after));
    }
}
```

Controller HTTP girdisini doğrular. Uygulama servisi kullanım senaryosunu
yönetir. Repository interface'i veri yolu sözleşmesini taşır. Generated kod;
serileştirme, indeks ve veritabanı sağlayıcısı bağlantısını üstlenir.

REST endpoint'i yanıttaki `nextCursor` değerini bir sonraki isteğin isteğe bağlı
`after` parametresi olarak kabul eder. Cursor bu route'a, müşteri kapsamına ve
sıralama sözleşmesine bağlıdır; başka müşteri veya route için kullanılamaz.
Strict bir HOT route doğrudan `CursorPage<T>` dönebilir; generated kod sayfayı
oluşturmadan önce coverage bilgisinin eksiksiz ve güncel olduğunu doğrular.
Uygulama coverage kanıtını ayrıca inceleyecekse dönüş tipi `HotWindow<T>` kalmalıdır.

Processor ayrıca reflection kullanmayan bir route companion sınıfı üretir.
Integration test ve operasyon kodunda route adını string olarak tekrarlamak
yerine bu referansı kullan:

```java
cacheDbTestProbe.requireDeclaredWarmRoute(
        OrderRepositoryCacheDbRoutes.customerTimeline()
);

RouteCoverage coverage = cacheDbTestProbe.coverage(
        OrderRepositoryCacheDbRoutes.customerTimeline(),
        String.valueOf(customerId),
        Duration.ofMinutes(5)
);
```

Repository metodu veya route sözleşmesi değişirse bu kullanım derleme sırasında
değişmek zorunda kalır. Generated companion `target/generated-sources` altında
oluşur; bu sınıfı `src/main/java` dizinine kopyalama.

## Mevcut Veriyi Hazırlama

PostgreSQL'deki mevcut satırlar uygulama açılırken otomatik olarak Redis'e
aktarılmaz. Mevcut sistemden geçerken şu sırayı izle:

1. Sınırlı bir `@HotRoute` veya `@CacheLookup` tanımla.
2. Aynı yol için `@WarmRoute` ekle.
3. `dryRun=true` çalıştır ve aday satır sayısını incele.
4. Gerçek warm işini gönder ve `COMPLETED` durumuna kadar izle.
5. Route coverage ile PostgreSQL üyelik/sıralama karşılaştırmasını doğrula.
6. Trafiği kademeli aç ve PostgreSQL geri dönüş yolunu koru.

Dry-run örneği:

```powershell
Invoke-RestMethod -Method Post `
  -Uri "http://127.0.0.1:8091/api/warm/orders/customer/1?limit=100&projectionOnly=true&dryRun=true"
```

Liste ve panel için yalnızca projection hazırlamak en doğru seçimdir. Tam entity
hazırlama, yalnızca seçilmiş detay veya komut yolu bütün aktif veri yüküne ihtiyaç
duyuyorsa kullanılmalıdır.

Eski tablolarda `entity_version` değeri `NULL` veya `0` ise ilk hazırlama
sırasında başlangıç Redis sürümü kullanılır. Bu davranış yalnızca geçişi
kolaylaştırır; değişiklik akışının yerini tutmaz. Geçişten sonra PostgreSQL'e
dışarıdan yazan her uygulama sürümü düzenli artırmalı ve değişikliği outbox/CDC
ile yayımlamalıdır. Aksi durumda bir sonraki sınırlı uzlaştırma çevrimine kadar
oluşacak gecikme bilinçli olarak kabul edilmelidir.

### Periyodik warm ve uzlaştırma

[`SampleScheduledWarmPlans`](src/main/java/com/example/cachedb/sample/config/SampleScheduledWarmPlans.java),
90 günlük sipariş penceresini deklaratif olarak tanımlar. Redis lease sayesinde
bir döngüyü yalnızca bir pod çalıştırır; diğer pod'lar güvenli biçimde bekler
veya döngüyü atlar. Uzlaştırma, policy kapsamından çıkan kayıtları temizler.

```java
@CacheScheduledWarm(
        name = "sample-active-order-window",
        fixedDelayString = "${sample.scheduled-warm.orders.fixed-delay:PT15M}",
        lockAtMostForString = "${sample.scheduled-warm.orders.lock-at-most-for:PT2M}",
        lockWaitTimeoutString = "${sample.scheduled-warm.orders.lock-wait-timeout:PT20S}",
        minimumIntervalString = "${sample.scheduled-warm.orders.minimum-interval:PT15M}",
        reconcileHotSet = true
)
public CacheWarmPlan activeOrderWindow() {
    long cutoff = Instant.now().minus(Duration.ofDays(90)).getEpochSecond();
    return orders.warmActiveWindow(cutoff, orderWarmMaxRows);
}
```

Periyodik warm seçilen aktif pencereyi korur. CacheDB üzerinden gelen yeni
yazılar normal komut yoluyla hemen işlenir; bir sonraki zamanlanmış döngüyü
beklemez.

Annotation processor metot imzasını derleme sırasında doğrular ve tipli bir
Spring task adapter'ı üretir. Runtime, annotation eklenen metotları taramaz ve
reflection ile çağırmaz.

### Ön yükleme çalıştırma, sorgu niyeti ve kalıcılık

Tipli hedef, entity mi yoksa yalnızca projection mı hazırlanacağını bir kez
seçer. Generated plan bu kararı taşır; çalıştırma aşamasında yalnızca deneme
veya uygulama modu seçilir:

```java
CacheWarmTarget target = projectionOnly
        ? CacheWarmTarget.PROJECTIONS_ONLY
        : CacheWarmTarget.ENTITY_AND_PROJECTIONS;
CacheWarmPlan plan = orders.warmCustomerTimeline(customerId, limit, target);
CacheWarmExecution execution = cacheDatabase.executeWarm(
        plan,
        dryRun ? CacheWarmExecutionMode.DRY_RUN : CacheWarmExecutionMode.APPLY
);
CacheWarmSummary summary = execution.summary("customer-orders");
```

`DRY_RUN` Redis'i değiştirmez. Aynı yol için ayrı entity/projection metotları
oluşturma ve planın kararını ikinci bir `warmProjections`/`warm` koşuluyla
uygulama kodunda tekrar etme.

REST endpoint, ön yüklemeyi HTTP isteğini karşılayan iş parçacığında çalıştırmaz.
Tipli komutu Redis üzerindeki dayanıklı iş kuyruğuna gönderir; `202 Accepted` ve
işi izleyeceğin `Location` başlığını döndürür:

```java
public record SampleWarmCommand(Route route, int limit, boolean projectionOnly,
                                boolean dryRun) {
}

static final CacheDistributedJobDefinition<SampleWarmCommand> WARM_JOB =
        CacheDistributedJobDefinition.of("sample.route.warm", SampleWarmCommand.class);

CacheDistributedJobSnapshot job = jobs.submit(WARM_JOB, command);
// Location: /api/warm/jobs/{jobId}
```

Handler, `CacheDistributedJobHandler.Typed<SampleWarmCommand>` interface'ini
uygular ve aynı tanımı döndürür. Sınırlı ilerleme bilgisi
`CacheDistributedJobProgress` ile yazılır; route metni ve payload tipi tekrar edilmez.

Her pod aynı iş tanımını kaydeder. Redis iş durumunu ve checkpoint bilgisini
tutar; yarım kalan işi başka bir pod devralabilir. Satır sınırı, tekrarlanabilir
ön yükleme davranışı ve generated SQL sözleşmesi korunur. Arka plan işi, sorguyu
sınırsız hâle getirmez.

Sorgu koşullarındaki gruplar da açık bir sözleşmedir. Aynı gruptaki koşullar
AND, farklı gruplar OR ile birleştirilir. Aktif sipariş penceresi bilinçli
olarak "son 90 gün OR aktif durum" anlamına geldiği için
`@CacheRouteQuery`, `0` ve `1` gruplarıyla birlikte
`explicitDisjunction = true` kullanır. Birden fazla grup kullanıp bu onayı
vermeyen sorgu derleme sırasında reddedilir. İş kuralı AND ise bütün koşulları
aynı grupta tut.

Seed ve içe aktarma batch'leri SQL kalıcılık kanıtını korur:

```java
try (var orders = cacheDatabase.durableBatchWriter(
        "sample seed/orders", 128, 1_024, Duration.ofSeconds(30),
        orderRepository::saveAll
)) {
    sourceOrders.forEach(orders::add);
}
```

Timeout, komutun Redis tarafından kabul edildiğini fakat verilen süre içinde
SQL kalıcılığının doğrulanamadığını anlatır. Exception, receipt listesini ve
operasyon adını taşır. Bu nedenle aynı yazıları körlemesine tekrar gönderme;
önce receipt durumunu incele.

### Üretilen route envanteri

Annotation processor her repository için route (erişim yolu) kataloğu üretir.
Starter bu katalogları runtime reflection kullanmadan bir araya getirir:

```powershell
Invoke-RestMethod http://127.0.0.1:8091/actuator/cachedb
```

Yanıt; tanımlı repository ve route sayılarını, route türlerini, hızlı erişim
route'larının doldurma stratejilerini, en fazla 250 route ayrıntısını, en fazla
100 zamanlanmış ön yükleme ayrıntısını ve kesilme işaretlerini içerir. Micrometer
tarafında `cachedb.repositories.declared`, `cachedb.routes.declared`,
`cachedb.routes.hot.population{strategy=...}` ve `cachedb.scheduled.warm.*`
ölçümleri bulunur. Strateji etiketi dört sabit değerle sınırlıdır; route,
customer ve tenant adları metric etiketi yapılmaz. Katalog yalnızca derlenen
yüzeyi kanıtlar; coverage, veri eşitliği, gecikme, bellek ve SQL kalıcılığı ayrı
production kapılarıdır.

## Kullanım Senaryosuna Göre Ayar

Ayar kararına tablo boyutundan değil, ölçülmüş yol ihtiyacından başla.

| Senaryo | Aktif veri politikası | Okuma modeli | Başlangıç sınırı | Aktif setin dışında |
| --- | --- | --- | --- | --- |
| Müşteri sipariş zaman çizelgesi | Son 90 gün **veya** aktif sipariş durumları | Müşteri başına `OrderSummary` | Sayfa `100`, pencere `1.000`, yol bütçesi `16 MiB` | Sınırlı `archive` source route |
| Ürün kataloğu | Aktif ürünler **veya** stokta/düşük stokta olanlar | `ProductAvailability` | Entity sınırı `25.000`, sayfa `100` | Pasif ürün source route'u |
| Destek operasyonu | Son 30 günde güncellenen **veya** `OPEN/PENDING/ESCALATED` kayıtlar | Küçük satırlar için entity | Entity sınırı `50.000`, sayfa `50` | Gerektiğinde açık ticket-history SQL yolu |
| Lojistik kontrol paneli | Son 14 günde güncellenen **veya** aktif/istisna durumları | `ShipmentSummary` | Entity sınırı `150.000`, yol penceresi `2.000-10.000` | Teslim edilmiş gönderi source route'u |
| Rapor çalıştırma | `QUEUED/RUNNING/FAILED` veya son 24 saat | Küçük rapor işi entity'si | Entity sınırı `5.000`, sayfa `50` | Tamamlanan rapor geçmişi PostgreSQL'de |
| Güvenlik denetimi | Son 24 saatteki önemli olaylar | Küçük ve sınırlı entity listesi | Entity sınırı `2.000`, read admission kapalı | Tam denetim arşivi PostgreSQL'de |

Örnek, çözümlenmiş çalışma zamanı ayarlarını API üzerinden gösterir:

```powershell
Invoke-RestMethod http://127.0.0.1:8091/api/tuning
Invoke-RestMethod http://127.0.0.1:8091/api/tuning/profiles
```

[`SampleCacheDbTuningConfig`](src/main/java/com/example/cachedb/sample/config/SampleCacheDbTuningConfig.java)
içindeki temel kontroller:

| Kontrol | Örnek değer | Neden var? |
| --- | ---: | --- |
| `maxEntityQueryLimit` | `250` | Geniş entity materialization işlemini durdurur |
| `maxProjectionQueryLimit` | `1.000` | Küçük projection satırları için daha geniş pencereye izin verir |
| `maxQueryLoadRows` | `1.000` | Kayıtlı source yüklemeyi sınırlar |
| `queryTimeoutSeconds` | `15` | Source okumaya zaman sınırı koyar |
| `workerThreads` | `2` | Eş zamanlı SQL flush baskısını sınırlar |
| `batchSize` / `maxFlushBatchSize` | `128` | Sınırsız batch oluşturmadan SQL round-trip sayısını azaltır |
| Redis uyarı / kritik eşikleri | `%75` / `%88` | Redis maxmemory değerine ulaşmadan backpressure uygular |
| Beklenen eviction policy | `noeviction` | Redis'in koordinasyon veya yazma durumunu sessizce atmasını engeller |

Bu değerleri doğrudan canlı ortama kopyalama. Serileştirilmiş veri yükü boyutunu,
projection/indeks maliyetini, en yüksek eş zamanlı yol trafiğini, SQL flush
gecikmesini ve yeniden hazırlama/failover sırasında gereken Redis boşluğunu ölç.

## API Kataloğu

| Alan | Örnek endpoint'ler | Veri yolu |
| --- | --- | --- |
| Sağlık ve operasyon | `GET /actuator/health/readiness`, `GET /api/tuning`, `GET /api/warm/schedules` | Çalışma zamanı telemetry'si |
| Demo hazırlığı | `POST /api/demo/seed`, `GET /api/warm/jobs/{jobId}` | Dağıtık arka plan işleri |
| Müşteri | `POST /api/customers`, `GET /api/customers/{id}`, `GET /api/customers/{id}/orders` | Komut, entity detayı, projection listesi |
| Sipariş komutları | `POST /api/orders`, `PATCH /api/orders/{id}/status`, `DELETE /api/orders/{id}` | Redis öncelikli write-behind komutları |
| Sipariş okumaları | `GET /api/orders/{id}`, `GET /api/orders/high-value`, `GET /api/orders/archive` | Entity, ranked projection, PostgreSQL source route |
| Ürün | `GET /api/products/active`, `GET /api/products/low-stock`, `PATCH /api/products/{id}/stock` | Projection okumaları ve komut |
| Gönderi | `GET /api/shipments/active`, `GET /api/shipments/exceptions`, `GET /api/shipments/archive` | Projection ve PostgreSQL source route'ları |
| Destek | `GET /api/tickets/open`, `POST /api/tickets`, `PATCH /api/tickets/{id}/status` | Sınırlı entity okuması ve komutlar |
| Raporlama | `GET /api/reports/jobs/live`, `GET /api/reports/audit/security`, `GET /api/reports/audit/archive` | Aktif kayıtlar ve kalıcı arşiv |
| Paneller | `GET /api/dashboard/commerce`, `GET /api/dashboard/operations` | Ekrana göre şekillendirilmiş Redis verisi |
| Katalog snapshot'ları | `GET /api/catalog/items/{id}`, `GET /api/catalog/items?ids=...`, `GET /api/catalog/categories/{category}` | Tipli Redis okumaları ve cursor ile sayfalama |
| Katalog hazırlığı ve aktarım | `POST /api/catalog/refresh`, `POST /api/catalog/imports` | Sınırlı demo işleri; iş sonucunu ve SQL kalıcılığını ayrı kontrol edin |
| Redis'e hazırlama | `POST /api/warm/customers/active`, `/orders/customer/{id}`, `/orders/{id}/lines`, `/orders/high-value`, `/orders/highlighted`, `/products/active`, `/products/low-stock`, `/tickets/open`, `/shipments/active`, `/shipments/customer/{id}`, `/shipments/exceptions`, `/shipments/{id}/events`, `/reports/live`, `/reports/type/{type}`, `/audit/security` | Sınırlı PostgreSQL-Redis işleri; her gönderimden sonra `/api/warm/jobs/{jobId}` durumunu izle |

İstek sınırları doğrulanır. Sınırı aşan değerler sessizce küçültülmek yerine
`400 Bad Request` döner. Kuyruk doluluğu `429 Too Many Requests`; optimistic
conflict ve henüz kalıcılaşmamış ana kayıt ise `409 Conflict` üretir.

## Postman

Şu koleksiyonu içe aktar:

```text
postman/cache-database-postgresql-sample.postman_collection.json
```

Klasörleri şu sırayla çalıştır:

1. Readiness kontrolünü çalıştır, demo seed işini gönder ve `Latest Background
   Job Status` isteği `COMPLETED` gösterene kadar tekrarla.
2. Her iş alanı klasöründe hızlı erişim listesinden önce ilgili `Warm ...`
   isteğini çalıştır.
3. Her `202 Accepted` yanıtından sonra son iş `COMPLETED` olana kadar durum
   isteğini tekrarla.
4. Hızlı erişim route'unu çağır; klasörde karşılığı varsa sınırlı source/arşiv
   route'u ile sonucu karşılaştır.
5. Dashboard klasörünü, kullandığı bütün alt route'lar hazırlandıktan sonra çalıştır.
6. Pencere veya pool değerini değiştirmeden önce tuning ve periyodik warm
   durumunu incele.

Snapshot ve atomik aktarım için ayrı hazırlanan
`postman/cache-database-postgresql-catalog.postman_collection.json` koleksiyonunu
kullanın. Bu koleksiyon kendi dört ürününü oluşturur ve sonuçları doğrular.
Otomatik çalıştırıcı, API/worker kurulumu, tekrar gönderim kuralları ve beklenen
sonuçlar için [çalışan senaryoları](#çalışan-014-özellik-senaryoları) izleyin.

## Yük Testi

Seed ve warm tamamlandıktan sonra:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-load-test.ps1 `
  -RouteProfile hot-timeline `
  -Concurrency 8 `
  -DurationSeconds 20 `
  -SeedCustomers 20 `
  -OrdersPerCustomer 40 `
  -WarmCustomers 20 `
  -WarmLimit 100 `
  -MaxP95Millis 250
```

Bu komut yerel bir regresyon kapısıdır; production kapasite sonucu değildir.
Production ölçümü gerçek Redis/PostgreSQL gecikmesi, Kubernetes kaynak
sınırları, gerçek payload ve beklenen eş zamanlılıkla yapılmalıdır.

## PostgreSQL Canlı Ortam Notları

- Her source route predicate'i ve deterministik sıralama son eki için uygun
  indeks oluştur.
- Tüm pod'ların toplamını düşünerek HikariCP bağlantı sayısını PostgreSQL
  bütçesinin altında tut.
- Write-behind batch boyutunu WAL baskısı ve lock süresine göre ayarla.
- Warm, arşiv ve geçiş sorgularında statement timeout kullan.
- Aynı tablolara başka uygulamalar da yazıyorsa outbox/CDC ekle.
- Yedekleme, geri yükleme, Redis kaybı/yeniden hazırlama ve uygulamanın geri dönüş yollarını
  kanıtla.
- Operasyonel yol Redis'ten çalışsa bile kalıcı doğruluk kaynağının PostgreSQL
  olduğunu koru.

## Production Sertifikası

Framework deposundaki testler CacheDB'nin kendi Docker ve provider davranışını
kanıtlar. Uygulamanın rotaları ile gerçek staging topolojisi ayrıca
kanıtlanmalıdır. Rota kapsamı, veri eşitliği, bellek, failover, canary ve geri
dönüş kanıtlarını `cachedb-certification/` altında topladıktan sonra çalıştır:

```powershell
mvn verify -Pproduction-certification
```

Eksik rota, başka commit veya ortama ait kanıt, veri eşitliği sorunu, aşılmış
bellek bütçesi ya da eksik failover/geri dönüş provası build'i durdurur.
Paylaşılabilir sonuç `target/cachedb-production-certification.md` altında
oluşur. Ayrıntılı sözleşme için
[production sertifikası rehberini](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/production-sertifikasi.md)
kullan. Örnek kanıtları başarılı gibi işaretleme.

## Canlı Ortam Kontrol Listesi

- [ ] Her operasyonel endpoint; komut, aktif entity, projection veya source
  route olarak sınıflandırıldı.
- [ ] Her aktif yolun sayfa sınırı, aktif penceresi, bellek bütçesi, sıralaması
  ve coverage kapsamı tanımlandı.
- [ ] İlişki yoğun ve global sıralı ekranlar projection kullanıyor.
- [ ] Warm/backfill sınırlı, kaldığı yerden devam edebilir, izlenebilir ve Redis
  kaybından sonra test edilmiş durumda.
- [ ] Source route'ların uygun indeksleri, timeout ve maksimum satır sınırı var.
- [ ] Çağıran servisler `202 Accepted` kalıcılık anlamını biliyor.
- [ ] Redis için açık `maxmemory`, `noeviction`, alarm ve kapasite boşluğu var.
- [ ] PostgreSQL ve HikariCP bağlantı bütçesi pod başına ve toplam replica sayısı
  için hesaplandı.
- [ ] Çok pod'lu periyodik warm ve yarım kalan işin başka pod tarafından alınması
  test edildi.
- [ ] CacheDB dışındaki veritabanı yazıları outbox/CDC veya açık bir uzlaştırma
  kararıyla kapsandı.
- [ ] Yönetim endpoint'leri kapalı veya iç gateway arkasında korunuyor.
- [ ] Geçiş öncesi veri eşitliği, gecikme, canary, geri dönüş ve recovery kanıtı
  kaydedildi.

## Sorun Giderme

| Belirti | Olası neden | Çözüm |
| --- | --- | --- |
| `/api/demo/seed` veya `/api/warm/**` için `404` | Uygulama `demo` profili olmadan açıldı | `SPRING_PROFILES_ACTIVE=demo` tanımlayıp yeniden başlat |
| CacheDB artifact'leri için `404` alınıyor | İstenen sürüm yayımlanmamış veya genel Maven deposu eksik | Yayımlanmış kararlı sürümü kullan ve iki repository bölümüne de `https://esasmer-dou.github.io/cache-database/maven2` adresini ekle |
| Bağımlılıklar çözülüyor ancak `cachedb-maven-plugin` bulunamıyor | `pluginRepositories` tanımı yok | Aynı genel CacheDB adresini `pluginRepositories` altına ekle |
| `production-certification` başarısız oluyor | Rota kanıtı eksik, eski veya başka commit/ortama ait | Üretilen raporu oku, gerçek staging kanıtını yenile ve profili yeniden çalıştır |
| Aktif route `503` döndürüyor, arşiv route'u satır getiriyor | Redis route'u hazırlanmadı, coverage süresi doldu veya kapsam farklı | Dry-run yap, aynı route/scope'u hazırla, `COMPLETED` durumunu bekle ve coverage kaydını incele |
| Detay yolu verinin hazır olmadığını söylüyor | Entity payload'ı aktif setin dışında | O detay kapsamı için entity warm et veya sınırlı source-detail yolu ekle |
| Ana kaydı yazdıktan sonra `409 Conflict` | Ana kayıt henüz kalıcı değil veya optimistic version değişti | `Retry-After` değerine uy, write-behind durumunu kontrol et, idempotent retry yap |
| `429 Too Many Requests` | Sınırlı iş kuyruğu veya backpressure koruması devrede | Üretim hızını düşür, Redis ve write-behind telemetry'sini incele |
| Readiness `DOWN` | Redis, PostgreSQL, dead-letter, recovery veya backlog koşulu başarısız | Readiness ayrıntısını ve logları incele; trafiği henüz yönlendirme |
| Redis bellek uyarısı veriyor | Aktif set, projection/indeks maliyeti veya backlog bütçeyi aştı | Yeni admission'ı yavaşlat, keyspace'i ölç, pencereleri küçült veya kapasite ekle |

## İlgili Dokümanlar

- [Ana CacheDB README](https://github.com/esasmer-dou/cache-database/blob/main/tr/README.md)
- [Deklaratif repository kullanımı](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/deklaratif-repositoryler.md)
- [Başlangıç rehberi](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/getting-started.md)
- [Periyodik warm ve uzlaştırma](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/periodik-warm.md)
- [Production tuning](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/production-tuning-rehberi.md)
- [Kullanım senaryosu örnekleri](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/use-case-examples.md)
- [Veritabanı provider SPI](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/veritabani-provider-spi.md)
- [Production reçeteleri](https://github.com/esasmer-dou/cache-database/blob/main/tr/docs/production-recipes.md)

<!-- BEGIN COMPILED FEATURE EXAMPLES -->
## Çalışan 0.14 Özellik Senaryoları

Bu senaryolar yayımlanmış **CacheDB 0.14.0** paketini kullanır. Mevcut sipariş,
destek, lojistik ve arşiv yolları değişmez. Aşağıdaki sınıflar yalnızca anlatım
için yazılmış taslaklar değildir; üç sağlayıcıda da derlenen ve test edilen
örnek kaynak kodlarıdır. Her tam Java bloğunu, üstünde belirtilen dosyaya yerleştirin.

| Gerçek ihtiyaç | Kullanılan yapı | Gösterdiği davranış |
| --- | --- | --- |
| Mağaza ekranında küçük ve hızlı bir stok kartı göstermek | Üretilmiş JSON codec'i ve tipli snapshot | Veriyi SQL'den açıkça hazırlama, yalnızca Redis'ten okuma, Türkçe metin ve ondalık sayı doğruluğu |
| Tedarikçinin aynı yeni ürün listesini iki kez göndermesi | Atomik katalog aktarımı ve sabit batch kimliği | Redis'in tüm grubu birlikte kabul etmesi, tekrar gönderim güvenliği ve ayrı SQL kalıcılığı kontrolü |
| Bir kategorinin stoğu değişirken diğerinde sayfalamaya devam etmek | Kapsamı belirlenmiş snapshot yenileme | Etkilenmeyen kategorinin cursor'ı korunur; değişen kategoride sayfalama yeniden başlar |
| API trafiği ile SQL'e yazma kapasitesini ayrı ölçeklemek | `client` / `worker` profilleri ve Compose | API işi kabul eder; ayrı işleyiciler veriyi kalıcılaştırır |
| Fiziksel silmeden sonra eski bir yazmanın gecikerek gelmesi | Geçici veritabanında sağlayıcı entegrasyon testi | Eski UPSERT elenir; iş verisi ile sürüm koruması birlikte geri alınır |



### 1. Jackson Eşlemesi Olmadan Stok Kartı Hazırlama

`CatalogCard`, entity'nin tamamı değil, ekranın ihtiyaç duyduğu küçük yanıt
modelidir. Annotation processor, `CatalogCardJsonCodec` sınıfını üretir; bu sınıfı
elle oluşturmayın ve Git'e eklemeyin. Spring MVC'nin normal HTTP JSON işlemesi
değişmez. Üretilmiş codec yalnızca snapshot hazırlama ve tipli snapshot okuma
yolunda açıkça seçilir.

`src/main/java/com/example/cachedb/sample/catalog/CatalogCard.java`:

```java
package com.example.cachedb.sample.catalog;

import com.example.cachedb.sample.domain.ProductEntity;
import com.reactor.cachedb.annotations.CacheJsonRecord;

import java.math.BigDecimal;

@CacheJsonRecord
public record CatalogCard(
        long productId,
        String sku,
        String name,
        String category,
        int available,
        BigDecimal unitPrice) {
    public static CatalogCard from(ProductEntity product) {
        return new CatalogCard(
                product.productId,
                product.sku,
                product.productName,
                product.category,
                Math.max(0, product.stockQuantity - product.reservedQuantity),
                product.unitPrice);
    }
}
```

Plan SQL'den yalnızca aktif ürünleri okur. Bütün kök listesini bellekte tutmak
yerine kayıtları sırayla hazırlar. Sıralı indeks kategoriye göre bölünür; kısmi
yenileme için parametreli SQL kapsamı açıkça tanımlanır. Kullanılabilir stok
küçükten büyüğe sıralanır. Eşit stokta ürün kimliğinin metinsel sırası kullanılır.

`src/main/java/com/example/cachedb/sample/catalog/CatalogConfiguration.java`:

```java
package com.example.cachedb.sample.catalog;

import com.example.cachedb.sample.domain.ProductEntity;
import com.example.cachedb.sample.domain.ProductEntityCacheBinding;
import com.reactor.cachedb.spring.boot.snapshot.*;
import com.reactor.cachedb.starter.CacheDatabase;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class CatalogConfiguration {
    public static final String PLAN = "stock-catalog";

    @Bean
    SnapshotPlan<ProductEntity, CatalogCard> stockCatalog() {
        var source =
                SnapshotSource.entity("products", ProductEntityCacheBinding.SOURCE)
                        .where(SnapshotPredicate.eq("active_status", "ACTIVE"));
        return SnapshotPlan.streaming(
                        PLAN,
                        source,
                        row -> row.productId.toString(),
                        CatalogCard.class,
                        row -> List.of(CatalogCard.from(row)))
                .indexed(
                        SnapshotIndexSpec.of(
                                row -> row.category,
                                row -> Math.max(0, row.stockQuantity - row.reservedQuantity)))
                .scoped(
                        SnapshotRootScope.builder()
                                .source(
                                        source,
                                        ids ->
                                                SnapshotPredicate.inValues(
                                                        "product_id",
                                                        ids.stream().map(Long::valueOf).toList()))
                                .build())
                .withJsonCodec(CatalogCardJsonCodec.INSTANCE);
    }

    @Bean
    TypedSnapshotRepository<Long, CatalogCard> stockCatalogRepository(SnapshotJobs jobs) {
        return jobs.typedRepository(PLAN, CatalogCard.class, Object::toString);
    }

    @Bean
    CatalogImportService catalogImports(CacheDatabase database) {
        return new CatalogImportService(database::writeBatch, database::awaitDurable);
    }
}
```

Örneğin POM dosyasında Gson **2.13.2** açıkça sabitlenmiştir. Aksi hâlde Spring
Boot parent, gerekli `Strictness` sınıfını içermeyen eski Gson sürümünü seçebilir.
POM'da `cachedb-json`, annotation bağımlılığı ve 0.14.0 processor birlikte bulunur.
Gson sürüm ayarını kaldırmayın; farklı framework sürümlerini karıştırmayın.

0.14.0 BOM ve annotation processor ayarları mevcutsa, derlenen örneğin ek çalışma
zamanı bağımlılıkları şunlardır:

```xml
<dependency>
    <groupId>com.reactor.cachedb</groupId>
    <artifactId>cachedb-json</artifactId>
</dependency>
<dependency>
    <groupId>com.google.code.gson</groupId>
    <artifactId>gson</artifactId>
    <version>2.13.2</version>
</dependency>
```

Hızlı Başlangıç ile SQL şemasını kurup demo API'yi başlattıktan sonra:

```powershell
$base = 'http://127.0.0.1:8091'
$job = Invoke-RestMethod "$base/api/catalog/refresh" -Method Post -ContentType 'application/json' -Body '{"ids":[]}'
$deadline = [DateTime]::UtcNow.AddSeconds(30)
do {
    Start-Sleep -Milliseconds 200
    $state = Invoke-RestMethod "$base/api/warm/jobs/$($job.jobId)"
} while ($state.status -notin @('COMPLETED', 'FAILED') -and [DateTime]::UtcNow -lt $deadline)
if ($state.status -ne 'COMPLETED') { throw ($state | ConvertTo-Json -Depth 10) }
$state
Invoke-RestMethod "$base/api/catalog/items/10"
Invoke-RestMethod "$base/api/catalog/items?ids=10,11"
Invoke-RestMethod "$base/api/catalog/categories/electronics?limit=10"
```

Kaynak verinizde bulunan bir kategori kullanın; kategori adı birebir eşleşir.
Demo ürünlerin kategorilerini kaynak veya ürün API'lerinden görebilirsiniz.
`202`, işin kuyruğa alındığını anlatır; snapshot'ın hazır olduğu anlamına gelmez.
Başarılı hazırlık, yeni yayın neslini atomik olarak etkinleştirir. Hazırlık
başarısızsa önceki nesil kullanılmaya devam eder.

| İşlem | Veritabanı ve önbellek davranışı |
| --- | --- |
| `POST /api/catalog/refresh`, gövde `{"ids":[]}` | Sınırları belirlenmiş SQL hazırlığı yapar; Redis'te yeni nesli yayımlar |
| Aynı POST, gövde `{"ids":[10]}` | Yalnızca seçilen kimlikleri parametreli SQL ile yeniler; etkilenen bölümleri günceller |
| `GET /api/catalog/items/10` | Yalnızca Redis; FOUND/STALE_WARNING için `200`, ABSENT_IN_GENERATION için `404`, SNAPSHOT_UNAVAILABLE için `503` |
| `GET /api/catalog/items?ids=10,11` | En fazla 100 kimlik, aynı nesilden okuma; `200` içinde her kimliğin durumu ayrı belirtilir |
| Kategori GET | En fazla 100 satır ve kapalı cursor; hazırlanmamış katalog boş sayfa döndürebilir, kullanılabilirliği tekil sorguyla kontrol edin |

GET isteği veri hazırlamaz ve kendiliğinden SQL'e geçmez. Entity veya projection
güncellendiğinde snapshot **otomatik güncellenmez**. Açık yenileme yapın veya
zamanlamayı etkinleştirin. `STALE_WARNING` okunabilir bir güncellik uyarısıdır.
Aktif ürün kataloğunda bulunmayan kayıt, SQL'de hiç ürün bulunmadığını kanıtlamaz.

Ürün 10'un SQL'e yazıldığı doğrulandıktan sonra `{"ids":[10]}` gönderin.
Etkilenmeyen kategorinin cursor'ı geçerli kalır. Değişen kategorinin eski cursor'ı
`400` üretir; o kategorinin ilk sayfasından yeniden başlayın. Tam yenileme,
önceki nesle ait bütün cursor'ları geçersiz kılar.

### 2. Atomik Tedarikçi Aktarımı, Güvenli Tekrar ve Kalıcılık

Bu endpoint, **yeni ürünler için tasarlanmış, sınırlı bir aktarım örneğidir**;
genel amaçlı Excel güncelleme mekanizması değildir. Bu akışa ait yeni kimlik ve
SKU'lar kullanın. Beklenen Redis sürümü `0`, sürümü bulunan mevcut bir entity
nedeniyle grubun kısmen değişmesini engeller. Bu kontrol SQL unique constraint'inin
veya kaybolmuş sürüm sayaçlarının kurtarılmasının yerine geçmez.

**Veritabanı düzeyinde yalnızca ekleme garantisi vermez.** Aynı kimlik yalnızca
SQL'de bulunuyorsa Redis sürümü `0` bunu anlayamaz; write-behind UPSERT, SQL'deki
mevcut kaydı güncelleyebilir. Primary key kısıtı UPSERT işlemini engellemez.
Demo için yeni bir kimlik/SKU aralığı ayırın; mevcut kataloğun üzerine aktarım
yapmayın. Canlı ortamda yalnızca ekleme isteniyorsa bunu ayrı SQL komutu ve
kısıt sözleşmesiyle sağlayın; Redis'te kayıt bulunmamasına dayanmayın.

`CatalogImportItem`, tam kaydı ve kaynaktan gelen sabit `updatedAt` değerini taşır.
Zamanı, fiyatı, kayıt sırasını veya kimliği değiştirmek batch içeriğini değiştirir.
Aynı teslimatı yeniden gönderirken `Instant.now()` ile yeni zaman üretmeyin.

`src/main/java/com/example/cachedb/sample/catalog/CatalogImportItem.java`:

```java
package com.example.cachedb.sample.catalog;

import com.example.cachedb.sample.domain.ProductEntity;

import java.math.BigDecimal;

/** Complete insert-only source row. updatedAt comes from the source, never from a retry clock. */
public record CatalogImportItem(
        long productId,
        String sku,
        String name,
        String category,
        BigDecimal unitPrice,
        int stock,
        int reserved,
        long updatedAt) {
    public CatalogImportItem {
        if (productId < 1
                || unitPrice == null
                || unitPrice.signum() < 0
                || unitPrice.precision() - unitPrice.scale() > 15
                || unitPrice.scale() > 4
                || stock < 0
                || reserved < 0
                || reserved > stock
                || updatedAt < 1) {
            throw new IllegalArgumentException(
                    "Invalid complete catalog row or NUMERIC(19,4) price");
        }
        text(sku, 64, "sku");
        text(name, 160, "name");
        text(category, 64, "category");
    }

    private static void text(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " must contain 1.." + maximum + " characters");
        }
    }

    public ProductEntity entity() {
        var row = new ProductEntity();
        row.productId = productId;
        row.sku = sku;
        row.productName = name;
        row.category = category;
        row.activeStatus = "ACTIVE";
        row.unitPrice = unitPrice;
        row.stockQuantity = stock;
        row.reservedQuantity = reserved;
        row.stockStatus = stock - reserved == 0 ? "OUT_OF_STOCK" : "IN_STOCK";
        row.updatedAt = updatedAt;
        return row;
    }
}
```

`src/main/java/com/example/cachedb/sample/catalog/CatalogImportCommand.java`:

```java
package com.example.cachedb.sample.catalog;

import java.util.HashSet;
import java.util.List;

public record CatalogImportCommand(String batchId, List<CatalogImportItem> items) {
    public CatalogImportCommand {
        if (batchId == null || !batchId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException(
                    "batchId must contain 1..64 letters, digits, underscores or hyphens");
        }
        if (items == null || items.isEmpty() || items.size() > 25) {
            throw new IllegalArgumentException("A catalog import requires 1..25 complete rows");
        }
        items = List.copyOf(items);
        var ids = new HashSet<Long>();
        var skus = new HashSet<String>();
        for (var item : items) {
            if (!ids.add(item.productId()) || !skus.add(item.sku())) {
                throw new IllegalArgumentException("Duplicate product IDs or SKUs in one batch");
            }
        }
    }
}
```

Uygulama servisi bellek içi test oturumuyla da doğrudan sınanabilir. İki işlev
referansını HTTP istemcisi değil, `CatalogConfiguration` sağlar. Süre sınırlı
kalıcılık beklemesini arka plan işleyicisi yapar; HTTP isteği SQL için üç saniye
boyunca açık tutulmaz.

`src/main/java/com/example/cachedb/sample/catalog/CatalogImportService.java`:

```java
package com.example.cachedb.sample.catalog;

import com.reactor.cachedb.core.api.CacheWriteBatch;
import com.reactor.cachedb.core.api.CacheWriteBatchReceipt;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;

/** Executes on a bounded background worker, not on the HTTP request thread. */
public final class CatalogImportService {
    private final Function<String, CacheWriteBatch> batches;
    private final BiPredicate<CacheWriteBatchReceipt, Duration> durability;

    public CatalogImportService(
            Function<String, CacheWriteBatch> batches,
            BiPredicate<CacheWriteBatchReceipt, Duration> durability) {
        this.batches = Objects.requireNonNull(batches);
        this.durability = Objects.requireNonNull(durability);
    }

    public Result execute(CatalogImportCommand command) {
        var batch = batches.apply("sample-catalog-" + command.batchId());
        for (var item : command.items()) {
            batch.put(item.entity(), 0L);
        }
        var receipt = batch.commit();
        return new Result(
                "ACCEPTED", receipt, durability.test(receipt, Duration.ofSeconds(3)), null);
    }

    public record Result(
            String outcome, CacheWriteBatchReceipt receipt, boolean durable, String message) {}
}
```

```powershell
$body = @'
{"batchId":"supplier-delivery-001","items":[{"productId":9200001,"sku":"SUP-9200001","name":"İğne çelik","category":"tools","unitPrice":12.3456,"stock":8,"reserved":2,"updatedAt":1700000000},{"productId":9200002,"sku":"SUP-9200002","name":"Tool B","category":"tools","unitPrice":15.0000,"stock":12,"reserved":0,"updatedAt":1700000000}]}
'@
$job = Invoke-RestMethod "$base/api/catalog/imports" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
$deadline = [DateTime]::UtcNow.AddSeconds(30)
do {
    Start-Sleep -Milliseconds 200
    $state = Invoke-RestMethod "$base/api/warm/jobs/$($job.jobId)"
} while ($state.status -notin @('COMPLETED', 'FAILED') -and [DateTime]::UtcNow -lt $deadline)
if ($state.status -ne 'COMPLETED') { throw ($state | ConvertTo-Json -Depth 10) }
$state.result
# Aynı receipt'in döndüğünü görmek için aynı $body değerini yeniden gönderin.
```

| Sonuç | Anlamı ve yapılacak işlem |
| --- | --- |
| `202` | İş kuyruğa kabul edildi; SQL commit'i anlamına gelmez |
| COMPLETED ve `result.durable=true` | Atomik grubun SQL'de kalıcılaştığı gözlemlendi |
| COMPLETED, `result.outcome=ACCEPTED` ve `result.durable=false` | Redis grubu kabul etti, fakat üç saniyede SQL kalıcılığı doğrulanamadı; kuyruk/DLQ durumunu inceleyin, gerekiyorsa yalnızca aynı teslimatı yeniden gönderin |
| COMPLETED, `result.outcome=REJECTED` ve `Batch id reused` | Aynı kimlikle farklı içerik gönderildi; yeni batch uygulanmadı, kesin ret sonucu gereksiz yere tekrar denenmez |
| COMPLETED, `result.outcome=REJECTED` ve optimistic version conflict | Üyelerden biri yeni/sürümü sıfır bir entity değildi; Redis gruptaki hiçbir üyeyi değiştirmez |
| `400` / `413` | Geçersiz istek / 64 KiB'den büyük gövde; komut kuyruğa alınmaz |

`COMPLETED`, işin yaşam döngüsünü anlatır. Teslimatı kabul edilmiş ve kalıcı
saymadan önce `outcome` ve `durable` alanlarını kontrol edin. Beklenmeyen bağlantı
hataları framework'ün sınırlı retry yolunu izler ve FAILED ile sonuçlanabilir.

En fazla **25 kayıt**, **64 KiB HTTP gövdesi**, NUMERIC(19,4)'e sığan fiyat ve
negatif olmayan stok/rezervasyon kabul edilir. SQL unique/constraint hataları yine
operasyonel takip gerektirir. Redis kabulü ile sonraki SQL transaction'ı **tek bir
dağıtık transaction değildir**: SQL aktarımı başarısızken kabul edilmiş Redis
değerleri görülebilir. Aynı içeriğin tekrarı güvenlidir; fakat hatalı SQL kaydını
onarmaz ve batch receipt'ini bir denetim kaydı saklama politikasına dönüştürmez.

Farklı batch kimlikleri Redis'te koordinasyon/receipt bilgisi biriktirir. Bu alan,
TTL ile yönetilen entity penceresi değildir. Büyümeyi ölçüp bütçelendirin; eski
teslimatlar için tekrar koruması sözü verirken receipt'leri silmeyin. Üretim
saklama politikası, açık bir tekrar gönderim süresi ve kontrollü kurtarma/geçiş
planı gerektirir.

### 3. API ve İşleyici Konteynerlerini Ayırma

`mvn clean verify -Pprovider-integration` ile derleyin. SQL'i hazırlamak için Hızlı
Başlangıç adımlarını bir kez tamamlayın, ardından API portunu boşaltmak için yerel
Java sürecini durdurun. Aynı Redis ve SQL verisini kullanın; sürüm sayaçlarını
sıfırlamayın.

```powershell
docker compose -f docker-compose.yml -f docker-compose.roles.yml up -d --scale worker=2
docker compose -f docker-compose.yml -f docker-compose.roles.yml logs worker
```

Aynı JAR, API'de `demo,client`, işleyicilerde `demo,worker` profiliyle çalışır.
Worker profili `web-application-type=none` kullanır; REST dinleyicisi açmaz.
Entity tanımları, JSON codec'leri, Redis, SQL ve iş tanımları ortaktır.
0.14.0'da worker otomatik kurulumu, client için iş yürütücüsü oluşturmaz.
Örnek bu nedenle public API üzerinden yalnızca kuyruğa gönderim yapan bean'i
açıkça tanımlar. Sondaki `false`, iş tüketimini kapatır. Client profilinde demo
iş endpoint'lerini açıyorsanız bu tanımı kaldırmayın.

`src/main/java/com/example/cachedb/sample/catalog/ClientJobSubmissionConfiguration.java`:

```java
package com.example.cachedb.sample.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactor.cachedb.spring.boot.CacheDbSpringProperties;
import com.reactor.cachedb.spring.boot.CacheDistributedJobExecutor;
import com.reactor.cachedb.spring.boot.CacheDistributedJobHandler;
import com.reactor.cachedb.starter.CacheDatabase;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 0.14.0 auto-configures job executors on workers; this explicit client bean only publishes. */
@Configuration(proxyBeanMethods = false)
@Profile("client")
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
public class ClientJobSubmissionConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(CacheDistributedJobExecutor.class)
    CacheDistributedJobExecutor clientJobPublisher(
            CacheDatabase database,
            ObjectMapper mapper,
            CacheDbSpringProperties properties,
            ObjectProvider<CacheDistributedJobHandler<?>> handlers) {
        return new CacheDistributedJobExecutor(
                database.backgroundRedisOperations(),
                mapper,
                database.instanceId(),
                properties.getJobs(),
                handlers.orderedStream().toList(),
                false);
    }
}
```

Bu yerel örnekte her uygulama konteyneri **2 CPU / 768 MiB** ile sınırlıdır.
Bu değerler ölçülmüş üretim kapasitesi önerisi değil, örnek çalışma sınırlarıdır.

İşleyiciler durduğunda API işi kabul edebilir, fakat işler ve SQL yazmaları bekler.
Bir işleyiciyi yeniden başlatın; kurtarma amacıyla farklı batch kimliği üretmeyin.
Üretim yükseltmesinde bekleyen işleri boşaltmadan işleyicileri durdurmayın.
Buradaki Redis geçici yerel test altyapısıdır; HA depolama örneği değildir.

Periyodik stok hazırlığı için worker konteynerlerinde
`SAMPLE_CATALOG_SCHEDULE_ENABLED=true` ayarlayın. Framework hazırlığı yenilenebilir
Redis lease'i ile koordine eder; aynı lease altında başka worker eş zamanlı yayın
yapamaz. Varsayılan aralık beş dakikadır; dış SQL değişikliğinin anında
yansıyacağı sözü verilmez.

| Ayar | Örnek değer | Amacı |
| --- | --- | --- |
| Kaynak kayıt/bayt sınırı | 10.000 / 16 MB | Beklenmeyen geniş okumayı JVM belleğini tüketmeden reddetmek |
| JDBC fetch / hazırlık batch'i | 128 / 100 | Sürücü ve hazırlık işini sınırlamak |
| Sayfa/toplu yanıt bayt hedefi | 1 MB | Yanıt boyutunu sınırlamak; toplam Redis bellek kotası değildir |
| Uyarı / azami yaş | 10 / 30 dakika | Güncellik uyarısı ile kullanım süresinin bitmesini ayırmak |
| Hazırlık / toplam snapshot zaman aşımı | 20 / 30 saniye | İşi sınırlayıp yayına zaman bırakmak |
| Lease süresi | 45 saniye | Pod'lar arasında yenilenebilir sahiplik |

### 4. Güvenlik Kontrollerini Yeniden Çalıştırma

```powershell
mvn clean verify -Pprovider-integration
```

`CatalogFeatureTest`, üretilen repository'nin `InMemoryCacheDatabase` alan
constructor'ını, üretilmiş codec'i ve `InMemorySnapshotRepository` sınıfını kullanır.
Docker açmadan tekrar gönderimi, çakışmada geri almayı, Türkçe metin/fiyat
doğruluğunu, okuma durumlarını ve bölüm cursor'larını kontrol eder. Test aracındaki
`markDurable` **yalnızca test onayıdır**; gerçek SQL commit'i değildir.

`src/test/java/com/example/cachedb/sample/catalog/CatalogFeatureTest.java`:

```java
package com.example.cachedb.sample.catalog;

import static org.junit.jupiter.api.Assertions.*;

import com.example.cachedb.sample.domain.ProductEntityCacheBinding;
import com.example.cachedb.sample.repository.ProductRepositoryCacheDbImplementation;
import com.example.cachedb.sample.web.CatalogController;
import com.reactor.cachedb.core.model.OptimisticWriteConflictException;
import com.reactor.cachedb.core.repository.WindowRequest;
import com.reactor.cachedb.core.testing.InMemoryCacheDatabase;
import com.reactor.cachedb.spring.boot.snapshot.*;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

class CatalogFeatureTest {
    private static final class TestClock extends java.time.Clock {
        long now = 1_700_000_000_000L;

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            if (!getZone().equals(zone)) throw new IllegalArgumentException("UTC fixture");
            return this;
        }

        @Override
        public long millis() {
            return now;
        }

        @Override
        public java.time.Instant instant() {
            return java.time.Instant.ofEpochMilli(now);
        }
    }

    @Test
    void staleWarningIsNotAbsenceOrAnImplicitSqlRefresh() {
        var clock = new TestClock();
        var raw = new InMemorySnapshotRepository(clock, java.time.Duration.ofSeconds(1));
        raw.putSnapshot("1", "[]");
        var typed = raw.typedRepository(CatalogCardJsonCodec.INSTANCE);
        assertEquals(SnapshotStatus.FOUND, typed.lookup("1").status());
        clock.now += 2_000;
        assertEquals(SnapshotStatus.STALE_WARNING, typed.lookup("1").status());
        assertTrue(typed.findValuesById("1").orElseThrow().isEmpty());
        assertEquals(SnapshotStatus.ABSENT_IN_GENERATION, typed.lookup("2").status());
    }

    static CatalogImportItem item(long id, String category) {
        return new CatalogImportItem(
                id,
                "SKU-" + id,
                "\u0130\u011fne \u00e7elik \ud83d\ude80",
                category,
                new BigDecimal("12.3456"),
                8,
                2,
                1_700_000_000L);
    }

    @Test
    void generatedCodecPreservesUnicodeMoneyAndEmptyArrays() throws Exception {
        var expected = List.of(CatalogCard.from(item(1, "tools").entity()));
        var output = new ByteArrayOutputStream();
        CatalogCardJsonCodec.INSTANCE.encodeArray(expected, output);
        assertEquals(
                expected,
                CatalogCardJsonCodec.INSTANCE.decodeArray(output.toString(StandardCharsets.UTF_8)));
        assertTrue(CatalogCardJsonCodec.INSTANCE.decodeArray("[]").isEmpty());
        assertThrows(
                java.io.IOException.class,
                () -> CatalogCardJsonCodec.INSTANCE.decodeArray("[{\"extra\":1}]"));
    }

    @Test
    void generatedRepositoryAcceptsTestSessionAndDoesNotInventSqlDurability() {
        var memory = new InMemoryCacheDatabase();
        var repository = new ProductRepositoryCacheDbImplementation(memory);
        var accepted = repository.save(item(1, "tools").entity());
        assertTrue(repository.findHotById(1L).isHit());
        assertFalse(memory.isDurable(accepted));
        memory.markDurable(accepted);
        assertTrue(memory.isDurable(accepted));
    }

    @Test
    void atomicImportReplayRetainsReceiptAndRequiresExplicitDurabilityEvidence() {
        var memory = new InMemoryCacheDatabase();
        ProductEntityCacheBinding.repository(memory);
        var imports =
                new CatalogImportService(
                        memory::writeBatch, (receipt, timeout) -> memory.isDurable(receipt));
        var command =
                new CatalogImportCommand("delivery-1", List.of(item(1, "tools"), item(2, "tools")));
        var first = imports.execute(command);
        assertFalse(first.durable());
        memory.markDurable(first.receipt());
        var replay = imports.execute(command);
        assertEquals(first.receipt(), replay.receipt());
        assertTrue(replay.durable());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        imports.execute(
                                new CatalogImportCommand("delivery-1", List.of(item(3, "tools")))));
    }

    @Test
    void optimisticConflictLeavesAllOtherBatchMembersUnchanged() {
        var memory = new InMemoryCacheDatabase();
        var products = ProductEntityCacheBinding.repository(memory);
        products.saveWithReceipt(item(2, "tools").entity());
        var imports = new CatalogImportService(memory::writeBatch, (receipt, timeout) -> false);
        assertThrows(
                OptimisticWriteConflictException.class,
                () ->
                        imports.execute(
                                new CatalogImportCommand(
                                        "conflict", List.of(item(1, "tools"), item(2, "tools")))));
        assertTrue(products.findById(1L).isEmpty());
    }

    @Test
    void snapshotStateAndUnrelatedPartitionPaginationStayExplicit() throws Exception {
        var raw = new InMemorySnapshotRepository();
        var typed =
                TypedSnapshotRepository.decoding(
                        raw, CatalogCardJsonCodec.INSTANCE, (Long id) -> id.toString());
        var api = new CatalogController(typed);
        assertEquals(503, api.item(1).getStatusCode().value());
        var encoded = new ByteArrayOutputStream();
        CatalogCardJsonCodec.INSTANCE.encodeArray(
                List.of(CatalogCard.from(item(1, "tools").entity())), encoded);
        String payload = encoded.toString(StandardCharsets.UTF_8);
        raw.putSnapshot("1", payload, "tools", 1);
        raw.putSnapshot("2", payload, "tools", 2);
        raw.putSnapshot("3", payload, "other", 3);
        assertEquals(200, api.item(1).getStatusCode().value());
        assertEquals(404, api.item(99).getStatusCode().value());
        var first = typed.findTypedPage("tools", WindowRequest.first(1));
        raw.putSnapshot("3", payload, "other", 4);
        assertEquals(
                "2",
                typed.findTypedPage("tools", WindowRequest.after(first.nextCursor(), 1))
                        .items()
                        .getFirst()
                        .id());
        raw.putSnapshot("1", payload, "tools", 5);
        assertThrows(
                IllegalArgumentException.class,
                () -> typed.findTypedPage("tools", WindowRequest.after(first.nextCursor(), 1)));
        raw.expire();
        assertEquals(503, api.item(1).getStatusCode().value());
    }

    @Test
    void deterministicBatchRejectionIsNotRetriedAsAnInfrastructureFailure() {
        var service =
                new CatalogImportService(
                        id -> {
                            throw new redis.clients.jedis.exceptions.JedisDataException(
                                    "Batch id reused with different operations");
                        },
                        (receipt, timeout) -> false);
        var handler = new CatalogImportJobHandler(service);
        var rejected =
                (CatalogImportService.Result)
                        handler.execute(
                                new CatalogImportCommand("delivery", List.of(item(1, "tools"))),
                                null);
        assertEquals("REJECTED", rejected.outcome());
        assertFalse(rejected.durable());
        assertNull(rejected.receipt());
        var broken =
                new CatalogImportService(
                        id -> {
                            throw new redis.clients.jedis.exceptions.JedisDataException(
                                    "WRONGTYPE unexpected storage error");
                        },
                        (receipt, timeout) -> false);
        assertThrows(
                redis.clients.jedis.exceptions.JedisDataException.class,
                () ->
                        new CatalogImportJobHandler(broken)
                                .execute(
                                        new CatalogImportCommand(
                                                "delivery", List.of(item(1, "tools"))),
                                        null));
    }

    @Test
    void sourceLimitsAndRequestContractsRejectUnboundedOrAmbiguousImports() {
        assertThrows(
                IllegalArgumentException.class, () -> new CatalogImportCommand("x", List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CatalogImportCommand("x", List.of(item(1, "a"), item(1, "b"))));
        assertThrows(IllegalArgumentException.class, () -> new CatalogRefreshCommand(List.of(-1L)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CatalogImportItem(1, "a", "b", "c", new BigDecimal("1E+20"), 1, 0, 1));
        var plan = new CatalogConfiguration().stockCatalog();
        assertSame(CatalogCardJsonCodec.INSTANCE, plan.jsonCodec());
        assertNotNull(plan.rootScope());
        assertNotNull(plan.indexSpec());
    }
}
```

Gerçek sağlayıcı entegrasyon testi ayrıca `CatalogScenarios`,
`WriteFenceScenarios` ve `SplitRoleScenarios` kontrollerini çalıştırır. REST ile
gerçek Redis/SQL akışı, fiziksel DELETE sonrasında writer yeniden oluşturulup eski
UPSERT'in gönderilmesi, SQL/metadata'nın birlikte geri alınması ve istemcide
bekleyen yazmanın ayrı işleyici ile onun yerine başlayan işleyicide tamamlanması
doğrulanır. Replay müdahalesi **yalnızca geçici testlerde** vardır; üretim HTTP
endpoint'i olarak sunulmaz. Kalıcı `cachedb_write_fence` tablosunu koruyun;
sağlayıcı migration'ı ve kontrollü yükseltme rehberini uygulayın.

`postman/cache-database-postgresql-catalog.postman_collection.json` dosyasını içe
aktarın. **15 isteği** sırayla çalıştırın; şema kurulmuşsa demo seed gerekmez.
Akış; ilk süre sınırlı bekleme yeterli değilse yalnızca aynı teslimatı tekrar
göndererek SQL kalıcılığını, aynı receipt ile tekrarı, değişen içeriğin reddini,
snapshot yayınını, Türkçe metin/ondalık sayıyı, tek nesilden okumayı, sayfalamayı
ve geçersiz girdi yanıtlarını doğrular. Mevcut 59 istekli iş senaryosu koleksiyonu
ayrı kalır. İki koleksiyon da REST için 50 ms SLA'sı iddia etmez.

```powershell
npx --yes newman@6.2.1 run postman/cache-database-postgresql-catalog.postman_collection.json --env-var baseUrl=$base
```

Entegrasyon testi, etkilenmeyen kategoride cursor'ın korunmasını da doğrular;
Postman akışı SQL'i doğrudan değiştirmez. Redis Cluster namespace dağılımı bir
framework altyapı yeteneğidir; yerel Compose tek Redis kullanır ve Cluster/HA
topolojisini doğruladığını iddia etmez. JSON ve rol seçimleri açıktır; ORM'nin
kendiliğinden SQL'e geçmesi gibi bir davranış eklenmez.

Yalıtılmış komut satırı testi için Node.js/npm ve PowerShell 7 kurulu olmalıdır.
JAR'ı derledikten sonra `./scripts/test-catalog-postman.ps1` çalıştırın. Araç;
geçici sağlayıcı altyapısını ve boş yerel portta JAR'ı başlatır, kaydedilmiş
koleksiyonu Newman ile çalıştırır, JSON/JUnit raporlarını `target/catalog-postman`
altına yazar ve yalnızca kendi test projesini/volume'larını kaldırır.
Bu aracı üretim veritabanına bağlamayın.
`-SplitRoles` seçeneği, yerel şemayı hazırlayıp hazırlık JVM'ini durdurur; aynı
koleksiyonu bir API konteyneri ve **iki worker konteyneri** üzerinde çalıştırır.
Örneklerin CI hattı bu modu kullanır; konteyner logları test kanıtına eklenir.
<!-- END COMPILED FEATURE EXAMPLES -->

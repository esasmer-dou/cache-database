# Başlangıç Rehberi

Bu rehber, yeni bir projede veya mevcut PostgreSQL uygulamasında CacheDB'yi
çalışır hale getirmek için izlenecek kısa ve pratik yolu anlatır.

Production'da çalışan bir ORM route'un varsa her şeyi bir anda yeniden yazma.
Önce tek bir route'u keşfet, staging'de warm et, PostgreSQL ve CacheDB
sonuçlarını karşılaştır, sonra cutover kararı ver.

## 1. Doğru Başlangıç Yolunu Seç

| Durum | Kullan | Not |
| --- | --- | --- |
| Yeni Spring Boot uygulaması | `cachedb-spring-boot-starter` | Çoğu ekip için önerilen varsayılan |
| JPA kullanan mevcut Spring Boot uygulaması | `cachedb-spring-boot-starter` ve mevcut `DataSource` | JPA zaten `DataSource` oluşturuyorsa JDBC auto-config'i tekrar ekleme |
| Plain Java servisi | `cachedb-starter` | Bootstrap ve lifecycle kontrolü sende olur |
| Mevcut PostgreSQL + ORM uygulaması | Önce Migration Planner | Şemayı keşfet, sıcak route'u planla, Redis'i warm et, sonucu karşılaştır |
| Relation-ağır ekran | Projection/read-model | İlk sayfada full aggregate yüklemeyi engeller |

## 2. Bağımlılıkları Ekle

### Spring Boot

Spring Boot'un `CacheDatabase` bean'ini oluşturmasını ve admin UI'yi aynı
uygulama portundan yayınlamasını istiyorsan bu yolu kullan.

```xml
<properties>
    <cachedb.version>0.1.0-beta.2</cachedb.version>
</properties>

<dependencies>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-spring-boot-starter</artifactId>
        <version>${cachedb.version}</version>
    </dependency>
    <dependency>
        <groupId>com.reactor.cachedb</groupId>
        <artifactId>cachedb-annotations</artifactId>
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

Bağımlılık kuralı:

- uygulama zaten Spring `DataSource` oluşturmuyorsa `spring-boot-starter-jdbc` ekle
- `spring-boot-starter-data-jpa` veya başka bir starter zaten `DataSource` oluşturuyorsa tekrar ekleme
- `cachedb-annotations` ve annotation processor olarak `cachedb-processor` her durumda kalsın

### Plain Java

Bootstrap kontrolünü kendin almak istiyorsan bu yolu kullan.

```xml
<properties>
    <cachedb.version>0.1.0-beta.2</cachedb.version>
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

## 3. Redis ve PostgreSQL Ayarını Yap

### Spring Boot `application.yml`

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

### Plain Java Bootstrap

```java
JedisPooled jedis = new JedisPooled("redis://127.0.0.1:6379");
DataSource dataSource = ...;

try (CacheDatabase cacheDatabase = CacheDatabase.bootstrap(jedis, dataSource)
        .production()
        .keyPrefix("app-cache")
        .register(com.reactor.cachedb.examples.entity.GeneratedCacheBindings::register)
        .start()) {
    // uygulama kodu
}
```

## 4. İlk Entity'yi Modelle

Bütün veritabanını bir anda modelleme. Uygulamada gerçekten sıcak olan tek bir
entity ile başla.

```java
@CacheEntity(table = "customers", redisNamespace = "customers")
public class CustomerEntity {
    private Long customerId;
    private String taxNumber;
    private String customerType;
}
```

Sonra projeyi compile et. Annotation processor runtime reflection kullanmadan
generated binding sınıflarını üretir.

## 5. Önce Önerilen API Yüzeyini Kullan

Varsayılan uygulama yüzeyi:

```java
var domain = GeneratedCacheModule.using(session);
```

Bu yüzey normal serviş kodu için doğru başlangıçtır. Onboarding kolay kalır ve
runtime maliyeti düşük repository yoluna yakın durur.

Daha aşağıya yalnızca ölçümden sonra in:

- normal iş kodu için `GeneratedCacheModule.using(session)...`
- ölçülmüş sıcak endpoint için `*CacheBinding.using(session)...`
- worker, replay, repair veya kanıtlanmış hotspot için doğrudan repository

## 6. Relation-Ağır Ekranları Bilinçli Modelle

Örnek: bir müşterinin çok sayıda siparişi var ve UI son 1.000 siparişi
`order_date DESC` sırasıyla göstermek istiyor.

Bu tasarımı kullan:

1. `CustomerEntity` root olarak kalır
2. tüm sipariş tarihçesi PostgreSQL'de durable kalır
3. Redis'te yalnızca sınırlı sıcak sipariş penceresi tutulur
4. liste için order summary projection kullanılır
5. full order detail yalnızca kullanıcı tek siparişi açınca yüklenir

Şunlardan kaçın:

- her liste render'ında full customer aggregate yüklemek
- sadece satır göstermek için bütün order line'ları yüklemek
- ilk sayfa istendikten sonra büyük entity payload'ını memory içinde sıralamak

## 7. Mevcut PostgreSQL + ORM Geçiş Akışı

Halihazırda tablo ve ORM yapın varsa admin UI'den başla:

1. `/cachedb-admin/migration-planner` ekranını aç
2. PostgreSQL şema keşfini çalıştır
3. keşfedilen root/child route adaylarından birini seç
4. route'u forma uygula
5. planı oluştur
6. gerekiyorsa entity/projection scaffold üret
7. dry-run warm çalıştır ve SQL'i incele
8. gerçek staging warm çalıştır
9. side-by-side comparison çalıştır
10. migration report indir

%100 migration coverage için bunu her production route için tekrarla. Planner
bilinçli olarak tek route modeller; tam sistem kapsamı, tek global düğmeden
değil route envanterinden gelir.

## 8. Root `.gitignore` Ekle

Bu repo hazır bir root [.gitignore](../../.gitignore) içerir. Şunları kapsar:

- Maven ve Java çıktıları
- modül `target/` klasörleri
- IDE dosyaları
- lokal log ve geçici çıktı dosyaları
- `tools/tmp` evidence dosyaları
- lokal secret dosyaları

CacheDB'yi gömen uygulama repolarında eşdeğer bir Java/Maven ignore politikası
yoksa bu baseline'ı kullan.

## 9. Lokal Doğrula

En az şu komutu çalıştır:

```powershell
mvn -q -DskipTests package
```

Demo uygulama için:

```powershell
./tools/ops/demo/run-spring-boot-load-demo.ps1
```

Sonra şu adresleri aç:

- demo load UI: `http://127.0.0.1:8090/demo-load`
- admin dashboard: `http://127.0.0.1:8090/cachedb-admin`
- migration planner: `http://127.0.0.1:8090/cachedb-admin/migration-planner`

## 10. Sonraki Okuma

- [Spring Boot Starter](./spring-boot-starter.md)
- [Geçiş Planlayıcı](./migration-planner.md)
- [Production Recipes](./production-recipes.md)
- [Tuning Parameters](./tuning-parameters.md)
- [ORM Alternative](./orm-alternative.md)


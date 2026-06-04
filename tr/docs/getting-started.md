# Başlangıç Rehberi

Bu rehber, CacheDB'yi yeni bir projeye eklemek veya mevcut PostgreSQL tabanlı
bir uygulamada denemek için izlenecek pratik yolu anlatır.

Çalışan bir ORM yapın varsa her şeyi bir anda değiştirme. Önce tek bir akışı
seç, staging ortamında Redis'i önceden doldur, PostgreSQL ve CacheDB sonuçlarını
karşılaştır, sonra canlıya geçiş kararını ver.

## 1. Doğru Başlangıç Yolunu Seç

| Durum | Kullan | Not |
| --- | --- | --- |
| Yeni Spring Boot uygulaması | `cachedb-spring-boot-starter` | Çoğu ekip için önerilen varsayılan yol |
| JPA kullanan mevcut Spring Boot uygulaması | `cachedb-spring-boot-starter` ve mevcut `DataSource` | JPA zaten `DataSource` oluşturuyorsa JDBC starter'ı tekrar ekleme |
| Plain Java servisi | `cachedb-starter` | Başlatma ve yaşam döngüsü kontrolü sende olur |
| Mevcut PostgreSQL + ORM uygulaması | Önce Geçiş Planlayıcı | Şemayı keşfet, sıcak akışı planla, Redis'i doldur, sonucu karşılaştır |
| Çok ilişkili ekran | Projection / okuma modeli | İlk sayfada bütün veri grafiğini yüklemeyi engeller |

## 2. Bağımlılıkları Ekle

### Spring Boot

Spring Boot'un `CacheDatabase` bean'ini oluşturmasını ve yönetim arayüzünü aynı
uygulama portundan yayınlamasını istiyorsan bu yolu kullan.

```xml
<properties>
    <cachedb.version>0.1.0-beta.3</cachedb.version>
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

- Uygulama henüz Spring `DataSource` oluşturmuyorsa `spring-boot-starter-jdbc` ekle.
- `spring-boot-starter-data-jpa` veya başka bir starter zaten `DataSource` oluşturuyorsa tekrar JDBC starter ekleme.
- `cachedb-annotations` ve annotation processor olarak `cachedb-processor` her durumda kalmalıdır.

### Plain Java

`CacheDatabase` nesnesini kendin başlatmak istiyorsan bu yolu kullan.

```xml
<properties>
    <cachedb.version>0.1.0-beta.3</cachedb.version>
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

### Plain Java Başlatma

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

Bütün veritabanını bir anda modellemeye çalışma. Uygulamada gerçekten sıcak olan
tek bir entity ile başla.

```java
@CacheEntity(table = "customers", redisNamespace = "customers")
public class CustomerEntity {
    private Long customerId;
    private String taxNumber;
    private String customerType;
}
```

Projeyi compile ettiğinde annotation processor, çalışma zamanı reflection'ı kullanmadan
binding sınıflarını üretir.

## 5. Önce Önerilen API Yüzeyini Kullan

Normal servis kodu için başlangıç noktası:

```java
var domain = GeneratedCacheModule.using(session);
```

Bu yol, ekibi hızlı başlatır ve düşük ek yük hedefinden uzaklaşmaz.

Daha düşük seviyeye yalnızca ölçümden sonra in:

- normal iş kodu için `GeneratedCacheModule.using(session)...`
- ölçülmüş sıcak endpoint için `*CacheBinding.using(session)...`
- worker, replay, repair veya kanıtlanmış darboğaz için doğrudan repository

## 6. Çok İlişkili Ekranları Bilinçli Tasarla

Örnek: bir müşterinin çok sayıda siparişi var ve UI son 1.000 siparişi
`order_date DESC` sırasıyla göstermek istiyor.

Doğru tasarım:

1. `CustomerEntity` kök olarak kalır.
2. Sipariş geçmişinin tamamı PostgreSQL'de kalıcı olarak tutulur.
3. Redis'te yalnızca sınırlı sıcak sipariş penceresi tutulur.
4. Liste için order summary projection kullanılır.
5. Sipariş detayı, kullanıcı ilgili satırı açtığında ayrıca yüklenir.

Kaçınılacak tasarım:

- her liste render'ında bütün müşteri veri grafiğini yüklemek
- sadece satır göstermek için tüm sipariş satırlarını yüklemek
- ilk sayfa istendikten sonra büyük entity payload'ını bellek içinde sıralamak

## 7. Mevcut PostgreSQL + ORM Geçiş Akışı

Halihazırda tablo ve ORM yapın varsa yönetim arayüzünden başla:

1. `/cachedb-admin/migration-planner` ekranını aç.
2. PostgreSQL şema keşfini çalıştır.
3. Keşfedilen kök/çocuk tablo adaylarından birini seç.
4. Adayı forma uygula.
5. Planı oluştur.
6. Gerekirse entity/projection iskeletini üret.
7. Dry-run ön ısıtma çalıştır ve SQL'i incele.
8. Gerçek staging ön ısıtma çalıştır.
9. Yan yana karşılaştırma çalıştır.
10. Geçiş raporunu indir.

%100 geçiş kapsamı için bunu her production akışı için tekrarla. Planner
bilinçli olarak tek akış modeller; tam sistem kapsamı, tek global düğmeden
değil akış envanterinden gelir.

## 8. Root `.gitignore` Ekle

Bu repo hazır bir root [.gitignore](../../.gitignore) içerir. Şunları kapsar:

- Maven ve Java çıktıları
- modül `target/` klasörleri
- IDE dosyaları
- lokal log ve geçici çıktı dosyaları
- `tools/tmp` evidence dosyaları
- lokal secret dosyaları

CacheDB'yi gömen uygulama repolarında eşdeğer bir Java/Maven ignore politikası
yoksa bu başlangıç listesini kullan.

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
- yönetim paneli: `http://127.0.0.1:8090/cachedb-admin`
- geçiş planlayıcı: `http://127.0.0.1:8090/cachedb-admin/migration-planner`

## 10. Sonraki Okuma

- [Spring Boot Starter](./spring-boot-starter.md)
- [Geçiş Planlayıcı](./migration-planner.md)
- [Kullanım Senaryosu Örnekleri](./use-case-examples.md)
- [Production Reçeteleri](./production-recipes.md)
- [Tuning Parametreleri](./tuning-parameters.md)
- [ORM Alternatifi](./orm-alternative.md)

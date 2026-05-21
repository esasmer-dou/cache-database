# cache-database

English version: [../README.md](../README.md)

`cache-database`, Redis'i okuma/yazma yolunun merkezine alan, PostgreSQL'i ise
kalıcı veri deposu olarak kullanan bir Java persistence kütüphanesidir. Hedefi,
çalışma zamanı ek yükünü düşük tutarken geliştiriciye ORM'e yakın ve anlaşılır
bir kullanım sunmaktır.

Bu proje şu ilkeye dayanır: sıcak veri yolu açık, sınırlı ve ölçülebilir
olmalıdır.

## Ne Sağlar?

- Sıcak okuma ve yazma işlemleri için Redis öncelikli çalışma modeli.
- PostgreSQL'e async write-behind ile kalıcı yazım.
- Çalışma zamanı reflection'ı yerine derleme zamanında üretilen metadata.
- Normal servis kodu için ORM'e yakın üretilmiş API'ler.
- Pahalı liste ekranları için açık ilişki yükleme, projection ve okuma modeli desteği.
- Ölçümle kanıtlanan darboğazlarda daha düşük seviyeli repository kullanımına inme imkanı.
- Spring Boot içinde aynı porttan çalışan yönetim arayüzü ve geçiş planlayıcı.

## Mevcut Olgunluk

CacheDB bugün açık beta değerlendirmesi ve staging pilotları için uygundur.
Henüz "koşulsuz GA" olarak konumlandırılmamalıdır.

Production'a yakın kullanımda şu kurallar tasarımın parçası kabul edilmelidir:

- Redis gerçek bir production bağımlılığı olarak yönetilmelidir.
- Çok ilişkili liste ekranları projection ve okuma modeliyle tasarlanmalıdır.
- Global sıralama veya iş önceliği kullanan ekranlarda ranked projection tercih edilmelidir.
- Mevcut ORM'den geçiş, önce staging ortamında ön ısıtma ve yan yana karşılaştırma ile kanıtlanmalıdır.

## Hangi Yoldan Başlamalısın?

| Durum | Başlangıç | Neden |
| --- | --- | --- |
| Yeni Spring Boot servisi | `cachedb-spring-boot-starter` | En hızlı kurulum, aynı porttan yönetim arayüzü, production varsayılanları |
| Plain Java servisi | `cachedb-starter` | Spring Boot kullanmadan başlatma kontrolü |
| Mevcut PostgreSQL + ORM uygulaması | Yönetim arayüzündeki Geçiş Planlayıcı | Şemayı keşfeder, sıcak akışı seçmeni sağlar, Redis'i önceden doldurur, sonucu karşılaştırır |
| Çok ilişkili liste ekranı | Projection / okuma modeli | İlk ekranda bütün veri grafiğini yüklemeyi engeller |
| Ölçülmüş tek gecikme darboğazı | `*CacheBinding` veya doğrudan repository | Yalnızca gerçekten sıcak olan yerde daha fazla kontrol verir |
| Worker, replay veya operasyon kodu | Doğrudan repository | Daha açık, tahmin edilebilir ve düşük ek yük taşıyan yol |

## 5 Dakikada Kurulum

### Maven: Spring Boot

Spring Boot kullanıyorsan ve uygulamada bir Spring `DataSource` varsa bu yolu
tercih et.

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

Uygulama zaten `spring-boot-starter-data-jpa` veya başka bir starter üzerinden
`DataSource` oluşturuyorsa, yalnızca CacheDB için ayrıca
`spring-boot-starter-jdbc` ekleme. CacheDB'nin ihtiyacı olan şey çalışan bir
`DataSource` bean'idir; JDBC auto-configuration'ı iki kez açmak gerekmez.

### Maven: Plain Java

`CacheDatabase` nesnesini kendin başlatmak istiyorsan bu yolu kullan.

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

### Minimal Spring Boot Ayarı

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
  admin:
    http-enabled: true
```

Yönetim arayüzü aynı porttan yalnızca `cachedb.admin.http-enabled=true`
açıkça verildiğinde yayınlanır:

- yönetim paneli: `/cachedb-admin`
- geçiş planlayıcı: `/cachedb-admin/migration-planner`
- sağlık API'si: `/cachedb-admin/api/health`

Üretim kuralı: `/cachedb-admin/**` yollarını internete doğrudan açma. Bu
yüzeyi gateway/auth katmanının arkasına al veya CacheDB token auth için
`cachedb.admin.auth-enabled=true` ve boş olmayan bir admin token tanımla.

## İlk Gün Uygulama Akışı

1. Maven bağımlılıklarını ekle.
2. Redis ve PostgreSQL bağlantılarını tanımla.
3. İlk sıcak entity için `@CacheEntity` kullan.
4. Annotation processor'ın binding sınıflarını üretmesine izin ver.
5. Uygulama kodunda `GeneratedCacheModule.using(session)...` ile başla.
6. Bütün veri grafiğini yüklememesi gereken liste ekranları için projection ekle.
7. "Son 8 sipariş satırı" gibi önizleme alanlarında `withRelationLimit(...)` kullan.
8. Sadece ölçülmüş darboğazları daha düşük seviyeli repository kullanımına indir.

## Yaygın Kullanım Senaryoları

### Senaryo 1: Bir Müşterinin Çok Fazla Siparişi Var

Kullanıcı müşteri detayını açtığında son 1.000 siparişi tarih sırasına göre
görmek istiyorsa, müşteriyle ilişkili bütün siparişleri ve sipariş satırlarını
tek seferde yükleme.

Bu tasarımı kullan:

- `CustomerEntity` kök entity olarak kalır.
- `OrderEntity` tam geçmişiyle PostgreSQL'de durur; Redis'te yalnızca gerekli sıcak pencere tutulur.
- Müşteri-sipariş özet projection'ı ekranın liste için ihtiyaç duyduğu alanları taşır.
- Liste sorgusu `order_date DESC, order_id DESC` ile sıralanır.
- Sipariş detayı, kullanıcı tek bir siparişi açtığında ayrıca yüklenir.

Sonuç: müşteri başına sipariş sayısı artsa bile ilk ekranın maliyeti sınırlı
kalır.

### Senaryo 2: Yönetim Paneli En Önemli İş Kayıtlarını Gösteriyor

Ekran gelir, risk, öncelik veya benzeri bir iş skoruna göre genel sıralama
yapıyorsa ranked projection kullan.

Bu tasarımı kullan:

- projection üzerinde kararlı bir `rank_score` veya eşdeğer sıralama alanı üret.
- Sorguyu bu sıralama alanı üzerinden indeksle.
- İlk sayfayı veya sıcak pencereyi Redis'te tut.
- Büyük entity payload'larını çekip sonradan uygulama içinde sıralamaktan kaçın.

### Senaryo 3: Mevcut ORM Akışını Taşıyorsun

PostgreSQL ve farklı bir ORM ile çalışan bir uygulamayı doğrudan taşımaya
çalışma. Önce taşınacak akışı kanıtla.

Geçiş Planlayıcı akışı:

1. `/cachedb-admin/migration-planner` ekranını aç.
2. PostgreSQL şemasını keşfet.
3. Önerilen kök/çocuk tablo adaylarından birini seç.
4. Önerilen entity/projection iskeletini üret.
5. Redis'i değiştirmeden dry-run ön ısıtma çalıştır ve SQL'i incele.
6. Staging ön ısıtma ile Redis sıcak veri setini doldur.
7. Yan yana karşılaştırma çalıştır.
8. Veri eşleşmesi, sıralama ve gecikme kabul edilebilir değilse canlıya geçme.

Tam sistem dönüşümünde bunu akış bazında tekrarla. Her production ekranı ve API
şu sınıflardan birine açıkça yerleştirilmelidir:

- generated CRUD akışı
- projection / okuma modeli akışı
- ranked projection akışı
- doğrudan repository veya worker akışı
- bilinçli olarak PostgreSQL soğuk veri yolunda bırakılan akış

## Neden CacheDB?

Şu durumlarda CacheDB mantıklı bir seçimdir:

- düşük gecikmeli okuma önemliyse
- Redis zaten gerçek bir production bağımlılığıysa
- okuma modeli üzerinde açık kontrol istiyorsan
- ilişki fan-out değeri zaman içinde büyüyebiliyorsa
- çalışma zamanı reflection'ı istemiyor ama üretilmiş API ergonomisi istiyorsan
- PostgreSQL/ORM akışlarını aşamalı biçimde Redis öncelikli sıcak yola taşımak istiyorsan

## Neden CacheDB Değil?

Şu durumlarda geleneksel JPA/Hibernate benzeri bir stack daha uygun olabilir:

- uygulamanın ana yükü SQL join ve raporlama ise
- ORM davranışının büyük ölçüde görünmez kalması bekleniyorsa
- ekip projection ve okuma modeli tasarımını sahiplenmek istemiyorsa
- Redis production çalışma planının parçası değilse

Bu ayrım bilinçlidir. CacheDB, persistence davranışını saklamaktan çok açık
kontrol, sınırlı sıcak veri yolu ve öngörülebilir çalışma zamanı ek yükü için
tasarlanmıştır.

## Hızlı Karşılaştırma

| Konu | CacheDB | Geleneksel ORM |
| --- | --- | --- |
| Birincil okuma yolu | Redis öncelikli | Veritabanı öncelikli |
| Kalıcılık | Write-behind ile PostgreSQL | Doğrudan veritabanı transaction yolu |
| Metadata | Derleme zamanında üretilir | Genelde çalışma zamanı reflection'ı ve ORM metadata |
| İlişki modeli | Açık fetch plan, loader ve projection | Çoğu zaman implicit lazy/eager davranış |
| Sıcak liste ekranları | Önce projection / okuma modeli | Çoğu zaman önce entity grafiği |
| En iyi uyum | Düşük gecikmeli servisler, Redis merkezli sistemler | SQL merkezli ilişkisel uygulamalar |

## Ölçülmüş Kanıt

Buradaki iddia ergonominin bedava olduğu değildir. Daha pratik iddia şudur:
üretilmiş API ergonomisi, minimal repository yoluyla aynı düşük ek yük bandında
kalabilir. Production maliyetinin büyük kısmı genelde sorgu şekli, ilişki
yükleme, Redis contention ve write-behind baskısından gelir.

Son yerel recipe benchmark özeti:

| Yüzey | Avg ns | p95 ns | Nasıl okunmalı |
| --- | ---: | ---: | --- |
| Generated entity binding | 6005 | 13400 | Bu yerel koşuda ortalamada en hızlı yüzey |
| JPA-style domain module | 8059 | 20300 | Gruplanmış ergonomik yüzey, makul wrapper maliyeti |
| Minimal repository | 15075 | 9600 | Bu yerel koşuda en düşük p95 |

![Repository recipe benchmark](../docs/assets/repository-recipe-benchmark.svg)

Raporu yeniden üretmek için:

```powershell
mvn -q -f cachedb-production-tests/pom.xml exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.RepositoryRecipeBenchmarkMain"
```

## Production Reçete Merdiveni

![Production recipe ladder](../docs/assets/production-recipe-ladder.svg)

Pratik kural:

1. `GeneratedCacheModule.using(session)...` ile başla.
2. Sıcak endpoint'leri yalnızca ölçümden sonra `*CacheBinding.using(session)...` tarafına indir.
3. Kanıtlanmış darboğaz, replay veya worker kodunu doğrudan repository seviyesine çek.
4. Çok ilişkili ve global sıralı ekranlarda projection / okuma modeli kullan.

## Sonraki Okuma

- [Başlangıç Rehberi](docs/getting-started.md)
- [Spring Boot Starter](docs/spring-boot-starter.md)
- [Geçiş Planlayıcı](docs/migration-planner.md)
- [Kullanım Senaryosu Örnekleri](docs/use-case-examples.md)
- [Production Reçeteleri](docs/production-recipes.md)
- [ORM Alternatifi Rehberi](docs/orm-alternative.md)
- [Tuning Parametreleri](docs/tuning-parameters.md)
- [Production Testleri](cachedb-production-tests/README.md)
- [Örnekler](cachedb-examples/README.md)
- [Mimari](docs/architecture.md)
- [Açık Beta Hazırlık Durumu](docs/public-beta-readiness.md)
- [Release Checklist](docs/release-checklist.md)

## Topluluk

- [License](../LICENSE)
- [Contributing](../CONTRIBUTING.md)
- [Security Policy](../SECURITY.md)
- [Code of Conduct](../CODE_OF_CONDUCT.md)
- [Support](../SUPPORT.md)
- [Changelog](../CHANGELOG.md)

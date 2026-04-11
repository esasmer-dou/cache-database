# cache-database

Bu dosya, projenin Turkce kullanici odakli kisa landing dokumanidir.

Teknik mimari icin: [docs/architecture.md](docs/architecture.md)  
Production recipe rehberi icin: [docs/production-recipes.md](docs/production-recipes.md)  
ORM alternatifi rehberi icin: [docs/orm-alternative.md](docs/orm-alternative.md)  
Public beta readiness icin: [docs/public-beta-readiness.md](docs/public-beta-readiness.md)  
Release checklist icin: [docs/release-checklist.md](docs/release-checklist.md)

`cache-database`, production runtime overhead'i dusuk tutmayi birinci sinif tasarim kurali yapan Redis-first bir persistence kutuphanesidir. Ama bunu yaparken gelistirici deneyimini de ORM benzeri, kolay tuketilir bir seviyede tutmayi hedefler.

Sana su seyleri verir:

- Redis-first okuma ve yazma yolu
- async write-behind ile PostgreSQL kaliciligi
- runtime reflection yerine compile-time generated metadata
- explicit relation loading, projection ve hotspot kacis hatlari
- minimal repository yoluna yakin kalan generated ergonomi

## 5 Dakikada Kurulum

En hizli yol su:

1. `pom.xml` icine CacheDB dependency'lerini ekle
2. Redis ve PostgreSQL baglantisini tanimla
3. uygulama kodunda `GeneratedCacheModule.using(session)...` ile basla

### Maven: Spring Boot

```xml
<properties>
    <cachedb.version>0.1.0-beta.1</cachedb.version>
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

### Maven: Plain Java

```xml
<properties>
    <cachedb.version>0.1.0-beta.1</cachedb.version>
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

### Minimal Spring Boot config

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

Tam kopyala-yapistir akisi icin [Getting Started](docs/getting-started.md) rehberine bak.

## Neden CacheDB

Su durumlarda CacheDB sec:

- dusuk gecikmeli okumalar onemliyse
- Redis production mimarinin gercek bir parcasiysa
- read-model sekli uzerinde explicit kontrol istiyorsan
- ergonomik bir baslangic yuzeyi ama gerektiğinde daha alt seviyeye inen bir kacis hatti istiyorsan

## Neden CacheDB Degil

Su durumlarda geleneksel JPA/Hibernate benzeri bir stack daha uygun olur:

- uygulamanin ana yukunu SQL join ve iliskisel raporlama tasiyorsa
- ORM davranisinin buyuk olcude implicit kalmasi bekleniyorsa
- ekip projection, fetch limit ve relation sekli dusunmek istemiyorsa
- Redis production mimarisinde gercek bir bagimlilik degilse

Bu ayrim bilerek var. CacheDB, persistence davranisini gizlemek icin degil; explicit kontrol ve dusuk runtime overhead icin optimize edildi.

## Nasil Baslanmali

1. Spring Boot starter veya plain Java bootstrap yolu ile basla.
2. Varsayilan uygulama surface'i olarak `GeneratedCacheModule.using(session)...` kullan.
3. Sadece olculmus hotspot'lari `*CacheBinding.using(session)...` veya dogrudan repository tarafina indir.

Relation-agir ekranlarda once projection ve `withRelationLimit(...)` kullan; daha alt repository seviyesine bundan sonra in.

## Hizli Karsilastirma

| Konu | CacheDB | Geleneksel ORM |
| --- | --- | --- |
| Birincil okuma yolu | Redis-first | Database-first |
| Metadata | Compile-time generated | Genelde runtime reflection + ORM metadata |
| Varsayilan relation modeli | Explicit fetch plan ve loader | Cogu zaman implicit lazy/eager graph davranisi |
| Hotspot stratejisi | Olculmus kacis hatti ile daha alt surface'e inilir | Cogu zaman ORM soyutlamalari icinde kalinir |
| En iyi uyum | Dusuk gecikmeli servisler, Redis-merkezli sistemler | Iliskisel alanlar, SQL-merkezli uygulamalar |

## Olculmus Kanit

Buradaki iddia ergonominin bedava oldugu degil.

Buradaki daha durust ve daha faydali iddia su:

- generated ergonomi, minimal repository yolu ile ayni dusuk-overhead bandinda kalabiliyor
- production maliyetinin asil kaynagi hala query sekli, relation hydration, Redis contention ve write-behind baskisi

Son yerel recipe benchmark ozeti:

| Surface | Avg ns | p95 ns | Nasil okunmali |
| --- | ---: | ---: | --- |
| Generated entity binding | 6005 | 13400 | Bu yerel kosuda ortalamada en hizli yuzey |
| JPA-style domain module | 8059 | 20300 | Gruplanmis ergonomik surface, makul wrapper maliyeti |
| Minimal repository | 15075 | 9600 | Bu yerel kosuda en dusuk p95 |

![Repository recipe benchmark](../docs/assets/repository-recipe-benchmark.svg)

Bu olcumde karsilastirilan operasyonlar:

- `activeCustomers`
- `customersPage`
- `topCustomerOrdersSummary`
- `promoteVipCustomer`
- `deleteCustomer`

Raporu yeniden uretmek icin:

```powershell
mvn -q -f cachedb-production-tests/pom.xml exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.RepositoryRecipeBenchmarkMain"
```

Cikti:

- `target/cachedb-prodtest-reports/repository-recipe-comparison.md`
- `target/cachedb-prodtest-reports/repository-recipe-comparison.json`

Yorum notu:

- bu benchmark yon gosterir; her mikro kosuda ayni wrapper surface'in kazanacagini vaat etmez
- asil sonuc, generated ergonominin dogrudan repository kullanimiyla ayni buyukluk mertebesinde kalmasidir

## CacheDB Ile Baslamali Misin?

```mermaid
flowchart TD
    A["Dusuk gecikmeli okuma ve explicit runtime kontrol onemli mi?"] -->|Evet| B["Redis production'da gercek bir bagimlilik mi?"]
    A -->|Hayir| C["Geleneksel ORM genelde daha basit varsayilan olur"]
    B -->|Evet| D["CacheDB ile basla"]
    B -->|Hayir| E["Geleneksel ORM genelde daha iyi uyum verir"]
    D --> F["Ilk surface olarak GeneratedCacheModule kullan"]
    F --> G["Liste ekranlarinda projection ve relation limit kullan"]
    G --> H["Sadece olculmus hotspot'lari daha alta indir"]
```

## Temel Tasarim

- runtime reflection yok
- compile-time generated entity metadata var
- Redis-first okuma ve yazma yolu var
- PostgreSQL kaliciligi async write-behind ile saglaniyor
- relation loading ve projection tabanli read-model explicit
- hot-data butcesi ve runtime basinci icin guardrail'lar var

## Production Recipe Merdiveni

![Production recipe ladder](../docs/assets/production-recipe-ladder.svg)

Pratik kural:

1. `GeneratedCacheModule.using(session)...` ile basla
2. sadece sicak endpoint'leri `*CacheBinding.using(session)...` tarafina indir
3. yalnizca kanitlanmis hotspot, replay veya worker kodunu dogrudan repository seviyesine cek

## En Hizli Baslangic

### Spring Boot

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

### Duz Java

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

Onerilen yolda sunlari alirsin:

- production odakli varsayilanlar
- split foreground/background Redis pool
- generated registrar auto-registration
- Spring Boot icinde ayni port admin UI
- relation-agir okumalar icin projection + relation-limit yolu

## Sonraki Okuma

- [Production Recipes](docs/production-recipes.md)
- [Getting Started](docs/getting-started.md)
- [ORM Alternatifi Rehberi](docs/orm-alternative.md)
- [Public Beta Launch Kit](docs/public-beta-launch-kit.md)
- [Maven Central Publish Checklist](docs/maven-central-publish-checklist.md)
- [Public Beta Readiness](docs/public-beta-readiness.md)
- [Release Checklist](docs/release-checklist.md)
- [Konumlandirma Taslagi](docs/positioning-announcement.md)
- [Spring Boot Starter](docs/spring-boot-starter.md)
- [Tuning Parameters](docs/tuning-parameters.md)
- [Production Tests](cachedb-production-tests/README.md)
- [Examples](cachedb-examples/README.md)
- [Architecture](docs/architecture.md)

## Topluluk

- [License](../LICENSE)
- [Contributing](../CONTRIBUTING.md)
- [Security Policy](../SECURITY.md)
- [Code of Conduct](../CODE_OF_CONDUCT.md)
- [Support](../SUPPORT.md)
- [Changelog](../CHANGELOG.md)

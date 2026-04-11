# Getting Started

Bu rehber, `git clone` sonrasinda calisan bir CacheDB uygulamasina giden en
kisa yoldur.

## Yol Secimi

Asagidaki iki yoldan birini kullan:

- Spring Boot starter: cogu ekip icin en kolay yol
- Plain Java bootstrap: bootstrap uzerinde daha fazla kontrol istiyorsan

## 1. Dependency Ekle

### Spring Boot

Starter runtime parcalarini getirir. `cachedb-annotations` ise entity
siniflarinin compile-time generation akisina girmesini saglar.

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

### Plain Java

Runtime icin starter, entity annotation'lari icin `cachedb-annotations`, code
generation icin de annotation processor kullan.

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

## 2. Minimal Config Ekle

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

## 3. Onerilen API Surface Ile Basla

En iyi varsayilan baslangic:

```java
var domain = GeneratedCacheModule.using(session);
```

Neden:

- en kolay onboarding yolu
- compile-time generated helper'lar
- normal uygulama kodu icin dusuk-overhead surface
- gerekiyorsa daha alt binding/repository seviyesine net kacis hatti

## 4. Kok `.gitignore` Kullan

Repo artik kullanima hazir bir kok [.gitignore](../.gitignore) ile geliyor.
Bu dosya sunlari kapsar:

- Maven/Java build output
- tum modullerdeki `target/` klasorleri
- IDE dosyalari
- lokal log ve temp output
- `tools/tmp` altindaki evidence/log dosyalari
- lokal secret dosyalari

Eger uygulama reposunda esdeger bir Java/Maven ignore politikasi yoksa bu
baseline'i kullan.

## 5. Sonraki Okuma

- [Spring Boot Starter](./spring-boot-starter.md)
- [Production Recipes](./production-recipes.md)
- [Tuning Parameters](./tuning-parameters.md)
- [ORM Alternative](./orm-alternative.md)

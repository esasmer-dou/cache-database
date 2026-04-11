# Getting Started

Bu rehber, `git clone` sonrasinda çalışan bir CacheDB uygulamasina giden en
kısa yoldur.

## Yol Seçimi

Asagidaki iki yoldan birini kullan:

- Spring Boot starter: çoğu ekip için en kolay yol
- Plain Java bootstrap: bootstrap üzerinde daha fazla kontrol istiyorsan

## 1. Dependency Ekle

### Spring Boot

Starter runtime parcalarini getirir. `cachedb-annotations` ise entity
sınıflarinin compile-time generation akışina girmesini sağlar. Buna ek olarak
Spring Boot tarafinda bir `DataSource` yolu gerekir; tipik seçim
`spring-boot-starter-jdbc` olur.

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

Uygulama zaten `spring-boot-starter-data-jpa` gibi bir starter ile Spring
`DataSource` oluşturuyorsa `spring-boot-starter-jdbc` bağımlılığını ikinci kez
ekleme. CacheDB'nin ihtiyaçi olan şey, `DataSource` bean'inin zaten var olması.

### Plain Java

Runtime için starter, entity annotation'lari için `cachedb-annotations`, code
generation için de annotation processor kullan.

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

## 3. Önerilen API Surface ile Başla

En iyi varsayılan başlangıç:

```java
var domain = GeneratedCacheModule.using(session);
```

Neden:

- en kolay onboarding yolu
- compile-time generated helper'lar
- normal uygulama kodu için düşük-overhead surface
- gerekiyorsa daha alt binding/repository seviyesine net kaçış hattı

## 4. Kök `.gitignore` Kullan

Repo artık kullanıma hazır bir kok [.gitignore](../.gitignore) ile geliyor.
Bu dosya sunlari kapsar:

- Maven/Java build output
- tüm modullerdeki `target/` klasorleri
- IDE dosyalari
- lokal log ve temp output
- `tools/tmp` altındaki evidence/log dosyalari
- lokal seçret dosyalari

Eğer uygulama reposunda esdeger bir Java/Maven ignore politikasi yoksa bu
baseline'i kullan.

## 5. Sonraki Okuma

- [Spring Boot Starter](./spring-boot-starter.md)
- [Production Recipes](./production-recipes.md)
- [Tuning Parameters](./tuning-parameters.md)
- [ORM Alternative](./orm-alternative.md)

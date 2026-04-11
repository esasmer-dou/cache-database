# Getting Started

This guide is the shortest path from `git clone` to a running CacheDB-backed
application.

## Choose Your Path

Use one of these:

- Spring Boot starter: easiest path for most teams
- Plain Java bootstrap: best when you want explicit bootstrap control

## 1. Add Dependencies

### Spring Boot

Add the starter plus the annotations artifact. The starter brings the runtime
pieces; the annotations artifact lets your entities participate in compile-time
generation. You also need a Spring Boot `DataSource` path, typically through
`spring-boot-starter-jdbc`.

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

If your app already includes `spring-boot-starter-data-jpa` or another starter
that creates a Spring `DataSource`, do not add `spring-boot-starter-jdbc`
again. CacheDB just needs the `DataSource` bean to exist.

### Plain Java

Use the starter for the runtime, the annotations artifact for entity
annotations, and the processor as an annotation processor.

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

## 2. Add Minimal Configuration

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
    // application code
}
```

## 3. Start With The Recommended API Surface

The best default is:

```java
var domain = GeneratedCacheModule.using(session);
```

Why:

- easiest onboarding path
- compile-time generated helpers
- low-overhead surface for normal application code
- clean escape hatch to lower-level bindings or direct repositories later

## 4. Add A Root `.gitignore`

This repository now ships a ready-to-use root [.gitignore](../.gitignore) that
covers:

- Maven/Java outputs
- `target/` directories in all modules
- IDE files
- local logs and temp output
- `tools/tmp` evidence/log files
- local secret files

Copy that baseline into application repos that embed CacheDB if you do not
already have an equivalent Java/Maven ignore policy.

## 5. What To Read Next

- [Spring Boot Starter](./spring-boot-starter.md)
- [Production Recipes](./production-recipes.md)
- [Tuning Parameters](./tuning-parameters.md)
- [ORM Alternative](./orm-alternative.md)

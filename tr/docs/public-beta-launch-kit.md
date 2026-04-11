# Public Beta Launch Kit

Bu dokuman, repo'yu dis dunyaya acarken kullanilacak en kisa launch paketidir.

## GitHub About

### Isim

`cache-database`

### Kisa aciklama

`Redis-first Java persistence with async PostgreSQL write-behind and compile-time generated ORM-like APIs.`

### Website

Ayrica bir site yoksa repo URL'si veya ana dokuman landing sayfasi kullanilabilir.

### Onerilen topics

- `java`
- `redis`
- `postgresql`
- `orm`
- `persistence`
- `write-behind`
- `annotation-processor`
- `spring-boot`
- `low-latency`
- `read-model`
- `projection`
- `cache`

## Public Beta Konumlandirma

Onerilen ana mesaj:

`cache-database is a Redis-first persistence library for Java teams that care about production runtime overhead and still want an ORM-like developer experience.`

Onerilen caveat:

`Public beta: projection/read-model discipline is part of the intended design, especially for relation-heavy and globally ranked screens.`

## Ilk Public Release Basligi

`cache-database public beta`

Onerilen ilk tag:

`v0.1.0-beta.1`

## Ilk Public Release Note Taslagi

```md
## cache-database public beta

Bu, `cache-database` icin ilk public beta release'idir.

### CacheDB nedir

CacheDB, PostgreSQL'i durable store olarak korurken uygulama yolunu Redis-first
sekilde kuran bir Java persistence kutuphanesidir. Hedefi, runtime overhead'i
dusuk tutarken ciddi bir ORM-benzeri gelistirici deneyimi sunmaktir.

### Simdiden guclu olan taraflar

- Redis-first read/write path ve PostgreSQL durability
- compile-time generated entity metadata ve generated ergonomik API'ler
- Spring Boot starter ve plain Java bootstrap yolu
- relation-heavy ekranlar icin projection/read-model guidance
- global sorted business ekranlari icin ranked projection destegi
- production evidence workflow'lari ve multi-instance coordination smoke kapsami

### Public beta kapsami

CacheDB public beta kullanimi icin hazirdir; ancak henuz "kosulsuz GA" seviyesinde duyurulmamalidir.

Buradaki temel tasarim kurali explicit read-model shape'tir:

- once generated module/binding surface ile basla
- relation-agir liste ekranlarinda projection ve relation limit kullan
- global sorted/range-driven gorunumlerde ranked projection kullan
- yalnizca olculmus hotspot'lari direct repository seviyesine indir

### Production rollout oncesi

- production recipe rehberini oku
- tuning parameters dokumanini oku
- production evidence workflow'unu calistir
- shared Redis/PostgreSQL kullaniminda multi-instance coordination smoke'u calistir

### Onemli not

Relation-heavy ve global sorted ekranlarda projection/read-model disiplini
CacheDB icin optional degil, tasarimin bir parcasidir.
```

## Launch Checklist

- repo visibility bilincli sekilde degistirildi
- README ve docs linkleri kontrol edildi
- release checklist gozden gecirildi
- `pom.xml` icindeki placeholder maintainer alanlari guncellendi
- GitHub security reporting acildi
- branch protection acildi
- `oss-release` ile artifact build alindi

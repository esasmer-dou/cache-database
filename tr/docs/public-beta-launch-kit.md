# Public Beta Launch Kit

Bu doküman, repo'yu dış dünyaya açarken kullanılacak en kısa launch paketidir.

## GitHub About

### İsim

`cache-database`

### Kısa açıklama

`Redis-first Java persistence with async PostgreSQL write-behind and compile-time generated ORM-like APIs.`

### Website

Ayrıca bir site yoksa repo URL'si veya ana doküman landing sayfasi kullanılabilir.

### Önerilen topics

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

## Public Beta Konumlandırma

Önerilen ana mesaj:

`cache-database is a Redis-first persistence library for Java teams that care about production runtime overhead and still want an ORM-like developer experience.`

Önerilen caveat:

`Public beta: projection/read-model discipline is part of the intended design, especially for relation-heavy and globally ranked screens.`

## İlk Public Release Başlığı

`cache-database public beta`

Önerilen ilk tag:

`v0.1.0-beta.1`

## İlk Public Release Note Taslağı

```md
## cache-database public beta

Bu, `cache-database` için ilk public beta release'idir.

### CacheDB nedir

CacheDB, PostgreSQL'i durable store olarak korurken uygulama yolunu Redis-first
şekilde kuran bir Java persistence kütüphanesidir. Hedefi, runtime overhead'ini
düşük tutarken güçlü bir ORM-benzeri geliştirici deneyimi sunmaktır.

### Şimdiden güçlü olan taraflar

- Redis-first read/write path ve PostgreSQL durability
- compile-time generated entity metadata ve generated ergonomik API'ler
- Spring Boot starter ve plain Java bootstrap yolu
- relation-heavy ekranlar için projection/read-model guidance
- global sorted business ekranları için ranked projection desteği
- production evidence workflow'ları ve multi-instance coordination smoke kapsamı

### Public beta kapsamı

CacheDB, public beta kullanımı için hazırdır; ancak henüz "koşulsuz GA" seviyesinde duyurulmamalıdır.

Buradaki temel tasarım ilkesi explicit read-model shape yaklaşımıdır:

- önce generated modüle/binding surface ile başla
- relation-ağır liste ekranlarında projection ve relation limit kullan
- global sorted/range-driven görünümlerde ranked projection kullan
- yalnızca ölçülmüş hotspot'ları doğrudan repository seviyesine indir

### Production rollout öncesi

- production recipe rehberini oku
- tuning parameters dokümanını oku
- production evidence workflow'unu çalıştır
- shared Redis/PostgreSQL kullanımında multi-instance coordination smoke'u çalıştır

### Önemli not

Relation-heavy ve global sorted ekranlarda projection/read-model disiplini
CacheDB için optional değil, tasarımın bir parçasıdır.
```

## Launch Checklist

- repo visibility bilinçli şekilde değiştirildi
- README ve docs linkleri kontrol edildi
- release checklist gözden geçirildi
- `pom.xml` içindeki placeholder maintainer alanları güncellendi
- GitHub security reporting açıldı
- branch protection açıldi
- `oss-release` ile artifact build alindi

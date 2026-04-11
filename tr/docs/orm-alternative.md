# CacheDB Bir ORM Alternatifi Olarak

Bu sayfa tek bir soruya dışarıya dönük kısa bir cevap verir:

Bir ekip, geleneksel JPA/Hibernate benzeri ORM yerine ne zaman CacheDB seçmeli?

Cevap, projenin birinci önceliğiyle uyumlu kalmalıdır:

- production runtime overhead düşük olmalı
- kütüphane, gerçek bir ORM alternatifi olacak kadar kolay kullanılabilmeli

## Kısa Cevap

Şu durumlarda CacheDB seç:

- Redis gerçek production mimarisinin parçasıysa, sonradan eklenmiş bir detay değilse
- düşük gecikmeli okuma, saydam ilişkisel soyutlamadan daha önemliyse
- ekip relation loading, projection ve hot path konusunda explicit olmayı kabul ediyorsa
- production overhead, sade startup ve kaçış hattı önemliyse

Şu durumlarda Hibernate/JPA tarafinda kal:

- uygulamanın ana okuma modeli ağır biçimde ilişkisel join'lere dayanıyorsa
- ekip explicit runtime kontrolden çok saydam ORM davranışı istiyorsa
- lazy loading ve entity graph davranışının büyük ölçüde görünmez kalması bekleniyorsa
- production darboğazlarının çoğu read-path latency değil SQL modellemesi ise

## CacheDB Nedir

CacheDB, Hibernate'in birebir kopyası olmaya çalışmaz.

Bu kütüphane Redis-first bir persistence kütüphanesidir:

- uygulama okuma ve yazmalari önce Redis'e gider
- PostgreSQL kalıcı depo olarak kalır
- metadata derleme anında üretilir
- relation loading explicit olur
- write-behind, kalıcılık işini foreground path dışına taşır

Bu nedenle CacheDB şu gözle değerlendirilmelidir:

- explicit kontrol isteyen ekipler için düşük-overhead bir ORM alternatifi
- Redis-first uygulamalar için production-odaklı persistence kütüphanesi
- gerçek hotspot'lar için net bir kaçış hattı birakan bir library

## Hızli Karşılastirma

| Konu | CacheDB | Geleneksel JPA / Hibernate |
| --- | --- | --- |
| Birincil okuma yolu | Redis-first | Database-first |
| Metadata modeli | Compile-time generated | Genelde runtime reflection ve ORM metadata |
| Varsayılan felsefe | Explicit kontrol | Saydam soyutlama |
| Relation loading | Explicit `FetchPlan`, loader, projection | Çoğu zaman implicit lazy/eager graph davranisi |
| Hotspot kaçış hattı | Binding veya doğrudan repository'ye inebilirsin | Çoğu zaman ORM içinde kalinir veya custom SQL yazılir |
| En iyi uyum | Düşük geçikmeli servisler, read-ağır API'ler, Redis-merkezli sistemler | Iliskisel alanlar, SQL-merkezli sistemler, join-ağır uygulamalar |
| Runtime overhead hedefi | Çok düşük | Genelde kabul edilebilir, ama birincil tasarım hedefi değil |

## CacheDB En Çok Nerede Guclu

CacheDB şu alanlarda guclu bir uyum verir:

- sıcak read path'i olan urun servisleri
- projection kullanan dashboard ve liste-ağır uygulamalar
- Redis'i zaten production'da birinci sınıf bağımlilik olarak isleten sistemler
- reflection-ağır runtime davranis istemeyen ama generated ergonomi isteyen ekipler
- normal kod ile ölçulmus hotspot'lari net ayirmak isteyen servisler

## Nerede Daha Kötü Bir Uyumluluk Gösterir

Ekip şu şeyleri istiyorsa CacheDB daha kötü bir uyum verir:

- read-model seklini büyük oranda gizleyen bir ORM
- ekranları varsayılan olarak geniş relational join ile kurmak
- payload boyutunu düşünmeden otomatik graph traversal beklemek
- temel uygulama paterni olarak ağır iliskisel raporlama

Bu tip durumlarda Hibernate/JPA daha dogal arac olabilir.

Bu mesaji açık vermek zayiflik değil; konumlandırmayi daha güvenilir hale getirir.

## Production Ekipleri Neyi Beklemeli

Bir ekip CacheDB'yi doğru kullanirsa production resmi genelde söyle olur:

- varsayılan is kodu generated domain veya binding surface kullanir
- sıcak path'ler projection ve explicit fetch limit ile kurulur
- global sorted/range ekranları, geniş multi-sort entity query yerine projection'a özel ranked alan kullanır
- bu ranked alanlar `rankedBy(...)` ile tanımlanir; boylece projection repository top-window fast path kullanabilir
- sadece kanıtlanmış hotspot'lar doğrudan repository'ye iner
- foreground repository trafigi background worker/admin trafiginden ayrilir

Bir ekip CacheDB'yi kötü kullanirsa sorun genelde söyle görünur:

- liste ekranlarında geniş aggregate hydrate eder
- Redis'i sihirli ve bedava sanir
- projection kullanmaz
- foreground ve background yollarini aynı Redis pool'da toplar
- tüm kodu ölçmeden minimal repository stiline indirir

CacheDB explicitligi odullendirir. Object graph'in bedelsiz olduğunu varsaymayi odullendirmez.

## Önerilen Geçis Yolu

Bir ekip JPA/Hibernate'ten geliyorsa şu geçis yolunu izle:

1. `GeneratedCacheModule.using(session)...` ile başla
2. CRUD ve normal servis endpoint'lerini generated surface üzerinde birak
3. Liste ekranlarıni projection ve summary/detail pattern'ine çek
4. Preview relation'lara `withRelationLimit(...)` ekle
5. Sadece ölçulmus hotspot'lari `*CacheBinding.using(session)...` tarafına indir
6. Doğrudan repository'yi ancak profiling hala gerekli diyorsa kullan

Bu yol onboarding'i kolay tutarken düşük-overhead hedefini de korur.

## Surface Seçimi

Bunu ekipler için varsayılan kural seti gibi kullan:

| Ekip veya yuk tipi | Önerilen surface |
| --- | --- |
| Normal urun servis kodu | `GeneratedCacheModule.using(session)...` |
| Explicit sıcak endpoint'ler | `*CacheBinding.using(session)...` |
| Worker, replay, recovery, infra kodu | doğrudan `EntityRepository` / `ProjectionRepository` |
| Relation-ağır liste veya dashboard okumasi | projection + `withRelationLimit(...)` |

## Benchmark Dürüstlüğü

Bu repo içindeki recipe benchmark bilerek dar kapsamli.

Kanıtledigi şey şu:

- generated ergonomi, doğrudan repository kullanımiyla aynı düşük-overhead bandinda kalabiliyor

Kanıtlemadigi şey ise şu:

- CacheDB'nin her yukte Hibernate'den hızli olduğu
- Redis latency'nin ortadan kalktigi
- relation-ağır ekranların read-model disiplini olmadan ucuz olacagi

Bu benchmark'i marketing masali için değil, API-surface dürüstlüğü için kullan.

## Repo İçindeki Güncel Kanıt

Son recipe benchmark özeti:

- `Generated entity binding`: güncel yerel koşuda ortalamada en hızli
- `Minimal repository`: güncel yerel koşuda en düşük p95
- `JPA-style domain module`: gruplanmis ergonomik surface, makul wrapper maliyeti

Buradaki asil sonuc:

- ergonomik surface bedelsiz değil
- ama doğrudan repository yoluna yeterince yakın; bu yuzden çoğu ekip okunabilirlikten erken vazgecmemeli

## Sonraki Okuma

- [Production Recipes](./production-recipes.md)
- [Spring Boot Starter](./spring-boot-starter.md)
- [Tuning Parameters](./tuning-parameters.md)
- [Production Tests](../../cachedb-production-tests/README.md)

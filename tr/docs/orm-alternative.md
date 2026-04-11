# CacheDB Bir ORM Alternatifi Olarak

Bu sayfa tek bir soruya disariya donuk kisa bir cevap verir:

Bir ekip, geleneksel JPA/Hibernate benzeri ORM yerine ne zaman CacheDB secmeli?

Cevap projenin birinci onceligiyle uyumlu kalmalidir:

- production runtime overhead dusuk olmali
- kutuphane, gercek bir ORM alternatifi olacak kadar kolay kullanilabilmeli

## Kisa Cevap

Su durumlarda CacheDB sec:

- Redis gercek production mimarisinin parcasiysa, sonradan eklenmis bir detay degilse
- dusuk gecikmeli okuma, saydam iliskisel soyutlamadan daha onemliyse
- ekip relation loading, projection ve hot path konusunda explicit olmayi kabul ediyorsa
- production overhead, sade startup ve kacis hatti onemliyse

Su durumlarda Hibernate/JPA tarafinda kal:

- uygulamanin ana okuma modeli agir bicimde iliskisel join'lere dayaniyorsa
- ekip explicit runtime kontrolden cok saydam ORM davranisi istiyorsa
- lazy loading ve entity graph davranisinin buyuk olcude gorunmez kalmasi bekleniyorsa
- production darbozlarinin cogu read-path latency degil SQL modellemesi ise

## CacheDB Nedir

CacheDB, Hibernate'in birebir kopyasi olmaya calismaz.

Bu kutuphane Redis-first bir persistence kutuphanesidir:

- uygulama okuma ve yazmalari once Redis'e gider
- PostgreSQL kalici depo olarak kalir
- metadata derleme aninda uretilir
- relation loading explicit olur
- write-behind, kalicilik isini foreground path disina tasir

Bu nedenle CacheDB su gozle degerlendirilmelidir:

- explicit kontrol isteyen ekipler icin dusuk-overhead bir ORM alternatifi
- Redis-first uygulamalar icin production-odakli persistence kutuphanesi
- gercek hotspot'lar icin net bir kacis hatti birakan bir library

## Hizli Karsilastirma

| Konu | CacheDB | Geleneksel JPA / Hibernate |
| --- | --- | --- |
| Birincil okuma yolu | Redis-first | Database-first |
| Metadata modeli | Compile-time generated | Genelde runtime reflection ve ORM metadata |
| Varsayilan felsefe | Explicit kontrol | Saydam soyutlama |
| Relation loading | Explicit `FetchPlan`, loader, projection | Cogu zaman implicit lazy/eager graph davranisi |
| Hotspot kacis hatti | Binding veya dogrudan repository'ye inebilirsin | Cogu zaman ORM icinde kalinir veya custom SQL yazilir |
| En iyi uyum | Dusuk gecikmeli servisler, read-agir API'ler, Redis-merkezli sistemler | Iliskisel alanlar, SQL-merkezli sistemler, join-agir uygulamalar |
| Runtime overhead hedefi | Cok dusuk | Genelde kabul edilebilir, ama birincil tasarim hedefi degil |

## CacheDB En Cok Nerede Guclu

CacheDB su alanlarda guclu bir uyum verir:

- sicak read path'i olan urun servisleri
- projection kullanan dashboard ve liste-agir uygulamalar
- Redis'i zaten production'da birinci sinif bagimlilik olarak isleten sistemler
- reflection-agir runtime davranis istemeyen ama generated ergonomi isteyen ekipler
- normal kod ile olculmus hotspot'lari net ayirmak isteyen servisler

## Nerede Daha Kotu Bir Uyumluluk Gosterir

Ekip su seyleri istiyorsa CacheDB daha kotu bir uyum verir:

- read-model seklini buyuk oranda gizleyen bir ORM
- ekranlari varsayilan olarak genis relational join ile kurmak
- payload boyutunu dusunmeden otomatik graph traversal beklemek
- temel uygulama paterni olarak agir iliskisel raporlama

Bu tip durumlarda Hibernate/JPA daha dogal arac olabilir.

Bu mesaji acik vermek zayiflik degil; konumlandirmayi daha guvenilir hale getirir.

## Production Ekipleri Neyi Beklemeli

Bir ekip CacheDB'yi dogru kullanirsa production resmi genelde soyle olur:

- varsayilan is kodu generated domain veya binding surface kullanir
- sicak path'ler projection ve explicit fetch limit ile kurulur
- global sorted/range ekranlari genis multi-sort entity query yerine projection'e ozel ranked alan kullanir
- bu ranked alanlar `rankedBy(...)` ile tanimlanir; boylece projection repository top-window fast path kullanabilir
- sadece kanitlanmis hotspot'lar dogrudan repository'ye iner
- foreground repository trafigi background worker/admin trafiginden ayrilir

Bir ekip CacheDB'yi kotu kullanirsa sorun genelde soyle gorunur:

- liste ekranlarinda genis aggregate hydrate eder
- Redis'i sihirli ve bedava sanir
- projection kullanmaz
- foreground ve background yollarini ayni Redis pool'da toplar
- tum kodu olcmeden minimal repository stiline indirir

CacheDB explicitligi odullendirir. Object graph'in bedelsiz oldugunu varsaymayi odullendirmez.

## Onerilen Gecis Yolu

Bir ekip JPA/Hibernate'ten geliyorsa su gecis yolunu izle:

1. `GeneratedCacheModule.using(session)...` ile basla
2. CRUD ve normal servis endpoint'lerini generated surface uzerinde birak
3. Liste ekranlarini projection ve summary/detail pattern'ine cek
4. Preview relation'lara `withRelationLimit(...)` ekle
5. Sadece olculmus hotspot'lari `*CacheBinding.using(session)...` tarafina indir
6. Dogrudan repository'yi ancak profiling hala gerekli diyorsa kullan

Bu yol onboarding'i kolay tutarken dusuk-overhead hedefini de korur.

## Surface Secimi

Bunu ekipler icin varsayilan kural seti gibi kullan:

| Ekip veya yuk tipi | Onerilen surface |
| --- | --- |
| Normal urun servis kodu | `GeneratedCacheModule.using(session)...` |
| Explicit sicak endpoint'ler | `*CacheBinding.using(session)...` |
| Worker, replay, recovery, infra kodu | dogrudan `EntityRepository` / `ProjectionRepository` |
| Relation-agir liste veya dashboard okumasi | projection + `withRelationLimit(...)` |

## Benchmark Dürüstlüğü

Bu repo icindeki recipe benchmark bilerek dar kapsamli.

Kanitledigi sey su:

- generated ergonomi, dogrudan repository kullanimiyla ayni dusuk-overhead bandinda kalabiliyor

Kanitlemadigi sey ise su:

- CacheDB'nin her yukte Hibernate'den hizli oldugu
- Redis latency'nin ortadan kalktigi
- relation-agir ekranlarin read-model disiplini olmadan ucuz olacagi

Bu benchmark'i marketing masali icin degil, API-surface dürüstlüğü icin kullan.

## Repo Icindeki Guncel Kanit

Son recipe benchmark ozeti:

- `Generated entity binding`: guncel yerel kosuda ortalamada en hizli
- `Minimal repository`: guncel yerel kosuda en dusuk p95
- `JPA-style domain module`: gruplanmis ergonomik surface, makul wrapper maliyeti

Buradaki asil sonuc:

- ergonomik surface bedelsiz degil
- ama dogrudan repository yoluna yeterince yakin; bu yuzden cogu ekip okunabilirlikten erken vazgecmemeli

## Sonraki Okuma

- [Production Recipes](./production-recipes.md)
- [Spring Boot Starter](./spring-boot-starter.md)
- [Tuning Parameters](./tuning-parameters.md)
- [Production Tests](../../cachedb-production-tests/README.md)

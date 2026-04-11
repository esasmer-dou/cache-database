# Production Recipes

Bu rehber tek bir pratik soruya cevap verir:

Bir production ekip, hangi durumda hangi CacheDB surface'ini kullanmali?

Eger once daha ust seviyede konumlandirma hikayesini okumak istersen [CacheDB Bir ORM Alternatifi Olarak](./orm-alternative.md) dokumanina bak.
Repo'yu dis kullanicilara acmaya hazirlaniyorsan [Public Beta Readiness](./public-beta-readiness.md) ve [Release Checklist](./release-checklist.md) dokumanlarini da oku.

Cevap bilerek projenin birinci onceligine bagli tutuldu:

- production runtime overhead dusuk kalmali
- kutuphane, gercek bir ORM alternatifi gibi kolay kullanilmali

## 30 Saniyelik Secim

En kisa tavsiye su:

- yeni production servislerine `GeneratedCacheModule.using(session)...` ile basla
- kanitlanmis sicak endpoint'i `*CacheBinding.using(session)...` tarafina indir
- olculmus hotspot, worker ve infra akislarini dogrudan repository kullanimina cek
- relation-agir liste ve dashboard ekranlarinda projection + `withRelationLimit(...)` kullan

## Projection'in Sart Oldugu Durumlar

Su shape'lerde projection'i tavsiye degil, sart gibi dusun:

- global sorted veya range odakli liste ekranlari
- ilk boyamada tam aggregate istemeyen liste ve dashboard ekranlari
- ekranda sadece kucuk child preview gosterilen relation satirlari
- genis aday kumesi ustunde kararlı bir is siralamasi isteyen ekranlar

Ozellikle son durumda `rank_score` benzeri projection'e ozel bir siralama alani uret ve sorguyu tek bir sorted index uzerinden kur. Genis global listelerde pahali multi-sort tie-boundary maliyetinden production dostu kacis yolu budur.

Bu niyeti projection ustunde de acikca tanimla:

```java
EntityProjection<DemoOrderEntity, HighLineOrderSummaryReadModel, Long> projection =
        EntityProjection.<DemoOrderEntity, HighLineOrderSummaryReadModel, Long>builder(...)
                .rankedBy("rank_score")
                .asyncRefresh()
                .build();
```

Bu tanim, projection'in pre-ranked bir is alani tasidigini CacheDB'ye soyler ve projection repository'nin genis candidate scan'e dusmeden once ranked top-window fast path'i kullanmasini saglar.

## Karar Akisi

```mermaid
flowchart TD
    A["Normal servis endpoint'leri mi gelistiriyorsun?"] -->|Evet| B["GeneratedCacheModule.using(session) ile basla"]
    A -->|Hayir| C["Bu yol kanitlanmis hotspot veya batch/infra akisi mi?"]
    C -->|Evet| D["*CacheBinding.using(session) ya da dogrudan repository kullan"]
    C -->|Hayir| E["Ekran relation-agir veya liste-agir mi?"]
    E -->|Evet| F["Projection ve withRelationLimit(...) kullan"]
    E -->|Hayir| G["Profiling aksini soyleyene kadar GeneratedCacheModule uzerinde kal"]
```

## Karar Tablosu

| Durum | Onerilen surface | Neden | Runtime overhead profili | Ne zaman daha alta inilmeli |
| --- | --- | --- | --- | --- |
| Tipik is CRUD'u, servis katmani uygulamalari, hizli onboarding | `GeneratedCacheModule.using(session)...` | En az glue, en ORM-benzeri yol, onboarding icin en guvenli baslangic | Dusuk | Ancak gercek hotspot kanitlanirsa daha alta in |
| Package-level domain wrapper istemeyen ama generated helper isteyen ekipler | `*CacheBinding.using(session)...` | Biraz daha explicit, hala generated, hala dusuk ceremony | Cok dusuk | Tekil endpoint latency-hassas hale gelince daha alta in |
| Bilinen sicak read/write endpoint'leri, batch isleri, infra servisleri | dogrudan `EntityRepository` / `ProjectionRepository` | En kucuk wrapper yuzeyi ve tam kontrol | En dusuk | Sadece kanitlanmis hotspotlarda burada kal |
| Relation-agir okuma ekranlari | generated binding veya minimal repository + projection + relation limit | Ergonomiyi korurken genis object graph maliyetini dusurur | Dusuk ile cok dusuk arasi | Summary/detail hala hedefi tutmazsa minimal repository'ye in |
| Ic admin/reporting akisleri | generated module veya binding | Gelistirici hizi genelde nanosaniye kazancindan daha degerli | Dusuk | Cogu durumda daha alta inmek gerekmez |
| Replay/recovery/worker kodu | minimal repository | Operasyonel kodun explicit ve tahmin edilebilir kalmasi daha iyi | En dusuk | Genelde ekstra soyutlama gerekmez |

## Resmi Oneri Merdiveni

Bu surface'leri su sirayla kullan:

1. `GeneratedCacheModule.using(session)...` ile basla
2. Sicak endpoint'leri gerekirse `*CacheBinding.using(session)...` tarafina cek
3. Sadece kanitlanmis hotspot'lari dogrudan repository/projection kullanimina indir

Bu sayede uygulama kodunun buyuk bolumu ergonomik kalir, ama gercekten gerek duyulan az sayidaki yol icin net bir kacis hatti de elde tutulur.

## Cok Pod Koordinasyon Smoke'u

Yeni bir Kubernetes recetesine guvenmeden once ayni Redis/PostgreSQL cifti uzerinde local multi-instance coordination smoke'u bir kez kos:

```powershell
.\tools\ops\cluster\run-multi-instance-coordination-smoke.ps1 `
  -RedisUri "redis://default:welcome1@127.0.0.1:56379" `
  -PostgresUrl "jdbc:postgresql://127.0.0.1:55432/postgres"
```

Bu smoke su uc production-kritik davranisi dogrular:

- consumer group'lar ortak kalirken consumer name'lerin instance-unique olmasi
- singleton cleanup/history/report loop'larinin Redis leader lease ile failover yapmasi
- abandon olmus write-behind pending isin baska bir instance tarafindan claim edilip drain edilmesi

Neden onemli:

- shared Redis stream modelinde dogruluk unique consumer kimligine baglidir
- singleton ops loop'lar gercekten singleton kalinca cluster gurultusu dusuk kalir
- gercek cok pod deploy oncesi koordinasyon regressions yakalamanin en hizli yolu budur

Local not:

- ayni workstation uzerinde `HOSTNAME` genelde tum process'ler icin aynidir
- Kubernetes pod'larinda bu problem yoktur, cunku pod hostname'leri zaten unique gelir
- localde birden fazla process'i elle kaldiriyorsan acik `cachedb.runtime.instance-id` degerleri ver ya da yukaridaki smoke runner'i kullan

## Benchmark Ne Anlama Geliyor

Resmi recipe benchmark su uc CacheDB kullanim stilini ayni repository yolu uzerinde karsilastirir:

- `JPA-style domain module`
- `Generated entity binding`
- `Minimal repository`

Calistirmak icin:

```powershell
mvn -q -f cachedb-production-tests/pom.xml exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.RepositoryRecipeBenchmarkMain"
```

Cikti:

- `target/cachedb-prodtest-reports/repository-recipe-comparison.md`
- `target/cachedb-prodtest-reports/repository-recipe-comparison.json`

Onemli not:

- bu benchmark CacheDB API-surface overhead'ini olcer
- dis Hibernate/JPA runtime maliyetini olcmez
- Redis/PostgreSQL uzerindeki end-to-end production senaryo kosularinin yerine gecmez

Generated-surface cache iyilestirmesinden sonraki son yerel olcum ozetimiz:

- `Generated entity binding`: guncel yerel kosuda ortalamada en hizli
- `Minimal repository`: guncel yerel kosuda en dusuk p95
- `JPA-style domain module`: gruplanmis ergonomik surface, makul wrapper maliyeti

Buradaki asil cikarim:

- ergonomik surface'ler sifir maliyetli degil
- ama maliyetleri dogrudan repository kullanimiyla ayni dusuk-overhead bandinda kaliyor; bu yuzden cogu is kodunu minimal-repository stiline zorlamaya gerek yok
- production latency'nin asil suruculeri hala query sekli, relation hydration, Redis contention ve write-behind baskisi

## Ekip Tipine Gore Hizli Tavsiye

### Urun servis ekipleri

`GeneratedCacheModule.using(session)...` ile basla.

Bu yol sana:

- en rahat onboarding deneyimini
- Spring Boot tarafinda zero-glue startup'i
- compile-time generated ergonomiyi
- normal production API'ler icin yeterince dusuk wrapper maliyetini

birlikte verir.

### Birkac sicak endpoint'i olan ekipler

Kodun buyuk kismini generated domain module uzerinde birak, sadece olculmus hotspot'i `*CacheBinding.using(session)...` tarafina cek.

Bu genelde en iyi orta noktadir, cunku:

- kodun geri kalani okunakli kalir
- sicak endpoint daha kucuk bir wrapper yuzeyi alir
- tum kodu erken davranip alt seviye repository stiline indirmezsin

### Platform, worker ve operasyon ekipleri

Dogrudan `EntityRepository` / `ProjectionRepository` kullan.

Bu yol su durumlarda daha dogru:

- kod urun endpoint'inden cok operasyonel akis ise
- helper ergonomisinden cok aciklik gerekiyorsa
- replay, repair veya batch mantiginda en kucuk abstraction yuzeyi isteniyorsa

## JPA/Hibernate'ten Gecis Yolu

Bir ekip JPA/Hibernate aliskanligindan geliyorsa onu bir anda minimal repository stiline zorlama.

Bunun yerine su gecis yolunu kullan:

1. `GeneratedCacheModule.using(session)...` ile basla
2. Genis eager read'leri projection + explicit detail fetch modeline cek
3. Preview ekranlarinda `withRelationLimit(...)` ekle
4. Sadece kanitlanmis hotspot'lari `*CacheBinding.using(session)...` tarafina indir
5. Dogrudan repository stilini ancak profiling hala gerekli diyorsa kullan

Bu yol ekiplerin zihinsel modelini tamamen bozmaz ama daha dusuk overhead'li query sekillerine yonlendirir.

## Recipe'ler

### Recipe 1: Varsayilan Servis Ekibi

Su durumlarda kullan:

- hizli onboarding istiyorsan
- ekip JPA/Hibernate benzeri calisma aliskanligindan geliyorsa
- endpoint'lerin cogu normal CRUD veya filtreli liste ise

Onerilen surface:

```java
var domain = com.reactor.cachedb.examples.entity.GeneratedCacheModule.using(session);
List<UserEntity> activeUsers = domain.users().queries().activeUsers(25);
```

Neden bu varsayilan:

- compile-time generated
- reflection scan yok
- runtime metadata discovery yok
- wrapper overhead'i production icin yeterince dusuk

### Recipe 2: Sicak Endpoint, Daha Explicit Entity Odagi

Su durumda kullan:

- tek bir ekran veya API latency-hassas olduysa
- hala generated helper kullanmak istiyorsan
- package-level module'den biraz daha explicit olmak istiyorsan

Onerilen surface:

```java
var users = UserEntityCacheBinding.using(session);
List<UserEntity> activeUsers = users.queries().activeUsers(25);
```

Neden:

- bir grouping katmani daha az
- entity kontratinin sahibi daha net
- hala compile-time generated ve dusuk ceremony

### Recipe 3: Relation-Agir Read Model

Su durumda kullan:

- order summary, preview line veya dashboard row benzeri ekranlarin varsa
- full entity hydration pahaliya mal oluyorsa
- ekran ilk boyamada tum aggregate'i istemiyorsa

Onerilen pattern:

1. Summaries tarafini projection repository ile query et
2. Detail'i ihtiyac oldugunda acikca yukle
3. Relation preview'leri `withRelationLimit(...)` ile sinirla
4. Global top-N veya threshold odakli ekranlarda genis multi-sort entity query yerine projection'e ozel ranked alan kullan
5. Bu ranked alani `rankedBy(...)` ile isaretle ki projection repository projection'e ozel top-window yolunu kullanabilsin

Ornek:

```java
ProjectionRepository<OrderSummaryReadModel, Long> summaries =
        DemoOrderEntityCacheBinding.using(session).projections().orderSummary();

List<OrderSummaryReadModel> topOrders =
        DemoOrderEntityCacheBinding.topCustomerOrders(summaries, customerId, 24);

EntityRepository<DemoOrderEntity, Long> previewRepository =
        DemoOrderEntityCacheBinding.using(session).fetches().orderLinesPreview(8);
```

Eger ekran tum veri kumesi ustunde "line count'a gore en buyuk siparisler, sonra revenue" gibi bir global siralama istiyorsa, tam entity query'yi daha da zorlamaya calisma. Projection'e ozel rank alani ekle ve bu projection'i tek sorted index ile query et.

Neden:

- ilk okumada genis object graph olusmasini engeller
- Redis payload ve decode maliyetini dusurur
- uygulama ekibi icin API dogal kalir

Olculmus destek:

- summary list, preview list ve full aggregate list materialization maliyetini repo icinde karsilastirmak istiyorsan `cachedb-production-tests` altindaki `ReadShapeBenchmarkMain` yuzeyini kullan
- ranked projection top-window ile genis candidate scan farkini repo icinde karsilastirmak istiyorsan `RankedProjectionBenchmarkMain` yuzeyini kullan
- bu benchmark bilerek uygulama katmani odaklidir; yani end-to-end Redis/PostgreSQL senaryo kosularinin yerine degil, yanina kullanilmalidir

### Recipe 4: Kanitlanmis Hotspot veya Batch Dongusu

Bunu sadece su durumda kullan:

- profiling bu endpoint'in hala sicak oldugunu gosteriyorsa
- query ve fetch plan uzerinde tam kontrol istiyorsan
- kod daha cok infra/operasyonel karakterdeyse

Onerilen surface:

```java
List<UserEntity> activeUsers = userRepository.query(
        QuerySpec.where(QueryFilter.eq("status", "ACTIVE"))
                .orderBy(QuerySort.asc("username"))
                .limitTo(25)
);
```

Neden:

- en kucuk abstraction yuzeyi
- allocation, limit ve query sekli uzerinde en net kontrol

Bedeli:

- daha fazla ceremony
- uygulama kodunda daha fazla tekrarlayan query/fetch glue

## Production Guardrail'lari

Hangi recipe'yi secersen sec, production icin su varsayilanlar hala onerilen yoldur:

- foreground repository Redis trafigi ile background worker/admin Redis trafigini ayir
- liste ekranlari ve dashboard'larda projection kullan
- eager genis relation yerine summary query + explicit detail fetch kullan
- preview ekranlarinda `withRelationLimit(...)` kullan
- global sorted/range liste ekranlarini projection-first ele al; is sirasi onemliyse pre-ranked projection alani tercih et
- generated ergonomiyi normal kod icin koru, minimal repository stilini sadece olculmus hotspot'lara sakla
- admin UI'yi ikincil dusun; sistemin ana runtime path'ini sekillendirmemeli, sadece gozlemlemeli

## Production'da Kacinilmasi Gerekenler

Su pattern'lerden kacin:

- her liste endpoint'inde tam aggregate hydration
- ilk query icinde yuzlerce relation child'i tek seferde cekmek
- foreground repository trafigi ile background worker'lari ayni Redis pool'da toplamak
- olcmek yerine tum kodu dogrudan minimal repository stiline indirmek
- Redis latency'yi sadece Redis'in kendisiyle aciklamaya calismak; cogu zaman asil maliyet query sekli ve hydration olur

## Spring Boot Recipe

Cogu production servis icin su baslangic iyidir:

```yaml
cachedb:
  enabled: true
  profile: production
  redis:
    uri: redis://127.0.0.1:6379
    background:
      enabled: true
```

Sonra generated registrar'lar entity'leri otomatik register etsin ve servis kodunda generated module veya binding surface kullanilsin.

## Cok Pod'lu Kubernetes Recipe

Birden fazla application pod ayni Redis ve ayni PostgreSQL'e baglandiginda su kurallari acik tut:

- consumer group'lari pod'lar arasinda ortak birak
- CacheDB'nin consumer adlarina otomatik instance id eklemesine izin ver
- cleanup/report/history benzeri singleton loop'lar icin Redis leader lease'i acik tut
- worker thread ve flush parallelism degerlerini pod bazli degil, cluster toplami olarak hesapla
- Redis'i koordinasyon katmaninin kritik bagimliligi olarak dusun ve durability/failover ile calistir

Onerilen baslangic:

```yaml
cachedb:
  enabled: true
  profile: production
  redis:
    uri: redis://redis:6379
    background:
      enabled: true
  runtime:
    append-instance-id-to-consumer-names: true
    leader-lease-enabled: true
```

Bu varsayilanla artik sunlar olur:

- write-behind, DLQ replay, projection refresh ve incident-delivery DLQ worker'lari ortak consumer group uzerinden yatay olceklenmeye devam eder
- consumer adlari cozulmus instance id sayesinde otomatik olarak pod-unique olur
- cleanup/report/history loop'lari Redis lease ile singleton kalir
- pod dusmesi tek basina veri kaybi anlamina gelmez; pending stream isi baska pod tarafindan claim edilebilir

Degismeyen seyler:

- Redis hala ana koordinasyon bagimliligidir
- async projection refresh hala eventual consistency tasir
- `at-least-once` delivery modelinde PostgreSQL version guard correctness'in parcasi olmaya devam eder

## Onerilen Varsayilanlar

Bir engineering playbook'a hizlica kopyalanacak kisa production kural seti istiyorsan su listeyi kullan:

- varsayilan uygulama kodu: `GeneratedCacheModule.using(session)...`
- sicak endpoint kacis hatti: `*CacheBinding.using(session)...`
- worker ve replay kodu: dogrudan repository
- liste ve dashboard okumalari: once projection, sonra gerekirse tam aggregate
- relation preview ekranlari: her zaman `withRelationLimit(...)` dusun
- daha alt seviyeye ancak profiling sonucu in

Ilgili dokumanlar:

- [Spring Boot Starter](./spring-boot-starter.md)
- [Tuning Parameters](./tuning-parameters.md)
- [Production Tests](../../cachedb-production-tests/README.md)
- [CI production evidence workflow](../../.github/workflows/production-evidence.yml)
- [CI local runner](../../tools/ci/run-production-evidence.ps1)

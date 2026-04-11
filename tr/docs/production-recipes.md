# Production Recipes

Bu rehber tek bir pratik soruya cevap verir:

Bir production ekip, hangi durumda hangi CacheDB yüzeyini kullanmalı?

Önce daha üst seviyede konumlandırma hikâyesini okumak istersen [CacheDB Bir ORM Alternatifi Olarak](./orm-alternative.md) dokümanına bak.
Repo'yu dış kullanıcılara açmaya hazırlanıyorsan [Public Beta Readiness](./public-beta-readiness.md) ve [Release Checklist](./release-checklist.md) dokümanlarını da oku.

Cevap bilerek projenin birinci önceliğine bağlı tutuldu:

- production runtime overhead düşük kalmalı
- kütüphane, gerçek bir ORM alternatifi gibi kolay kullanılmalı

## 30 Saniyelik Seçim

En kısa tavsiye şu:

- yeni production servislerine `GeneratedCacheModule.using(session)...` ile başla
- kanıtlanmış sıcak endpoint'i `*CacheBinding.using(session)...` tarafına indir
- ölçülmüş hotspot, worker ve infra akışlarını doğrudan repository kullanımına çek
- relation-ağır liste ve dashboard ekranlarında projection + `withRelationLimit(...)` kullan

## Projection'in Şart Olduğu Durumlar

Şu kullanım şekillerinde projection'i tavsiye değil, neredeyse zorunlu gibi düşün:

- global sorted veya range odaklı liste ekranları
- ilk boyamada tam aggregate istemeyen liste ve dashboard ekranları
- ekranda sadece küçük child preview gösterilen relation satırları
- geniş aday kümesi üstünde kararlı bir iş sıralaması isteyen ekranlar

Özellikle son durumda `rank_score` benzeri, projection'a özel bir sıralama alanı üret ve sorguyu tek bir sorted index üzerinden kur. Geniş global listelerde pahalı multi-sort tie-boundary maliyetinden kaçış için production dostu yol budur.

Bu niyeti projection üzerinde de açıkça tanımla:

```java
EntityProjection<DemoOrderEntity, HighLineOrderSummaryReadModel, Long> projection =
        EntityProjection.<DemoOrderEntity, HighLineOrderSummaryReadModel, Long>builder(...)
                .rankedBy("rank_score")
                .asyncRefresh()
                .build();
```

Bu tanım, projection'ın önceden sıralanmış bir iş alanı taşıdığını CacheDB'ye söyler ve projection repository'nin geniş candidate scan'e düşmeden ranked top-window fast path'i kullanmasını sağlar.

## Karar Akışı

```mermaid
flowchart TD
    A["Normal servis endpoint'leri mi geliştiriyorsun?"] -->|Evet| B["GeneratedCacheModule.using(session) ile başla"]
    A -->|Hayır| C["Bu yol kanıtlanmış hotspot veya batch/infra akışı mı?"]
    C -->|Evet| D["*CacheBinding.using(session) ya da doğrudan repository kullan"]
    C -->|Hayır| E["Ekran relation-ağır veya liste-ağır mı?"]
    E -->|Evet| F["Projection ve withRelationLimit(...) kullan"]
    E -->|Hayır| G["Profiling aksini söyleyene kadar GeneratedCacheModule üzerinde kal"]
```

## Karar Tablosu

| Durum | Önerilen surface | Neden | Runtime overhead profili | Ne zaman daha alta inilmeli |
| --- | --- | --- | --- | --- |
| Tipik iş CRUD'u, servis katmanı uygulamaları, hızlı onboarding | `GeneratedCacheModule.using(session)...` | En az glue ile en ORM-benzeri yol; onboarding için en güvenli başlangıç | Düşük | Ancak gerçek hotspot kanıtlanırsa daha alta in |
| Package-level domain wrapper istemeyen ama generated helper isteyen ekipler | `*CacheBinding.using(session)...` | Biraz daha explicit, hala generated, hala düşük ceremony | Çok düşük | Tekil endpoint latency-hassas hale gelince daha alta in |
| Bilinen sıcak read/write endpoint'leri, batch işleri, infra servisleri | doğrudan `EntityRepository` / `ProjectionRepository` | En küçük wrapper yüzeyi ve tam kontrol | En düşük | Sadece kanıtlanmış hotspotlarda burada kal |
| Relation-ağır okuma ekranları | generated binding veya minimal repository + projection + relation limit | Ergonomiyi korurken geniş object graph maliyetini düşürür | Düşük ile çok düşük arası | Summary/detail hâlâ hedefi tutmazsa minimal repository'ye in |
| İç admin/reporting akışları | generated module veya binding | Geliştirici hızı genelde nanosaniye kazancından daha değerlidir | Düşük | Çoğu durumda daha alta inmek gerekmez |
| Replay/recovery/worker kodu | minimal repository | Operasyonel kodun explicit ve tahmin edilebilir kalması daha iyi | En düşük | Genelde ekstra soyutlama gerekmez |

## Resmi Öneri Merdiveni

Bu yüzeyleri şu sırayla kullan:

1. `GeneratedCacheModule.using(session)...` ile başla
2. Sıcak endpoint'leri gerekirse `*CacheBinding.using(session)...` tarafına çek
3. Sadece kanıtlanmış hotspot'ları doğrudan repository/projection kullanımına indir

Bu sayede uygulama kodunun büyük bölümü ergonomik kalır; gerçekten gerek duyulan az sayıdaki yol için de net bir kaçış hattı korunur.

## Çok Pod Koordinasyon Smoke'u

Yeni bir Kubernetes reçetesine güvenmeden önce aynı Redis/PostgreSQL çifti üzerinde local multi-instance coordination smoke'u bir kez koş:

```powershell
.\tools\ops\cluster\run-multi-instance-coordination-smoke.ps1 `
  -RedisUri "redis://default:welcome1@127.0.0.1:56379" `
  -PostgresUrl "jdbc:postgresql://127.0.0.1:55432/postgres"
```

Bu smoke şu üç production-kritik davranışı doğrular:

- consumer group'lar ortak kalırken consumer name'lerin instance-unique olması
- singleton cleanup/history/report loop'larının Redis leader lease ile failover yapması
- abandon olmuş write-behind pending işin başka bir instance tarafından claim edilip drain edilmesi

Neden önemli:

- shared Redis stream modelinde doğruluk unique consumer kimliğine bağlıdır
- singleton ops loop'lar gerçekten singleton kalınca cluster gürültüsü düşük kalır
- gerçek çok pod deploy öncesi koordinasyon regressions yakalamanın en hızlı yolu budur

Local not:

- aynı workstation üzerinde `HOSTNAME` genelde tüm process'ler için aynıdır
- Kubernetes pod'larında bu problem yoktur; pod hostname'leri zaten unique gelir
- localde birden fazla process'i elle kaldırıyorsan açık `cachedb.runtime.instance-id` değerleri ver ya da yukarıdaki smoke runner'ı kullan

## Benchmark Ne Anlama Geliyor

Resmi recipe benchmark şu üç CacheDB kullanım stilini aynı repository yolu üzerinde karşılaştırır:

- `JPA-style domain module`
- `Generated entity binding`
- `Minimal repository`

Çalıştırmak için:

```powershell
mvn -q -f cachedb-production-tests/pom.xml exec:java `
  "-Dexec.mainClass=com.reactor.cachedb.prodtest.scenario.RepositoryRecipeBenchmarkMain"
```

Çıkti:

- `target/cachedb-prodtest-reports/repository-recipe-comparison.md`
- `target/cachedb-prodtest-reports/repository-recipe-comparison.json`

Önemli not:

- bu benchmark CacheDB API-surface overhead'ini ölçer
- dış Hibernate/JPA runtime maliyetini ölçmez
- Redis/PostgreSQL üzerindeki end-to-end production senaryo koşularinin yerine geçmez

Generated-surface cache iyilestirmesinden sonraki son yerel ölçum özetimiz:

- `Generated entity binding`: güncel yerel koşuda ortalamada en hızli
- `Minimal repository`: güncel yerel koşuda en düşük p95
- `JPA-style domain module`: gruplanmis ergonomik surface, makul wrapper maliyeti

Buradaki asil çıkarim:

- ergonomik surface'ler sifir maliyetli değil
- ama maliyetleri doğrudan repository kullanımiyla aynı düşük-overhead bandinda kaliyor; bu yuzden çoğu is kodunu minimal-repository stiline zorlamaya gerek yok
- production latency'nin asil suruculeri hala query sekli, relation hydration, Redis contention ve write-behind baskisi

## Ekip Tipine Göre Hızli Tavsiye

### Urun servis ekipleri

`GeneratedCacheModule.using(session)...` ile başla.

Bu yol sana:

- en rahat onboarding deneyimini
- Spring Boot tarafinda zero-glue startup'i
- compile-time generated ergonomiyi
- normal production API'ler için yeterince düşük wrapper maliyetini

birlikte verir.

### Birkac sıcak endpoint'i olan ekipler

Kodun büyük kısmını generated domain module üzerinde bırak, sadece ölçülmüş hotspot'i `*CacheBinding.using(session)...` tarafına çek.

Bu genelde en iyi orta noktadir, çünkü:

- kodun geri kalani okunakli kalir
- sıcak endpoint daha küçük bir wrapper yüzeyi alir
- tüm kodu erken davranip alt seviye repository stiline indirmezsin

### Platform, worker ve operasyon ekipleri

Doğrudan `EntityRepository` / `ProjectionRepository` kullan.

Bu yol şu durumlarda daha doğru:

- kod urun endpoint'inden çok operasyonel akış ise
- helper ergonomisinden çok açıklik gerekiyorsa
- replay, repair veya batch mantiginda en küçük abstraction yüzeyi isteniyorsa

## JPA/Hibernate'ten Geçis Yolu

Bir ekip JPA/Hibernate aliskanligindan geliyorsa onu bir anda minimal repository stiline zorlama.

Bunun yerine şu geçis yolunu kullan:

1. `GeneratedCacheModule.using(session)...` ile başla
2. Geniş eager read'leri projection + explicit detail fetch modeline çek
3. Preview ekranlarında `withRelationLimit(...)` ekle
4. Sadece kanıtlanmış hotspot'lari `*CacheBinding.using(session)...` tarafına indir
5. Doğrudan repository stilini ancak profiling hala gerekli diyorsa kullan

Bu yol ekiplerin zihinsel modelini tamamen bozmaz ama daha düşük overhead'li query sekillerine yonlendirir.

## Recipe'ler

### Recipe 1: Varsayılan Servis Ekibi

Şu durumlarda kullan:

- hızli onboarding istiyorsan
- ekip JPA/Hibernate benzeri çalışma aliskanligindan geliyorsa
- endpoint'lerin çoğu normal CRUD veya filtreli liste ise

Önerilen surface:

```java
var domain = com.reactor.cachedb.examples.entity.GeneratedCacheModule.using(session);
List<UserEntity> activeUsers = domain.users().queries().activeUsers(25);
```

Neden bu varsayılan:

- compile-time generated
- reflection scan yok
- runtime metadata discovery yok
- wrapper overhead'i production için yeterince düşük

### Recipe 2: Sıcak Endpoint, Daha Explicit Entity Odagi

Şu durumda kullan:

- tek bir ekran veya API latency-hassas olduysa
- hala generated helper kullanmak istiyorsan
- package-level module'den biraz daha explicit olmak istiyorsan

Önerilen surface:

```java
var users = UserEntityCacheBinding.using(session);
List<UserEntity> activeUsers = users.queries().activeUsers(25);
```

Neden:

- bir grouping katmanı daha az
- entity kontratinin sahibi daha net
- hala compile-time generated ve düşük ceremony

### Recipe 3: Relation-Ağır Read Model

Şu durumda kullan:

- order summary, preview line veya dashboard row benzeri ekranların varsa
- full entity hydration pahaliya mal oluyorsa
- ekran ilk boyamada tüm aggregate'i istemiyorsa

Önerilen pattern:

1. Summaries tarafini projection repository ile query et
2. Detail'i ihtiyaç olduğunda açıkça yükle
3. Relation preview'leri `withRelationLimit(...)` ile sinirla
4. Global top-N veya threshold odaklı ekranlarda geniş multi-sort entity query yerine projection'e özel ranked alan kullan
5. Bu ranked alani `rankedBy(...)` ile isaretle ki projection repository projection'e özel top-window yolunu kullanabilsin

Örnek:

```java
ProjectionRepository<OrderSummaryReadModel, Long> summaries =
        DemoOrderEntityCacheBinding.using(session).projections().orderSummary();

List<OrderSummaryReadModel> topOrders =
        DemoOrderEntityCacheBinding.topCustomerOrders(summaries, customerId, 24);

EntityRepository<DemoOrderEntity, Long> previewRepository =
        DemoOrderEntityCacheBinding.using(session).fetches().orderLinesPreview(8);
```

Eğer ekran tüm veri kümesi üstunde "line count'a göre en büyük siparisler, sonra revenue" gibi bir global sıralama istiyorsa, tam entity query'yi daha da zorlamaya çalışma. Projection'e özel rank alani ekle ve bu projection'i tek sorted index ile query et.

Neden:

- ilk okumada geniş object graph oluşmasini engeller
- Redis payload ve decode maliyetini düşurur
- uygulama ekibi için API dogal kalir

Ölçülmüş destek:

- summary list, preview list ve full aggregate list materialization maliyetini repo içinde karşılastirmak istiyorsan `cachedb-production-tests` altındaki `ReadShapeBenchmarkMain` yüzeyini kullan
- ranked projection top-window ile geniş candidate scan farkini repo içinde karşılastirmak istiyorsan `RankedProjectionBenchmarkMain` yüzeyini kullan
- bu benchmark bilerek uygulama katmanı odaklıdır; yani end-to-end Redis/PostgreSQL senaryo koşularının yerine değil, yanına kullanılmalıdır

### Recipe 4: Kanıtlanmış Hotspot veya Batch Döngüsu

Bunu sadece şu durumda kullan:

- profiling bu endpoint'in hala sıcak olduğunu gösteriyorsa
- query ve fetch plan üzerinde tam kontrol istiyorsan
- kod daha çok infra/operasyonel karakterdeyse

Önerilen surface:

```java
List<UserEntity> activeUsers = userRepository.query(
        QuerySpec.where(QueryFilter.eq("status", "ACTIVE"))
                .orderBy(QuerySort.asc("username"))
                .limitTo(25)
);
```

Neden:

- en küçük abstraction yüzeyi
- allocation, limit ve query sekli üzerinde en net kontrol

Bedeli:

- daha fazla ceremony
- uygulama kodunda daha fazla tekrarlayan query/fetch glue

## Production Guardrail'lari

Hangi recipe'yi seçersen seç, production için şu varsayılanlar hala önerilen yoldur:

- foreground repository Redis trafigi ile background worker/admin Redis trafigini ayir
- liste ekranları ve dashboard'larda projection kullan
- eager geniş relation yerine summary query + explicit detail fetch kullan
- preview ekranlarında `withRelationLimit(...)` kullan
- global sorted/range liste ekranlarıni projection-first ele al; is sirasi önemliyse pre-ranked projection alani tercih et
- generated ergonomiyi normal kod için koru, minimal repository stilini sadece ölçülmüş hotspot'lara sakla
- admin UI'yi ikincil düşün; sistemin ana runtime path'ini sekillendirmemeli, sadece gözlemlemeli

## Production'da Kacinilmasi Gerekenler

Şu pattern'lerden kacin:

- her liste endpoint'inde tam aggregate hydration
- ilk query içinde yuzlerce relation child'i tek seferde çekmek
- foreground repository trafigi ile background worker'lari aynı Redis pool'da toplamak
- ölçmek yerine tüm kodu doğrudan minimal repository stiline indirmek
- Redis latency'yi sadece Redis'in kendisiyle açıklamaya çalışmak; çoğu zaman asil maliyet query sekli ve hydration olur

## Spring Boot Recipe

Çoğu production servis için şu başlangic iyidir:

```yaml
cachedb:
  enabled: true
  profile: production
  redis:
    uri: redis://127.0.0.1:6379
    background:
      enabled: true
```

Sonra generated registrar'lar entity'leri otomatik register etsin ve servis kodunda generated module veya binding surface kullanılsin.

## Çok Pod'lu Kubernetes Recipe

Birden fazla application pod aynı Redis ve aynı PostgreSQL'e bağlandiginda şu kurallari açık tut:

- consumer group'lari pod'lar arasinda ortak birak
- CacheDB'nin consumer adlarına otomatik instance id eklemesine izin ver
- cleanup/report/history benzeri singleton loop'lar için Redis leader lease'i açık tut
- worker thread ve flush parallelism değerlerini pod bazli değil, cluster toplami olarak hesapla
- Redis'i koordinasyon katmanının kritik bağımlılığı olarak düşün ve durability/failover ile çalıştır

Önerilen başlangic:

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

Bu varsayılanla artık sunlar olur:

- write-behind, DLQ replay, projection refresh ve incident-delivery DLQ worker'lari ortak consumer group üzerinden yatay ölçeklenmeye devam eder
- consumer adları çözulmus instance id sayesinde otomatik olarak pod-unique olur
- cleanup/report/history loop'lari Redis lease ile singleton kalir
- pod düşmesi tek başına veri kaybi anlamina gelmez; pending stream isi başka pod tarafından claim edilebilir

Değismeyen şeyler:

- Redis hâlâ ana koordinasyon bağımlılığıdır
- async projection refresh hala eventual consistency tasir
- `at-least-once` delivery modelinde PostgreSQL version guard correctness'in parcasi olmaya devam eder

## Önerilen Varsayılanlar

Bir engineering playbook'a hızlica kopyalanacak kısa production kural seti istiyorsan şu listeyi kullan:

- varsayılan uygulama kodu: `GeneratedCacheModule.using(session)...`
- sıcak endpoint kaçış hattı: `*CacheBinding.using(session)...`
- worker ve replay kodu: doğrudan repository
- liste ve dashboard okumalari: önce projection, sonra gerekirse tam aggregate
- relation preview ekranları: her zaman `withRelationLimit(...)` düşün
- daha alt seviyeye ancak profiling sonucu in

Ilgili dokümanlar:

- [Spring Boot Starter](./spring-boot-starter.md)
- [Tuning Parameters](./tuning-parameters.md)
- [Production Tests](../../cachedb-production-tests/README.md)
- [CI production evidence workflow](../../.github/workflows/production-evidence.yml)
- [CI local runner](../../tools/ci/run-production-evidence.ps1)

# Redis Performans Ölçümleri

Redis JMH aracı, framework'ün repository çağrılarını ölçer. Uygulamanın uçtan uca
yanıt süresini veya SQL commit süresini ölçmez. Sonuçlar yalnızca ölçümde kullanılan
JVM, CPU sınırları, Redis kurulumu ve ağ koşulları için geçerlidir.

## Ölçümler Nasıl Okunmalı?

Yük üreteci, ayarlanan çalışan sayısını korur. Tek çalışanlı karma senaryoda tek
bir karma çalışan kullanılır. Okuma ve yazma havuzlarını ayırmayı kapatmak,
okuma işlemlerini yazma işlemine dönüştürmez. Yalnız okuma veya yalnız yazma
senaryolarında da ilgili işlem türü korunur.

Ölçüm aracının ek yükü sıfır kabul edilmez. Onaylı Linux test ortamında ayrı
kontrol etmek için önce `tools/benchmarks/redis/RESOURCE-COLLECTION.md`
dosyasındaki hazırlığı tamamlayın, ardından çalıştırın:

```sh
export CACHEDB_BENCH_REDIS=127.0.0.1:6379
sh tools/benchmarks/redis/calibrate-jmh-resources.sh \
  /workspace/benchmarks.jar /workspace/evidence/observer-calibration-NEW
```

Bu kısa kontrol, aynı gerçek L1 okuma senaryosunu ve aynı JAR dosyasını dört
koşulda ölçer: ölçüm aracı olmadan, yalnız GC ölçümüyle, yalnız kaynak ölçümüyle
ve ikisi birlikte. Koşulların sırası tersine çevrilir; hiçbir sonuç elenmez.
Bu kontrol, ölçümün sisteme etkisini araştırır. Üretim kapasitesini, sıfır ek
yükü veya tüm performans kabul koşullarının sağlandığını kanıtlamaz. Ölçüm
aracının maliyeti ürünün gecikmesinden çıkarılmaz.

Ham JSON dosyasını, yanındaki `<result.json>.instrumentation` dosyasıyla birlikte
saklayın. Karşılaştırma, ham dosyanın özetini ve eşleşen senaryolarda kullanılan
ölçüm araçlarının sırasını, yardımcı dosyaların özetlerini ve örnekleme ayarlarını
denetler. Eksik veya farklı ölçüm koşulları varsa karşılaştırma reddedilir.
Güncel kabul koşulu, en az üç bağımsız JVM sürecinde beşer adet 3 saniyelik
ısınma ve ölçüm turudur. Eski bir 1 saniyelik referans, geçmişe ait geçerli bir
kayıt olabilir; ancak bu koşulu sağlamaz. Kabulden geçmesi için dosyanın ölçüm
bilgileri değiştirilmez.

`measurementVersion: 2` üretim testlerinde `achievedTransactionsPerSecond` yalnız
başarılı işlemleri sayar. `executedOperations` hata verenler dâhil toplam deneme
sayısıdır. Deneme hızı, başarılı işlem hızı ve hata gecikmeleri ayrı raporlanır.
Böylece hızlı hata veren bir sistem başarılı ve hızlı görünmez. Geçen süre,
sistem saati değişimlerinden etkilenmeyen monoton saatle hesaplanır. Asenkron
yazmanın kabul edilmesi SQL commit anlamına gelmez; kuyruk boşaltma sonucu ayrı
bir kabul ölçütüdür.

Eski gecikme alanları bütün denemelerin işlem süresini göstermeye devam eder.
İşlem arası bekleme ve test sonundaki kuyruk boşaltma bu süreye dâhil değildir.
Planlanan başlangıca göre gecikme ayrıca gösterilir. Bu test, dışarıdan sabit hızla
gelen sınırsız istekleri taklit etmez; çalışan ve istek sayısı sınırlıdır.

Histogramdaki p95 ve p99 değerleri aralıkların üst sınırıdır; tam yüzdelik hesabı
değildir. Son 5 saniyelik aralık aşıldığında gözlenen en büyük süre, ihtiyatlı üst
sınır olarak döner. Taşan örnek sayısı ve hangi yüzdeliğin bu üst sınırı kullandığı
raporda belirtilir. Örneğin 12 saniyelik gecikme artık 5 saniye olarak görünmez.
Sayaçlar her çalışanda ayrı tutulur ve çalışan bitince birleştirilir; her işlemde
ortak atomik sayaç güncellenmez.

HTTP yük kontrolü hem toplam deneme hızını hem başarılı istek hızını verir.
Gecikmeye istemci isteği, JSON çözümleme ve sonuç doğrulama dâhildir. Eski
`requestsPerSecond` alanı toplam deneme hızıdır. Başarı ve hata gecikmeleri ayrı
alanlarda; ham örnekler ise istek sırasıyla saklanır. Bu kısa kontrol, framework
mikro ölçümü veya üretim kapasite sertifikası değildir.

Beş örnekli COUNT/FACET tanılamasında `service_time_derived_ops_per_second`,
sıralı okuma sürelerinin ortalamasından hesaplanan teorik hızdır; eşzamanlı RPS
değildir. Sonuç doğrulama dâhil toplam süre, doğrulama süresi ve ham süreler de
saklanır. Buradaki p95, yalnız beş gözlemin en büyüğüdür. Ağ sayaçları Redis
geneline aittir ve gözlemci trafiğini de içerir. Bu sonuçlar, ham sorgular için
T4 kabul koşullarının yerine geçmez.

| Bellek ölçümü | Ne gösterir? | Ne göstermez? |
| --- | --- | --- |
| JMH `gc.alloc.rate.norm` | İşlem başına ayrılan bayt | Canlı heap veya RSS |
| Süreç RSS / VmHWM | Sürecin yaklaşık yerleşik belleği / ömrü boyunca en yüksek değer | Yalnız ölçüm anındaki kesin tepe |
| Cgroup current / peak / events | Container grubunun kullanımı ve olayları | Tek bir JVM'nin belleği |
| Redis `used_memory` / `used_memory_rss` | Redis tahsis hesabı / yerleşik bellek | İstemci JVM belleği |
| Anahtar `MEMORY USAGE` | Tek Redis anahtarının yaklaşık maliyeti | Bütün indeksler veya sunucunun toplam belleği |

Denetleyici JVM, alt JVM ve Redis ayrı kimliklerle izlenmelidir. Alınamayan ölçüm
sıfır değil, kullanılamıyor olarak işaretlenir. Örnekleme aralığındaki en büyük
değer gerçek tepeyi kaçırabilir. VmHWM ve cgroup peak hazırlık aşamasını da
içerebilir. Zamanlanan aşamada bütün dizinin boyutunu taramayın; zorunlu GC, JFR
ve NMT eklenirse bunu ayrı, araçlarla izlenen deney olarak adlandırın. CPU
isteği ve limiti ayrılmış çekirdek değildir. Daraltılma sayaçları doğru container
ve aşama için ayrı değerlendirilmelidir.

`findAllMixed100` tek bir 100 kimlikli çağrıdır; 100 bağımsız istek değildir.
JMH, çağrı öncesi hazırlığı ana süreye katmaz. Buna karşılık tur genelindeki
bellek tahsisi bu hazırlığın maliyetini içerebilir. Bu nedenle tahsis değeri
yalnız repository metoduna aitmiş gibi yorumlanmamalıdır.

JMH karşılaştırma eşikleri nokta tahminlerine dayanan regresyon kontrolleridir;
istatistiksel anlamlılık testi değildir. Ham JVM koşuları, örnek sayıları ve güven
aralıkları ayrıca incelenmelidir. Ortalamanın güven aralığı p99'un güven aralığı
değildir. Bu açıklamalar geçmiş sonuçları değiştirmez ve başarısız ölçümü başarılı
hâle getirmez.

## 0.19.0 Adayının Ölçüm Kanıtı

Okuma birleştirme, AYNI adayda açık ve kapalı ayarları karşılaştırır; bu sonuçlar 0.19.0 ile 0.18.1
arasındaki fark değildir. Korunan 16 hücreli matris, işlem hacmi ve örneklenmiş gecikme modlarında
üç ayrı JVM süreci, beşer üç saniyelik hazırlık ve ölçüm turu kullanır. Denetleyici ve alt JVM'nin
heap sınırı 512 MiB, `ActiveProcessorCount=1` ve işlemci sabitlemesi tek çekirdektir; pod CPU kotası
bir değil, iki çekirdektir. Bağlantı havuzu adil ve 32 bağlantılıdır; AUTO azami eşzamanlı işlem
sayısı 16, birleştirmenin varsayılanı `false` değeridir.

| Okuma birleştirme senaryosu | Kaydedilen açık/kapalı oranı | Ölçüt |
| --- | ---: | --- |
| 64 platform çalışanı, işlem hacmi | 2.2748x | PASS: isteğe bağlı kullanım için >=2x |
| 1000 sanal çalışan, işlem hacmi | 3.8490x | PASS: isteğe bağlı kullanım için >=2x |
| 1 platform çalışanı, tekil okuma p50 | 1.4775x | FAIL_DEFAULT_ENABLED: <=1.03 sınırının üzerinde |

Tek çalışan sonucu, %3 ölçütünün geçilmesi değil, p50 gecikmesinde %47,75 artıştır. Sonuç
**yalnızca isteğe bağlı kullanım için sayısal PASS** durumudur: birleştirmenin varsayılanı
`false` kalmak zorundadır ve `FAIL_DEFAULT_ENABLED` sonucu korunur. Bu, sürüm veya sağlayıcı
kabulü değildir.

| Örneklenmiş p99 senaryosu | Kaydedilen açık/kapalı oranı |
| --- | ---: |
| 1 platform çalışanı | 1.2409x |
| 16 platform çalışanı | 22.2543x |
| 64 platform çalışanı | 2.5753x |
| 1000 sanal çalışan | 0.5163x |

İşlem hacmi kazanımları, platform çalışanlarının p99 gerilemelerini ortadan kaldırmaz. Bu p99
oranları aynı adayda birleştirmenin açık ve kapalı durumlarını karşılaştırır; 0.18.1 ile
karşılaştırma veya sürümün gecikme kabulü değildir.

> **Paket kapsamı:** Etkin aday, ham araç JAR'ı ile doğrulanmış SDK sınıf katmanı ve
> doğrulanmış JMH örnek birleştirme yardımcısının birleşimidir. Ham JAR tek başına
> bu adayı doğrulamaz.
> Ham JAR SHA-256: `9e1347946bd2b7eed0b971fdcdfef228c210fee380c2d942b7d21ee800c1f7a3`.
> Yardımcı SHA-256: `9483f3b96d0b4c7445485e5999720d06c73ce9e58accd6a51b80cd4787c14305`.
> Kanıt girdi manifesti SHA-256:
> `aafc9f94947bd65743cb31f6b09276af4474fa59f1f3b9aa61461192579a8f07`.
> Ham matrisi, paket bağlarını ve kaynak/kaynak kullanım manifestlerini birlikte koruyun.
> Korunan kanıt V3 şemasındadır; resmî V4 doğrulaması yeni ölçüm gerektirir ve geçmişe
> uygulanamaz. Her değişiklikten sonra etkin paket birleşimini yeniden doğrulayın.

<a id="ham-t4-icin-surum-kabulu"></a>

### Ham T4 İçin Sürüm Kabulü

Ham kiracılı/kiracısız COUNT/FACET maddesi, 9 Kasım 2026 tarihli sürüm kabul kararıyla
mevcut uygulaması üzerinden **PASS / KABUL İSTİSNASI** olarak kapatılmıştır. 0.19.0 için
zorunlu iki kat işlem hacmi şartı kaldırılmıştır; hedefin ölçümde sağlandığı veya teknik
olarak imkânsız olduğu iddia edilmez. Aşağıdaki geçmiş sonuçlar, ham kanıtlar, denetleyici
eşikleri ve çıkış kodları korunur. Bunlar bugünkü kabul kararını değil, ilgili koşu sırasında
geçerli olan ölçütleri gösterir. Yalnız kaldırılan hedefi izlemek için değişmemiş matrisleri
yeniden çalıştırmayın; geçmiş sayısal FAIL sonucunu ölçülmüş PASS olarak değiştirmeyin.

Gerçek sahiplik, kayıt varlığı/silinme işareti, sınırlı okuma, hataların aktarılması ve nihai
paketin olağan regresyon kontrolleri zorunludur. Okuma birleştirme ve gözlem eşikleri dâhil
diğer performans koşulları değişmez. Read-model güncelliği ayrı bir API sözleşmesidir.
Bu istisna, nihai sürüm onayı veya son COUNT paketinin ölçülmüş performans kabulü değildir.

Kiracılı ve kiracısız aggregateCount10k ile facet10k20Groups için korunan 0.18.1 referansına göre
>=2x işlem hacmi karşılaştırmasının geçmiş sayısal sonucu **FAIL** olarak korunur. Aşağıdaki
tarihsel eşlenmiş koşu ve sonraki tanılama ölçümü, o sırada geçerli sayısal ölçütleri geçememiştir.
Sonuçların eşleşmesi veya istemcinin daha az ağ isteği
yapması, işlem hacminin arttığını göstermez. Gerçek sahiplik denetimleri, metadata boyut sınırları ve
Redis işlemleri CPU tüketir. Hedefe ulaşmak için bu doğruluk denetimlerini kaldırmayın. Paket
değişirse yeni ve eksiksiz eşlenmiş karşılaştırma gerekir.
Sürümlü Function kitaplıklarını yan yana koruyun; mevcut gövdeyi değiştirmeyin ve başka bir paketin
sonucunu yeni pakete taşımayın. Bkz. [Function geçiş sözleşmeleri](redis-functions-upgrade.md).

0.18.1 kaynak kodundaki indeksli kiracı COUNT akışı, kiracı indeksine üyeliği yeterli görür.
Aday sürüm ayrıca kaydın gerçek sahipliğini denetler. Bu ek denetim gereklidir: güncelliğini yitirmiş
veya sonradan eklenmiş bir indeks üyeliği, başka kiracıya ait kaydı okumaya yetki vermemelidir.
Denetimi kaldırmak yalnızca sorguyu hızlandırmaz; güvenlik sözleşmesini de değiştirir. Performans
karşılaştırmasındaki kaynak kodu farkı, tek başına geçmiş eşikten muafiyet sağlamaz ve ölçülen
JAR'ın hangi kaynaktan üretildiğini kanıtlamaz. Yukarıdaki sonraki sürüm istisnası ayrı bir kabul kararıdır.

Birden çok pencereyi okuyan akış, Redis komutlarını zaten toplu gönderir. Daha az komut, mutlaka
daha az ağ gidiş gelişi veya daha az sunucu işi anlamına gelmez. Metadata değeri okunmadan önce
boyutu denetlenmeli; bozulmuş bir sahiplik değeri de sınırsız okunmamalıdır. İyileştirme seçmeden
önce istemci bellek tahsislerini, Redis komut sürelerini ve CPU kısıtlamasını ayrı ölçün.
İşlevsel testin geçmesi veya Lua betiğinin kısalması, hızlanmayı kanıtlamaz.

### T4 Pair508: Yalnızca Ölçülen Paketler İçin FAIL

5 Kasım 2026 tarihinde 12:28:01 ile 12:57:14 UTC arasında tamamlanan eşlenmiş koşu, 16 JMH hücresi
ve 48 ayrı JVM sürecini kapsar. Her hücrede üç süreç, beşer üç saniyelik hazırlık ve ölçüm turu,
tek çalışan, 512 MiB heap, `ActiveProcessorCount=1` ve GC profili kullanılmıştır. Bu kanıt yalnızca
aşağıda tanımlanan paketlere aittir; genel bir sürüm karşılaştırması veya sürüm kabulü değildir.

Oranlar aday/referans olarak hesaplanmıştır. Her senaryoda işlem hacmi >=2x ve örneklenmiş p99
<=1.15x olmalıdır. Ham işlem hacmi birimi `ops/us`, p99 dahil örneklenmiş gecikme birimi `us/op`
değeridir. p99 oranları, ortalama gecikmeden veya işlem hacmi yüzdeliklerinden değil, sample-time
modunun 99. yüzdeliklerinden hesaplanmıştır.

| Senaryo | İşlem hacmi oranı, >=2x | Örneklenmiş p99 oranı, <=1.15x | Sonuç |
| --- | ---: | ---: | --- |
| COUNT, kiracısız | 2.661406x | 0.471400x | Yalnızca bu senaryo PASS |
| COUNT, kiracılı | 0.683083x | 1.526615x | FAIL |
| FACET, kiracısız | 0.617836x | 1.579139x | FAIL |
| FACET, kiracılı | 0.715524x | 1.378820x | FAIL |

Özgün `count.exit`, `facet.exit` ve `preservation.exit` değerleri 0; `numeric-gate.exit` ve `run.exit`
değerleri 1 olarak korunur. Ölçümün çalışıp tamamlanması, sayısal FAIL sonucunu kabule dönüştürmez.
Bağımsız yerel arşiv doğrulaması, dört ham JSON dahil korunan 22 özgün dosyanın tüm özetlerini
yeniden hesaplamış ve 21 dosyayı 50 dosyalı özgün yayım manifestiyle eşleştirmiştir. Diğer 29 dosya
ve ölçüm JAR'larının baytları, bağımsız özet doğrulaması için bu alt kümede bulunmamaktadır.

- Gerçek 0.18.1 referans JAR kanıtı SHA-256: `ae359de4220b0ccfedd126769ac40192421f98bf79380943fd9dc66d106c6066`.
- Aday508 JAR kanıtı SHA-256: `aa40be02feaf5895f68056740336e1d97d221b2bb3e094841d5e22523891b5b8`.
- Source508 manifesti SHA-256: `309808c664c39e926a1d5e86ee0539d7148c44fc42f0cb994a10a593c6730de7`.
- SDK508 yayımlanan92 manifesti SHA-256: `3029d7d28d5b067f13659e5c9b2118f63b0991b489dd2dfe32a3d6b9d765b7f0`.
- Özgün Pair508 yayım manifesti SHA-256: `4db712757072af8cf14417fdd850a78fefeee5867c5e379d90f48a852fe31c03`.
- Korunan alt küme arşivi SHA-256: `417537460187aadcfd9bb59ab3f84e076e0ee84450be6f1c95637658f8c62376`.

### T4 Tanılama Ölçümü: 6 Kasım 2026

Bu karşılaştırmada gerçek yayımlanmış 0.18.1 referansı ile ASCII anahtar oluşturma tahsis
iyileştirmesini içeren tanılama adayı kullanılmıştır. Sonuç, **nihai SDK'yı doğrulamaz**. Referans
ile aday arasında bu iyileştirmeden başka farklar da vardır; oranlar tek başına ASCII değişikliğinin
etkisini göstermez. Tamamlanan kanıt, aynı paket ve ayarlarla iki bölümde ölçülmüş 16 hücre ve
48 ayrı JVM sürecini kapsar; kesintisiz bir sürüm kabul koşusu değildir. Her hücrede üç süreç,
beşer üç saniyelik hazırlık ve ölçüm turu, tek çalışan, tek çekirdeğe sabitleme,
`ActiveProcessorCount=1`, 512 MiB heap ve GC profili kullanılmıştır. Redis CPU sınırı 500m'dir.
COUNT 10.000 kaydı, FACET aynı kayıt sayısını ve 20 grubu kapsar.

Oranlar aday/referanstır: **dört senaryonun tamamında** işlem hacmi >=2x ve sample-time p99
<=1.15x olmalıdır. Ham birimler `ops/us` ve `us/op` olarak korunur; oranlar boyutsuzdur.

| Senaryo | İşlem hacmi oranı, >=2x | Örneklenmiş p99 oranı, <=1.15x | Sonuç |
| --- | ---: | ---: | --- |
| COUNT, kiracısız | 4.296776x | 0.253859x | Yalnızca bu senaryo iki ölçütü karşılar |
| COUNT, kiracılı | 1.139831x | 1.016218x | FAIL: işlem hacmi |
| FACET, kiracısız | 0.852170x | 1.102839x | FAIL: işlem hacmi |
| FACET, kiracılı | 0.770940x | 1.414515x | FAIL: işlem hacmi ve p99 |

Bu karşılaştırma, yalnızca ASCII iyileştirmesini değil, ölçülen sürümler arasındaki diğer farkları da
içerir; güvenlik ve doğruluk denetimleri eşdeğer değildir. Eski indeksli kiracılı COUNT akışı, kiracı
indeksine üyeliği yeterli görür; aday sürüm ayrıca kaydın gerçek sahipliğini denetler. Aday, eski
facet akışından farklı olarak metadata değerlerini okumadan önce boyutlarını denetler. Bu farklar
kabul ölçütlerinden muafiyet sağlamaz; başarısız üç senaryonun sonucu değişmez.

**Dört senaryonun üçü, bu koşuda geçerli olan ölçütlere göre başarısızdır.** Dört karşılaştırmada da tahsis
azalmıştır; bu, işlem hacmi veya p99 gecikmesi kabulünü ya da yalnızca ASCII değişikliğinden
kaynaklanan kazanımı kanıtlamaz. Sonuçlar >=2x hedefinin imkânsız olduğunu kanıtlamaz.
Yukarıdaki sonraki sürüm istisnası, ölçümlerin başarılı olduğu iddiası değil, açık bir kabul kararıdır.

Ölçülen paketlerin SHA-256 bağları:

- Referans ölçüm JAR'ı: `ae359de4220b0ccfedd126769ac40192421f98bf79380943fd9dc66d106c6066`.
- Aday ölçüm JAR'ı: `797f05360a34636afb62204e1af7355712103f3730343993ce173d9dad787386`.
- Aday classpath'inde ilk sıradaki test edilmiş Redis runtime JAR'ı: `8f7644c441a493e26a7f3701900513acee5145588cc7595e50da308424f3da9a`.

### T4 Çalışma Profili: 7 Kasım 2026

EmaTech Kubernetes üzerinde kısa bir tanılama koşusunda standart kiracılı facet senaryosu
kullanıldı: 10.000 kayıt, 20 grup, bir JVM süreci, iki saniyelik bir hazırlık turu ve ikişer
saniyelik iki ölçüm turu. Sonuç `2.8161030559605606 işlem/sn` oldu. Bu, **referansla eşlenmiş
bir karşılaştırma, tam JMH kabulü veya sürüm kabul sonucu değildir**. Ölçüm JAR'ının SHA-256 özeti
`332ea44993020134fb9cdf506b44f6a0780d34ea0554500c80f4d60d2ac322c8`;
saklanan tanılama arşivinin özeti
`8b2a980be5d0994a406108a068b41c17e9c04384975d91f3a44bf4657147e869`.

JFR, facet yanıtı alınırken toplam yaklaşık 4,001 saniye süren 99 soket okuma olayı kaydetti.
Kaydın tamamını kapsayan aralıkta, CPU sınırı 500m olan Redis container'ında CPU kotasına
takılma sayısı 76; bu nedenle bekletilme süresi yaklaşık 3,655 saniye arttı. Kayıt; test verisi
hazırlığını, doğrulamayı, ısınmayı, ölçümü ve temizliği birlikte kapsar. Bu iki sayı yalnızca
ölçüm turlarına ait değildir; CPU kotasının tek neden olduğunu da kanıtlamaz. Az sayıdaki CPU
yığın örneğinden güvenilir bir süre yüzdesi çıkarılamaz.

İnceleme, yalnızca bellek tahsisini yeniden azaltmak yerine Redis komutlarının yürütülmesine
ve yanıt beklemelerine yönelmelidir. Bu bulgu; yalnızca adayın kaynak sınırını yükseltmeyi,
kayıt başına güvenlik kontrollerini kaldırmayı veya uygulama değişmeden tam matrisi yeniden
çalıştırmayı haklı çıkarmaz. Bu tanılama sırasında >=2x işlem hacmi ve p99 ölçütleri değişmemiş,
kabul açık kalmıştır. Güncel ham T4 durumu için yukarıdaki sonraki sürüm istisnasına bakın.

### Mimari Seçenekler: Kabul Kanıtı Değildir

**Bugün güvenli seçenek**, her kaydın gerçek varlığını, tombstone ve kiracı sahipliğini, sınırlı
okumaları ve gözlenebilir ACL/hata sırasını korumaktır. Birden çok pencere zaten aynı pipeline ile
gönderilir. Yeni protokol veya tahsis değişikliği için kaldırılabilir işin miktarı gösterilmelidir;
daha az yanıt veya daha kısa Lua betiği, daha az Redis CPU maliyetini kanıtlamaz.

**Gelecekte açıkça seçilen ayrı bir özellik**, yalnızca yazma protokolünün kontrol ettiği bir
namespace'te tanımlı facet/filtre türleri için ön toplama yapabilir. Atomik entity/indeks güncellemesi,
eski yazıcıları dışlayan yeniden oluşturma ve atomik indeks nesli yayımlama; güncelleme, silme, tombstone ve kiracı
taşımayı kapsamalıdır. Bütün yazıcılar protokole uymalıdır; indeks nesli tek başına dış değişikliği
tespit edemez. Uygun sorgularda iş O(kayıt) yerine O(grup) düzeyine yaklaşabilir; karşılığı ek yazma,
bellek ve çekişme maliyetidir. Bu, uygulanmış özellik veya ölçülmüş hızlanma değil, bir öneridir.

Uygunluk, bir indeks işaretinden varsayılmamalı; yazıcı yalıtımıyla sağlanmalıdır.
Kesin bir süre sonu protokolü yoksa izlenmeyen yazmalar ve bağımsız TTL sona ermesi uygunluk
dışında kalmalıdır; asenkron süre sonu bildirimleri yeterli değildir. Özet anahtarını okuma yetkisi,
alttaki her anahtarı okuma yetkisini kanıtlamaz. Yeni özellik için yetkilendirme açıkça tanımlanmalı;
mevcut kayıt başına ACL/hata davranışını isteyen çağrılar bu kısayola uygun sayılmamalıdır.
Kiracı yalıtımı, null/eksik grup anlamları ve çıktı bütçeleri korunmalıdır. Bilinmeyen veya uygun
olmayan indeks neslinde kısayol reddedilmeli ya da mevcut gerçek kayıt denetimi kullanılmalıdır.

İndeks üyeliğine sessizce güvenmek, metadata boyut denetimini atlamak veya değişebilir sahiplik
kanıtını önbelleğe almak **mevcut sözleşmenin optimizasyonu değildir**. Gelecekteki isteğe bağlı
özellik ayrı doğruluk ve performans kabulü gerektirir; mevcut T4 hedefini kapatamaz veya değiştiremez.

[Birleştirme profili](../../tools/benchmarks/redis/src/coalescing/README.md) ve
[isteğe bağlı özellik profili](../../tools/benchmarks/redis/src/features/README.md); ayrı derlemeleri,
sabit koşulları ve bağımsız denetleyicileri açıklar. Betiklerin bulunması, sentetik denetleyici testleri
ve işlevsel testler; gözlem/asenkron/takip performansının ölçüldüğünü göstermez. Gerçek Micrometer NOOP
ile kapalı durum arasındaki tekil okuma p50 ölçütü <=%3 olarak kalır; gerçek işleyici yükü ayrı raporlanır.
Nihai kanıtı [yayım kontrol listesinde](release-checklist.md) izleyin; sürüm kabulü ilan etmeyin.

### İsteğe Bağlı Özelliklerin Ölçümleri

Tamamlanmış 24 hücreli özellik matrisi, SHA-256 özeti
`9ac273ac590bd162dc25e4ddfabe0e20e42ac7287b763f25ec9ad6c71e835b45`
olan araç JAR'ı ile üretildi. Bağımsız denetleyici, değiştirilmeyen ham sonuçlar üzerinden tüm
süreç/tur kontrollerini ve aşağıdaki sayısal ölçütleri doğruladı. Konsol ayrıştırıcısı JMH'nin tur
önekini tanıyacak şekilde düzeltildi; önceki denetleyici hatasının kaydı değiştirilmedi.

| Özellik | Ölçülen karşılaştırma | Yorum |
| --- | --- | --- |
| Gözlem, L1 isabeti | NOOP/kapalı p50: 1.017013x | BAŞARILI: ek yük %1,70; sınır %3 |
| Gözlem, L1 kaçırması | NOOP/kapalı p50: 0.902072x | BAŞARILI: %3 sınırı içinde |
| Kayıtlı işleyiciyle gözlem, L1 isabeti | İşleyici/kapalı p50: 2.045369x | Yalnızca raporlanır; NOOP sonucu değildir |
| Native asenkron okuma, L1 kaçırması | 1000 işlemlik tam pencereler ölçüldü | Yalnızca raporlanır; pencere süresini 1000'e bölmek tekil istek yüzdeliklerini vermez |
| Sunucu takibi ile Pub/Sub | Okuma/yazma hücreleri, gerçek modlar ve PUBLISH farkları doğrulandı | Sayısal karşılaştırma yalnızca raporlanır; genel bir hızlanma iddiası değildir |

Kapasite planlamasında bu değerleri kullanmadan önce güven aralıklarını inceleyin. Bu sonuçlar
okuma birleştirme veya aggregation hedeflerini kapatmaz; doğruluk, Cluster, failover, native bellek
ve kurulum kabulünün yerine geçmez.

## Ölçümü Tekrar Çalıştırma

Ayrı bir Redis test ortamı kullanın. Her test düzeneği kendine özgü bir anahtar
öneki oluşturur ve yalnızca bu öneke ait anahtarları temizler. `FLUSHDB` veya
`FLUSHALL` çalıştırılmaz. Aracı üretim Redis'ine bağlamayın. Bu düzeneklerde SQL'e
erişilmeye çalışılırsa ölçüm hata ile durur.

```bash
mvn -B -ntp -Predis-benchmarks -pl tools/benchmarks/redis -am -DskipTests package
java -Dcachedb.bench.redis=redis:6379 \
  -Dcachedb.bench.output=target/jmh-result.json \
  -jar tools/benchmarks/redis/target/benchmarks.jar
pwsh tools/ci/compare-benchmarks.ps1 -SelfTest
pwsh tools/ci/check-jmh-evidence.ps1 -Path target/jmh-result.json
```

İlk kaydedilen referans ölçümünde üç ayrı JVM süreci ve birer saniyelik beş hazırlık
ile beş ölçüm turu vardır. Eşlenmiş sürüm kontrolü, aynı üç süreci ve beş turu korur;
**iki sürümde de her tur üç saniye sürer**. İşlem hacmi ve örneklenmiş gecikme ayrı
ölçülür; GC profili de kaydedilir. Her süreç 512 MiB heap ve varsayılan olarak iki etkin işlemci kullanır. Jedis
bağlantı havuzu 32 bağlantıyla sınırlıdır. Eşlenmiş iki koşuda da havuz açıkça
adil dağıtımla (`fairness=true`) ve on saniyelik bağlantı bekleme sınırıyla kurulur;
bağlantı ve okuma zaman aşımları korunur. Yayımlanmış 0.18.1 runtime kodu
değiştirilmez; desteklenen Jedis istemcisi tanımıyla aynı ayar verilir. Bu sonuç,
eski sürümün adil olmayan varsayılan havuzunu değil, bu yapılandırmasını ölçer.
Ölçüm için CPU ve bellek sınırları sabit,
ayrı bir doğrulama ortamı kullanın; bu sınırları sonuçlarla birlikte kaydedin.

Çok sayıda sanal thread aynı havuzu kullandığında, adil olmayan dağıtım birkaç
hızlı istemcinin bekleyenlerin önüne sürekli geçmesine yol açabilir. Toplam işlem
hacmi yüksek görünürken bazı istemciler hiç ilerlemeyebilir veya zaman aşımına
uğrayabilir. Hem toplam işlem hacmini hem de her istemcinin ilerlemesini doğrulayın;
sorunu gizlemek için havuzu veya bekleme süresini büyütmeyin. İlk referansı ve
başarısız inceleme koşularını özgün ayarlarıyla saklayın. Bunların sonuçlarını,
adil havuzla alınan daha uzun eşlenmiş koşuyla doğrudan karşılaştırmayın.

## Eşlenmiş Sürüm Kontrolü

Önce yayımlanmış referansa bağlı aracı derleyip JAR'ını ayırın, ardından aday
sürümü derleyin. İki derleme arasında `clean` çalıştırmayın; kök dizindeki kanıt
dosyalarını da kaldırır.

```bash
mkdir -p target
mvn -B -ntp -f tools/benchmarks/redis/pom.xml \
  -Dcachedb.benchmark.runtime.version=0.18.1 -DskipTests package
cp tools/benchmarks/redis/target/benchmarks.jar target/baseline-benchmarks.jar
mvn -B -ntp -Predis-benchmarks -pl tools/benchmarks/redis -am -DskipTests package
CACHEDB_BENCH_CASE=all-comparable CACHEDB_BENCH_REDIS=127.0.0.1:16383 \
  sh tools/benchmarks/redis/run-paired.sh \
  target/baseline-benchmarks.jar tools/benchmarks/redis/target/benchmarks.jar target/paired
CACHEDB_BENCH_CASE=virtual CACHEDB_BENCH_REDIS=127.0.0.1:16383 \
  sh tools/benchmarks/redis/run-paired.sh \
  tools/benchmarks/redis/target/benchmarks.jar tools/benchmarks/redis/target/benchmarks.jar target/candidate-virtual
pwsh tools/ci/merge-paired-benchmarks.ps1 -Directory target/paired \
  -LegacyVirtualLivenessEvidence tools/benchmarks/baseline/v0.18.1-virtual-liveness/failure.json \
  -CandidateVirtualResult target/candidate-virtual/virtual-candidate.json
```

Bir sonraki senaryoya geçmeden önce aynı senaryo iki JAR ile de ölçülür; eski ve
yeni sürümün çalışma sırası belirli bir kurala göre değiştirilir. Tüm ham sonuçlar
ve günlükler saklanır. Mevcut çıktı dizininin üzerine yazılmaz. Tek senaryolu
inceleme (`CACHEDB_BENCH_CASE=miss1`), 22 ölçümlük tam kontrolün yerine geçemez.
Tek CPU'lu düzenekte `CACHEDB_BENCH_CPUS=1`, birleştirme komutunda ise
`-ProcessorCount 1` kullanılır. Mutlak sonuçları iki CPU'lu ölçümle karşılaştırmayın.

CI, tam koşudan önce aynı eski JAR'ı iki kez kullanarak tekil okuma için A/A
kontrolü yapar. Bu kontrole de %10 işlem hacmi ve %15 p99 sınırı uygulanır.
Kontrol başarısızsa ortam, farkı koda bağlamak için yeterince kararlı değildir;
eşikler yükseltilmez ve yalnızca başarılı tekrar seçilmez. Kontrolün geçmesi,
yalnızca ölçülen senaryo için kanıttır; her iş yükünü doğrulamaz.

Kod karşılaştırmasında Redis ve istemciyi aynı podda çalıştırmak, düğümler arası
ağ değişkenliğini azaltır; üretim ağının gecikmesini kanıtlamaz. CPU isteği ve
sınırı da fiziksel çekirdeğin yalnız bu işe ayrıldığını göstermez. Topolojiyi,
CPU kısıtlamasını ve ortak altyapının sınırlarını kaydedin. Başarısız ham sonuçları
koruyun; farklı koşulardan yalnız geçen satırları seçerek sürüm sonucu üretmeyin.

### Eski Sürümde Sanal Thread İlerlemesinin Durması

Yayımlanmış 0.18.1, kaydedilen tek CPU'lu ortamda 1.000 sanal thread ile,
32 bağlantılı adil havuz kullanılmasına rağmen ilerleyemedi. Thread dökümünde,
`RedisProducerGuard.sampleSafely` içinde Redis bağlantısı beklerken taşıyıcı
thread'in tutulduğu görülüyor. Süreç, tanılama kaydı alındıktan sonra SIGTERM ile
durduruldu; 143 çıkış kodu kendiliğinden çökmeyi değil, bu müdahaleyi gösterir.
Bkz. [özet ve dosya sağlama değerleri](../../tools/benchmarks/baseline/v0.18.1-virtual-liveness/failure.json).

Bu denemeden eski sürüm için geçerli bir sanal thread performans referansı
çıkarılamaz. Sonuç sıfır işlem hacmi, gecikme yüzdeliği veya başarılı kıyas olarak
gösterilmez. Karşılaştırılabilir 20 ölçümde gerileme eşikleri aynen korunur. Aday
sürüm, sanal thread için iki ölçüm modu dahil 22 ölçümün tamamını bitirmelidir;
üç ayrı JVM süreci ve beşer hazırlık/ölçüm turu şartı değişmez.

CI, eşlenmiş koşuda `CACHEDB_BENCH_CASE=all-comparable`, ardından aday JAR ile
iki geçişte `CACHEDB_BENCH_CASE=virtual` kullanır. Birleştirme kontrolü hem eski
sürümün doğrulanmış hata kaydını hem de adayın eksiksiz sonucunu ister:

```bash
pwsh tools/ci/merge-paired-benchmarks.ps1 -Directory target/paired \
  -LegacyVirtualLivenessEvidence tools/benchmarks/baseline/v0.18.1-virtual-liveness/failure.json \
  -CandidateVirtualResult target/candidate-virtual/virtual-candidate.json
```

Eksik, değiştirilmiş veya farklı bir hataya ait kanıt reddedilir. İstisna yalnızca
bu 0.18.1 senaryosu içindir; aday hatalarını veya diğer eksik ölçümleri kapsamaz.
İlerlemesiz geçen süre operatör gözlemidir. Thread dökümü ilgili bekleme zincirini
gösterir; tek başına biçimsel bir deadlock ispatı veya üretim SLA'sı değildir.

## Senaryolar

| Senaryo | Ölçülen işlem |
| --- | --- |
| `l1HitFindById` | Hazır L1 kaydından değişmez bir entity okuma |
| `l1MissFindById` | 1, 16 ve 64 platform thread ile Redis'ten tekil okuma |
| `l1MissFindByIdVirtual1000` | 1.000 sanal thread üzerinden tekil Redis okuması |
| `findAllMixed100` | 50 L1 isabeti ve 50 Redis okuması içeren 100 ID'lik çağrı |
| `aggregateCount10k` | Tenant kapsamı açık ve kapalıyken 10.000 kaydı sayma |
| `facet10k20Groups` | Aynı 10.000 kaydı 20 grupta özetleme |
| `saveSingle` | SQL'e aktarımı beklemeden Redis yazma kabulü ve stream'e ekleme |

Karışık okuma senaryosunda her çağrıdan önce 50 L1 girdisi bellek içinde
geçersizleştirilir. Bu küçük hazırlık maliyeti ölçüm düzeneğinin parçasıdır;
sonucu yalnızca aynı düzenekle karşılaştırın.
Eski geçersizleştirme aboneliği, istemcinin süreli bloklayan okuma ayarını kullanır.
Boşta kalma nedeniyle bağlantı kesilirse L1 temizlenir ve okuma geçici olarak
Redis'e yönelir. Bu uyarıları ham günlükte saklayın. L1 senaryosu, her çağrının
L1'den karşılandığını veya aboneliğin kesintisiz kaldığını tek başına kanıtlamaz.
Sanal thread senaryosunda JMH'nin
her işçi görevi için yeni bir sanal thread açılır; sanal thread havuzu yoktur.
Eşzamanlılık semaforla 1.000 görevle sınırlanır. Yüzdelik gecikmeler, toplu sürenin
istek sayısına bölünmesiyle değil, tek tek repository çağrılarından hesaplanır.

Sanal thread senaryosunda JMH'nin isteğe bağlı, aktif bekleme kullanan tur
eşzamanlaması kapalıdır. JMH 1.37 özel executor için bu beklemede thread'i serbest
bırakmadığından, taşıyıcı thread sayısını aşan işçi sayısında ilerleme durabilir.
Hazırlık ve kapanış bariyerleri korunur. Referans ve aday koşular aynı ayarı
kullanır; repository eşzamanlılığı veya ölçüm turu sayısı değiştirilmez.

Yazma senaryosunda ayrı bir daemon, yalnızca test stream'ini 100 ms'de bir yaklaşık
8.192 girdiye indirir. Bu bakım işi iki sürümde de ölçülen çağrılarla aynı Redis'i
kullanır. Redis `maxmemory` ve `noeviction` ayarları kesin bellek sınırını sağlar;
bellek dolarsa koşu başarılı sayılmaz.
Varsayılan yazma baskısı koruması açık kalır; SQL worker'ı çalışmadığı için bu
senaryoda devreye girebilir. Bu nedenle `saveSingle`, yalnızca `XADD` komutunun
gecikmesini ölçmez. Başlangıç kayıtları ölçümden önce, 128 satırlık sınırlı
`saveAll` çağrılarıyla hazırlanır.

## Sonuçları Değerlendirme

JMH JSON çıktısında ham ölçümler, güven aralıkları, yüzdelikler ve GC tahsis
miktarları bulunur. Aynı senaryoyu, ölçüm modunu, parametreleri, thread sayısını,
birimleri ve ortamı karşılaştırın. İşlem hacmi %10'dan fazla düşerse veya p99
gecikmesi %15'ten fazla artarsa kontrol başarısız olur. Eksik senaryo ve geçersiz
sayılar da hata sayılır. Kod değişikliğine sonuç atfetmeden önce güven aralıklarını
ve pod CPU kısıtlamasını inceleyin. Yalnızca ortalama düştü diye iyileşme ilan etmeyin.

0.18.1 referans ölçümü runtime değişikliklerinden önce alınır. Bu sürümde olmayan
özellikler ilk ölçümünü eklendikleri sürümde alır; geçmişe dönük sonuç uydurulmaz.
Benchmark aracı yayımlanan paketlere veya BOM'a dahil değildir.

CI performans hattı, yayımlanmış 0.18.1 paketlerini ve aday sürümü aynı makinede
art arda yeniden ölçer; eşikleri bu iki koşuya uygular. Git'te saklanan JSON geçmiş
ölçümün referansıdır, aynı donanımda yeniden ölçmenin yerine geçmez. Kubernetes
ölçümünü farklı bir GitHub makinesiyle doğrudan karşılaştırmak, altyapı farkını
framework gerilemesi sanmamıza neden olabilir.

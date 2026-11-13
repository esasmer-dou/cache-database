# İsteğe Bağlı 0.19.0 Özellik Ölçümleri

[English](../../../../../../tools/benchmarks/redis/src/features/README.md)

Bu isteğe bağlı geliştirme aracı profili, CacheDB 0.19.0'ın yerel API'lerini hedefler.
Referansla uyumlu varsayılan kaynakları, okuma birleştirme profilini, SDK bağımlılıklarını veya
ürün varsayılanlarını değiştirmez. Varsayılan araç, değiştirilmemiş 0.18.1 ile derlenebilir;
reflection veya eski API taklidi kullanılmaz.

## Derleme ve Çalıştırma

Yalnız onaylı EmaTech Kubernetes ölçüm ortamında, aynı aday kaynaktan üretilmiş SDK paketleri Maven
deposuna kurulduktan sonra çalıştırın. Örnek, hedeflenen 0.19.0 koordinatını kullanır; sürümün
yayımlandığı veya kabul edildiği anlamına gelmez. Bu profil eski 0.18.1 SDK ile derlenemez.
İki ölçüm profilini birlikte etkinleştirmeyin. İlk derlemeden önce asıl referans JAR'ını koruyun.

Sabit kaynaklı ölçüm podunda, depo kökünden:

~~~sh
mvn -f tools/benchmarks/redis/pom.xml -Predis-benchmarks-v019 \
  -Dcachedb.benchmark.runtime.version=0.19.0 -DskipTests package

export CACHEDB_BENCH_CPUS=1
export CACHEDB_BENCH_REDIS=redis://127.0.0.1:6379
export CACHEDB_BENCH_BASELINE_JAR=/workspace/preserved/v0.18.1/benchmarks.jar

timeout --signal=TERM --kill-after=15s 5h \
  sh tools/benchmarks/redis/run-features-paired.sh \
  tools/benchmarks/redis/target/features-v019/benchmarks-v019-features.jar \
  /workspace/evidence/t9-features-attempt1 features
~~~

HER deneme için yeni dizin kullanın. Yarım koşuyu sürdürmeyin, başarısız denemeden başarılı satırları
seçmeyin ve referans JAR'ının üzerine yazmayın. Betik mevcut çıktı dizinini reddeder; çıkışta aday,
referans ve kaynak özetlerini doğrular. Koşunun bitmesi tek başına kabul değildir.

Bağımsız denetleyiciyi aynı onaylı ortamda PowerShell 7 ile, ölçülen JVM'lerin dışında çalıştırın:

~~~powershell
$baseline = (Get-FileHash /workspace/preserved/v0.18.1/benchmarks.jar -Algorithm SHA256).Hash
./tools/ci/check-t9-feature-evidence.ps1 -Path /workspace/evidence/t9-features-attempt1 -CandidateJar ./tools/benchmarks/redis/target/features-v019/benchmarks-v019-features.jar -ExpectedBaselineSha256 $baseline -ProcessorCount 1 -Group features
~~~

Referans özeti, adayın bildiriminden değil, bağımsız korunan referanstan gelmelidir. Çalışma zamanı
varsayılanı değişince SDK ve araç JAR'ını yeniden derleyin. Ön kontroller, gerçek varsayılanları
özetlenmiş adaydan okur; kaynak dosyasından veya ortam değişkeninden çıkarsamaz.

| Grup | Zorunlu ham sonuç hücresi |
| --- | ---: |
| observation | 12: L1 isabeti / kaçırması; kapalı / NOOP / gerçek boş işleyici; işlem hacmi / SampleTime |
| async | 4: yerel asenkron L1 kaçırması; birleştirme kapalı / açık; iki ölçüm modu |
| tracking | 8: L1 isabeti / yazma; PUBSUB / SERVER_TRACKING; iki ölçüm modu |
| features | 24 özellik hücresinin tamamı |
| all | 24 hücre ve coalescing/ altında tam 16 hücreli okuma birleştirme matrisi |

Grubu betiğin üçüncü argümanı ve denetleyicinin aynı -Group argümanıyla seçin.
all grubu, okuma birleştirme betiği/denetleyicisini kullanır; gerçek paket varsayılanı dahil
T1_COALESCING_V4 kanıtını ister. Her çalışan ilerlemelidir. Tek çalışan p50 ölçütü <=%3, yüksek
eşzamanlılıktaki iki işlem hacmi koşulu >=2x olarak kalır. Yalnız paketten kanıtlanmış default=false,
tek çalışan için açıkça etkinleştirilen kullanım istisnasına izin verir; >=2x koşullarını gevşetmez.
Yeni altı saatlik pod süresi ve tam yeni matris ayırın; dıştaki beş saat sınırı denemeyi sınırlar.
`all` öncesinde [T1 yönergelerindeki](../../../../../../tools/benchmarks/redis/src/coalescing/README.md)
paketlenmiş yardımcı hazırlığını bu özellik JAR'ı için çalıştırın ve yardımcı dizinini dışa aktarın.
İç T1 denetleyicisi 512 MiB kullanır; yalnız iki 1000 çalışanlı SampleTime hücresi yardımcıyı GC'den
önce kaydeder. 24 özellik hücresi GC-only ve 1 GiB denetleyici ile değişmeden kalır.

## Sabit Koşullar

Her hücre; JMH 1.37, üç ayrı JVM süreci, beşer üç saniyelik hazırlık ve ölçüm turu, GC profili,
bir platform JMH çağırıcısı, 512 MiB alt süreç ve 1 GiB denetleyici kullanır.
Alt sürecin CPU sayısı hem argümanlarda hem gerçek çalışma zamanı kanıtında doğrulanır.
Her çiftte CPU/bellek sınırlarını, Redis sürecini, ağ yerleşimini ve JDK'yı aynı tutun.
Cgroup/işletim sistemi bilgisi saklanır; dağıtım kaynak incelemesinin yerine geçmez.

Ortak düzenek değişmez: adil en fazla 32 bağlantı, 10 saniye havuz bekleme, 5 saniye bağlantı kurma,
10 saniye soket ve 15 saniye bloklayan soket süresi; REDIS_ONLY ve değişmeyen üretici koruması.
Yapay gecikme, reflection, kaynak veritabanına dönüş, kapalı üretici koruması veya gizli ikinci
geçersizleştirme tüketicisi yoktur. Hazırlık, ilk veri akışının boşalmasını bekleme, INFO, eşdeğerlik
denetimi ve metrik görüntüleri ölçüm dışındadır. Tam asenkron sonuç doğrulaması ve sınırlı tamamlanma
bekleyişi ölçüm içindedir; terk edilmiş istekler işlem hacmini yükseltemez.

## Sonuçların Anlamı

Gözlem gerçek yerel kayıt kancasını kullanır. Kapalı durum core tekil nesnesini, NOOP ise gerçek
Micrometer kaydedicisini ObservationRegistry.NOOP ile kullanır. L1 isabeti ve kaçırmasında NOOP/kapalı
p50 oranı ayrı ayrı <=1.03 olmalıdır. Gerçek, boş yaşam döngüsü işleyicisi olan ayrı registry'nin yükü
ayrı raporlanır; %3 kapısına katılmaz. Ayrı sayan işleyici ön kontrolü, sarmalayıcı taklidi olmadan
tam bir yerel findById gözlemi ve doğru L1/REDIS katmanını ister. Boş işleyici, exporter veya gerçek
izleme arka ucunun maliyetini temsil etmez.

Asenkron senaryo, tek platform çağırıcısından 1000 yerel mantıksal okuma gönderir; bütün sonuçları
tükettikten sonra sonraki sınırlı pencereye geçer. İstek başına sanal thread'e görev aktarma yoktur;
reddeden executor ve gerçek kabul/taşıma/fiziksel tamamlanma sayaçları bunu denetler.
Birleştirme kapalıyken sınırlı tek kimlikli FIFO yolu zorunludur. Her turda fiziksel izinler ve
sonuç/teslim payları bırakılmalıdır. OperationsPerInvocation(1000), işlem hacmini ve tahsisi mantıksal
istek başına normalleştirir. SampleTime yüzdelikleri PENCERE süresi / 1000 değeridir;
tek tek isteklerin yanıt süresi yüzdelikleri DEĞİLDİR. Ayrı birleştirme grubu 1000 gerçek sanal JMH çalışanını ölçer.

Takip, database.nearCacheCoordinator() nesnesini ödünç alır; ikinci tüketici açılmaz.
PUBSUB eski yayını korur. SERVER_TRACKING, yalnız bu yalıtılmış düzeneğin bütün tüketicilerin
yükseltilmiş olması sözleşmesinde yayını kapatır. AUTO ile legacy=false reddedilmelidir.
Her turda hazır olma, gerçek mod, bağlantı nesli, temizleme/yeniden bağlanma ve INFO PUBLISH farkı
denetlenir. Ham Jedis yazması gerçek takip L1'ini bir saniye içinde geçersizleştirmelidir.
Her modun ön kontrolü 1000 gerçek CacheDB yazmasını ve 1000 sürümün tamamını doğrular;
PUBSUB için pozitif yayın kontrolü, katı takip için sıfır yayın farkı aranır.

INFO sayaçları sunucu geneline aittir. Yalnız özel anahtar öneki yeterli değildir; Redis sunucusu
bu koşuya özel olmalıdır. Paylaşılıyorsa yayın kanıtı yok sayılmaz, başarısız olur.
Düzenekler tek sunuculu JedisPooled kullanır; gerçek Cluster/bütün birinciller/failover testlerinin yerine geçmez.

Denetleyici bütün ham süreç ve ölçüm turlarını, hazırlık/ölçüm kanıtlarını, eşlenmiş sırayı, tam
kaynak/argümanları, GC verisini, ön kontrolleri ve kaynak özetlerini ister.
Kanıtı arşivlerken veya kopyalarken `sources/` ağacının tamamını ve `sources.sha256` dosyasını koruyun.
`sources/tools/ci/`, iki denetleyiciyi, ortak histogram yardımcısını ve üç öz sınama betiğini içerir;
özellik öz sınamasının gerektirdiği sabit kaynak dosyaları da ağaçta bulunur. Yalnız denetleyici
betiklerini kopyalamak gerekli bağımlılıkları eksik bırakır. Bu dosyalar SDK/JAR bağımlılıkları değil,
ayrı doğrulama araçlarıdır.
Girdi sınırları JSON başına 64 MiB, günlük başına 16 MiB, toplam 768 MiB'dir.
Birincil SampleTime yüzdelikleri, 15 ham ölçüm turunun histogramları ağırlıklarıyla birleştirildiğinde
bit düzeyinde aynı olmalıdır. Bu denetim yalnız JMH 1.37, `us/op`, üç ayrı süreç ve süreç başına beş
ölçüm turunu destekler. Yazılan on yüzdeliğin tamamı, JMH'nin ara değer hesabı ve binary64 dönüşüm
kurallarıyla doğrulanır; epsilon kullanılmaz, örnekler tek tek açılmaz ve çıktılar değiştirilmez.
Birleştirme en fazla 55.296 farklı değer ve 829.440 girdi kovasıyla sınırlıdır; toplam int32 sayaç
taşması desteklenmeyen kanıttır. Tutarlılık, histogramla birlikte değiştirilmiş bir özeti özgün
kanıt hâline getirmez veya başarısız ya da tahliye edilmiş bir koşuyu geçerli kılmaz.
Asenkron/takip hızı yalnız raporlanır; yeni hız eşiği uydurulmaz. Güven aralığı, p99 ve B/op korunur.
Ham veri veya kapı başarısı; sağlayıcı, ilerleme, iptal, hata, JFR veya üretim kabulü değildir.

## Kanıtın Kapsamı

[Kaydedilen birleştirme oranları ve paket sınırı](../../../../../docs/performans-olcumleri.md),
a9604544 önekli ölçülmüş JAR ve eski Function içeriği için geçerlidir; bu profil veya sonraki/nihai
0.19.0 paketi için geçerli değildir. Aggregate/facet >=2x kapısı AÇIKTIR; Function başlangıç hatası
sayısal sonuç üretmez. Özelliğin bulunması veya denetleyicinin geçmesi, ölçümün tamamlandığı iddiası değildir.
Her nihai paket gereksinimi, kendi kanıtı incelenene kadar
[yayım kontrol listesinde](../../../../../docs/release-checklist.md) açık kalır.

Birincil sözleşmeler:
- [JMH 1.37 JSON yazıcısı](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/results/format/JSONResultFormat.java)
- [Micrometer 1.13.6 ObservationHandler](https://github.com/micrometer-metrics/micrometer/blob/v1.13.6/micrometer-observation/src/main/java/io/micrometer/observation/ObservationHandler.java)

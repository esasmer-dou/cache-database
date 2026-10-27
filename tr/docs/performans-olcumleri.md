# Redis Performans Ölçümleri

Redis JMH aracı, framework'ün repository çağrılarını ölçer. Uygulamanın uçtan uca
yanıt süresini veya SQL commit süresini ölçmez. Sonuçlar yalnızca ölçümde kullanılan
JVM, CPU sınırları, Redis kurulumu ve ağ koşulları için geçerlidir.

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
pwsh tools/ci/compare-benchmarks.ps1 \
  -Baseline tools/benchmarks/baseline/v0.18.1.json \
  -Current target/jmh-result.json -Summary target/benchmark-summary.md
```

Kabul koşusu üç ayrı JVM süreci kullanır. Her süreçte birer saniyelik beş hazırlık
ve beş ölçüm turu vardır. İşlem hacmi ve örneklenmiş gecikme ayrı ölçülür; GC
profili de kaydedilir. Her süreç 512 MiB heap ve iki etkin işlemci kullanır. Jedis
bağlantı havuzu 32 bağlantıyla sınırlıdır. Ölçüm için CPU ve bellek sınırları sabit,
ayrı bir doğrulama ortamı kullanın; bu sınırları sonuçlarla birlikte kaydedin.

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
sonucu yalnızca aynı düzenekle karşılaştırın. Sanal thread senaryosunda JMH'nin
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

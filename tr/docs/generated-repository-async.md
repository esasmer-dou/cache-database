# Üretilen Repository ile Asenkron Erişim

CacheRepositoryProcessor, `asyncExecutor()` metodunu alttaki EntityRepository'ye doğrudan devredecek şekilde üretir.
Böylece üretilen CacheDbRepository'nin parametresiz `async()` ve `asyncWithReceipt()` çağrıları çalışma zamanına
ait kabul denetimini kullanır. Repository veya çağrı başına yeni yürütücü ya da kabul sayacı oluşturulmaz.
AsyncCacheDbRepository, değişiklik makbuzlarını korur; save/delete makbuzlarını sessizce varlık sonucuna dönüştürmez.

Çalışma zamanına ait kabul denetimi olmayan özel repository açık hata verir.
Bu tür bir uygulamada sahipliği açıkça belirlenmiş CacheAsyncExecutor nesnesini `async(executor)` veya
`asyncWithReceipt(executor)` metoduna verin.
Uygulamanın sahip olduğu kabul denetimini işler boşaldıktan sonra kapatın; üretilen repository onun sahibi değildir.

Sürüm yükseltmesinden sonra bağlamaları yenilemek için varlık/repository kaynaklarını yeniden derleyin.
Önceden derlenmiş sınıflara bu yönlendirme kendiliğinden eklenmez.
Üretilen sınıflar doğrudan çağrı kullanır; sorgu proxy'si, reflection ile alan erişimi veya çalışma zamanında
metot adı ayrıştırması yapmaz.

Makbuz odaklı bu arayüz, sınırlandırılmış yürütücü aktarımı kullanabilir.
RedisEntityRepository'nin yerel okuma uygunluğu [yerel asenkron okumalar](redis-native-async.md) belgesindedir.
Yalnız ortak kabul denetimini kullanmak, yürütücüye aktarım yapılmayacağı anlamına gelmez.

Kiracı, yönlendirme ve nedensel gereksinim denetimleri arka ucun sorumluluğundadır.
Nedensel tekil/toplu okumalar yerel sürüm doğrulamasını korur. Uygulanmış sürümü doğrulayan açık bir protokol
olmayan sorgu/aggregate işlemleri, doğrulanmamış sonuç döndürmek yerine ilgili nedensel gereksinimi reddeder.

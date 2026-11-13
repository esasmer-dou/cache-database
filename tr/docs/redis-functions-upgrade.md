# Redis Functions İçin 0.19.0 Geçişi

[English](../../docs/redis-functions-upgrade.md)

## Sürümlü İsimler

CacheDB 0.19.0, eski varsayılan kitaplığın üzerine yazmak yerine yan yana bir Redis Function kitaplığı yükler.
Bu isim değişikliği; varlık, sürüm, silinme işareti, stream ve indeks anahtarlarının düzenini değiştirmez.

| Ayar | 0.18.1 varsayılanı | 0.19.0 varsayılanı |
| --- | --- | --- |
| libraryName | cachedb | cachedb_v019 |
| libraryVersion | 0.18.0 | 0.19.0 |
| upsertFunctionName | entity_upsert | entity_upsert_v019 |
| deleteFunctionName | entity_delete | entity_delete_v019 |
| compactionCompleteFunctionName | compaction_complete | compaction_complete_v019 |

Sınırlı toplu silme giriş noktası deleteFunctionName üzerinden türetilir: entity_delete_v019_bulk.
Bu türetilmiş isim de çakışmamalıdır. Redis işlev isimleri yalnız kitaplık içinde değil, kitaplıklar
arasında da benzersiz olmalıdır. Yalnız libraryName veya libraryVersion değiştirmek yeterli değildir.
Bkz. [Redis FUNCTION LOAD sözleşmesi](https://redis.io/docs/latest/commands/function-load/).

Yükleyici, sürümü ve işlenmiş kaynağın SHA-256 değerini
cachedb:function-library:<libraryName>:marker anahtarında tutar; yüklemeyi bu anahtarın :lease ekiyle
koordine eder. Farklı kitaplık ismi, farklı işaretçi ve yükleme kilidi demektir. Normal başlangıç ve
işlevin kesin olarak bulunamadığı durumda yeniden yükleme, diğer kitaplığı ve işaretçisini korur.
Aynı sürümde içerik değişmesi ve aynı kitaplıkta sürüm düşürme hata olarak kalır; bunları aşmak için
katı yükleme denetimini kapatmayın.

## Varsayılan Geçiş ve Geri Dönüş

0.19.0 isimlerini birlikte kullanın. 0.18.1 örnekleri veya geri dönüş paketleri kullanabilecek durumdayken
eski kitaplığı, işaretçisini ve üç işlev ismini koruyun. FUNCTION FLUSH çalıştırmayın, eski işaretçiyi
silmeyin ve eski kitaplığı küresel olarak değiştirmeyin. Geçiş sırasında varlık/sürüm sayaçlarını sıfırlamayın.
Trafik kabulünden önce gerekli kitaplıkları her Redis birincil düğümünde doğrulayın. Tek sunuculu birlikte
çalışma testi, Cluster veya failover doğrulamasının yerine geçmez.

Yan yana isimler, eski korumasız yazma sözleşmesine erişimi korur. Eski üreticilere kiracı kuşağı
koruması, korumalı silme, şifreli depolama veya yeni kuyruk protokolleri kazandırmaz.
Paylaşılan veride bu özellikleri etkinleştirmeden önce ilgili bütün yazıcıların geçiş sözleşmesini
uygulayın. Kitaplıkların birlikte çalışması, uygulamanın güvenli geri dönüşünü tek başına doğrulamaz.

## Açıkça Sabitlenmiş Özel İsimler

Özel isimlere kendiliğinden sürüm eki eklenmez. Yapılandırmada cachedb, entity_upsert, entity_delete
veya compaction_complete sabitlenmişse yeni varsayılanlar bu kurulumu yalıtamaz.

Yeni kanonik kaynak için farklı kitaplık ismi ve üç farklı işlev ismi seçin; türetilmiş silme ekini de
hesaba katın. libraryVersion=0.19.0 değerini açıkça ayarlayın ve ilgili bütün çağıranları güncelleyin.
Eski çağıranların isimlerini, kaynaklarını ve sürümünü koruyun. Yazma kabulünden önce ACL izinlerini ve
başlangıç kaydını doğrulayın. Özel şablon veya sourceOverride, yapılandırılan bütün giriş noktalarını
kaydetmekten ve kendi aktarım sözleşmesinden sorumludur.

On argümanlı RedisFunctionsConfig kurucusu artık libraryVersion=0.19.0 kullanır; kendisine verilen
isimleri aynen korur, otomatik yeniden adlandırma YAPMAZ. Eski şablonu bilerek yüklemek için on bir
argümanlı kurucuyu veya builder'ı, tam eski kaynakla ve açık 0.18.0 sürüm sabitlemesiyle kullanın.
Değişmeyen eski sürüm numarasına yeni içerik bağlamayın.

Özel isim sözleşmesi birlikte çalışmaya izin vermiyorsa eski/yeni doğrulaması için ayrı onaylanmış,
yalıtılmış Redis örnekleri kullanın. Kaynak, veri ve iş yükü koşullarını aynı tutun; yalıtımı kanıta
kaydedin. Farklı bir uç nokta, asıl eşlenmiş düzeneğin yerine sessizce geçemez.
Korunan referans JAR'ını değiştirmeyin ve başlangıç hatasını sayısal ölçüm gibi göstermeyin.

## Doğrulama Kapsamı

RedisFunctionsVersionContractTest; varsayılanları, kurucu sabitlemelerini ve tarihsel kaynağın özetini denetler.
RedisFunctionLibraryCoexistenceRedisTest, yalnız kendi benzersiz kitaplık/işlev isimlerini ve
3a212461fd835c783cf84d3c6b656b375df40a19 commit'indeki tam 0.18.1 Lua kaynağını kullanır.
İki yazma sözleşmesini, birleştirme/tamamlama davranışını, eksik kitaplığın yeniden yüklenmesini ve
işaretçi/kodun değişmemesini denetler. İçerik değişmesi ve sürüm düşürme olumsuz kontrolleri korunur.
Temizlik yalnız düzeneğin iki kitaplığını ve açıkça sahip olduğu anahtarları siler.
Bunlar test sözleşmeleridir; sürümün, performansın veya karma uygulama kümesinin kabul edildiği iddiası değildir.

0.19.0 aggregate/facet işlem hacmi kapısı açık kalır. Kitaplık içeriği uyuşmazlığı nedeniyle başlangıç
hatası sayısal sonuç üretmez. Önceki okuma birleştirme ölçümleri yalnız kaydedilen pakete aittir;
bkz. [performans kanıtının sınırları](performans-olcumleri.md).

# Production Olgunluğu

İngilizce sürüm: [../../PRODUCTION_GA_CRITERIA.md](../../PRODUCTION_GA_CRITERIA.md)

Bu belge, CacheDB için tek ve güncel olgunluk kaynağıdır. Diğer hazırlık
belgeleri bu sayfaya yönlendirir; ayrı bir durum veya eksik listesi tutmaz.

## Ürün Kararı

CacheDB; sınırları açıkça tanımlanmış rotalar için kararlı, production
ortamlarında kullanılabilecek Redis-first bir kalıcılık ve okuma modeli
framework'üdür. Şeffaf bir cache veya her Redis kaçırmasında SQL'e dönen genel
amaçlı bir katman değildir.

Framework sürümü ile uygulamanın canlıya geçişi iki ayrı karardır:

| Karar | Sorumlu | Gerekli sonuç |
| --- | --- | --- |
| Kararlı CacheDB sürümü yayımlamak | CacheDB bakım ekibi | Aşağıdaki framework kontrollerinin tamamı aynı değişmez etiket üzerinde geçmelidir. |
| Bir uygulama rotasını CacheDB'ye geçirmek | Uygulama ekibi | Uygulamanın staging kanıtlarıyla `cachedb:certify` başarılı olmalıdır. |
| Yönetilen Redis veya SQL HA topolojisi için uygunluk iddiasında bulunmak | Uygulama ve platform ekipleri | Gerçek staging topolojisinde failover tetiklenmeli ve sonuç doğrulanmalıdır. |

Framework kontrollerinin geçmesi, herhangi bir uygulama için kendiliğinden rota,
kapasite, failover, canary veya geri dönüş kanıtı oluşturmaz.

## Kararlı Framework Sürüm Kontrolleri

| Kontrol | Zorunlu kanıt |
| --- | --- |
| Doğruluk ve uyumluluk | Tüm reactor testleri, public API karşılaştırması, generated code derlemesi, provider eşdeğerliği, eski yazma ve replay kontrolleri geçer. |
| Redis koordinasyonu | Çok podlu consumer kimliği, leader lease, pending claim, kesinti sonrası toparlanma, retry ve DLQ kanıtları geçer. |
| PostgreSQL provider | Write-behind, kaynak rotası, warm, outbox/checkpoint ve sample provider kontrolleri geçer. |
| SQL Server provider | Version korumalı batch yazma, throughput eşiği, yeniden başlatma ve bağlantı yenileme, lock sınıflandırması, outbox/checkpoint, migration ve çok podlu apply kontrolleri geçer. |
| Okuma performansı | Projection-first, partitioned relation top-N ve ranked-window benchmark eşikleri geçer. |
| Migration araçları | Şema keşfi derlenebilir projection record ve partitioned relation loader üretir; warm, veri eşitliği, bellek ve rapor testleri geçer. |
| Operasyon | Yönetim arayüzü isteğe bağlı açılır; metrikler, backlog, retry, DLQ, projection gecikmesi, bellek baskısı ve reconciliation durumu izlenebilir. |
| Dağıtım | Binary, source, Javadoc, POM, BOM, checksum ve release paketi değişmezdir; anonim Maven erişimi ile GitHub Release kontrolü geçer. |
| Dokümantasyon | İngilizce ve Türkçe başlangıç sayfaları, sürüm notları, örnekler ve bağlantı kontrolleri geçer. |

Bu kontroller `Framework Readiness`, `Production Evidence`, `Public Maven
Repository Publish` ve `Production GA Release Readiness` workflow'larıyla
uygulanır.

## Uygulama Production Sertifikası

Her uygulama `cachedb-certification` dizinini tutar ve şu komutu çalıştırır:

```bash
mvn verify -Pproduction-certification
```

Aşağıdaki alanlardan biri eksik veya tutarsızsa komut başarısız olur:

- ekran, API, batch, worker ve raporların tamamını kapsayan rota envanteri
- her rota için açık CacheDB kullanım şekli
- warm ve SQL kaynağı ile CacheDB sonucu arasındaki veri eşitliği kanıtı
- Redis bellek bütçesi kanıtı
- Redis ve seçilen SQL provider için failover kanıtı
- canary kanıtı
- denenmiş geri dönüş kanıtı
- çözümlenmemiş engel bulunmaması
- manifestteki rota sayısı ile CSV satır sayısının aynı olması

Kopyalanabilir dizin yapısı ve Maven ayarı için
[Production Sertifikası](production-sertifikasi.md) belgesini kullan.

## Altyapı Sınırları

Aşağıdakiler eksik framework özelliği değil, uygulamanın altyapı sorumluluğudur:

- Library, müşterinin yönetilen Redis failover'ını, SQL Server Always On
  failover'ını, PostgreSQL HA geçişini, gateway politikasını, VPN hattını veya
  Kubernetes topolojisini kendi reposundan tetikleyip sertifikalandıramaz.
- SQL'e CacheDB dışından yazılıyorsa outbox/CDC ya da ölçülmüş bir
  reconciliation rotası gerekir.
- Redis'in yazmayı kabul etmesi ile SQL kalıcılığının tamamlanması ayrı ve
  izlenebilir olaylardır.
- Arşiv, raporlama, dışa aktarma ve sınırsız geçmiş açık SQL rotalarında kalır.
- İş yükü, payload, ağ yolu veya kaynak limiti önemli ölçüde değişirse uygulama
  sertifikası yeniden çalıştırılır.

## Yayına Çıkma Kararı

**Framework sürümü için GO:** bütün framework kontrolleri aynı etiket üzerinde
geçer; yayımlanan artifact'ler anonim olarak indirilebilir ve checksum değerleri
eşleşir.

**Uygulama geçişi için GO:** kararlı framework sürümü kullanılır,
`cachedb:certify` temsilî staging kanıtlarıyla geçer ve ekip oluşan raporu
onaylar.

**NO-GO:** zorunlu framework kontrolünün atlanması, eksik rota envanteri, eksik
kanıt dosyası, veri eşitsizliği, Redis bütçesinin aşılması, çözümlenmemiş engel,
başarısız failover veya denenmemiş geri dönüş.

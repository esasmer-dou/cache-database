# Doküman Haritası

Bu klasör, proje dokümanlarının Türkçe sürümlerini tutar.

Kural:

- kökteki veya `docs` altındaki her Markdown dosyasının Türkçe karşılığı `tr` altında aynı göreli yapıda tutulur
- kök dosya değişirse `tr` altındaki karşılığı da güncellenir
- yeni modül README'leri için de `tr/<modul-adi>/README.md` yolu kullanılır
- `docs` altına yeni teknik doküman eklenirse `tr/docs` altında Türkçe sürümü oluşturulur

Mevcut eşleşmeler:

- Kullanıcı / genel bakış dokümanı:
  - kaynak: [../README.md](../README.md)
  - Türkçe: [README.md](README.md)
- Teknik mimari dokümanı:
  - kaynak: [../docs/architecture.md](../docs/architecture.md)
  - Türkçe: [docs/architecture.md](docs/architecture.md)
- Production test raporu:
  - kaynak: [../docs/production-test-report.md](../docs/production-test-report.md)
  - Türkçe: [docs/production-test-report.md](docs/production-test-report.md)
- Production readiness raporu:
  - kaynak: [../docs/production-readiness-report.md](../docs/production-readiness-report.md)
  - Türkçe: [docs/production-readiness-report.md](docs/production-readiness-report.md)
- Production test modülü:
  - kaynak: [../cachedb-production-tests/README.md](../cachedb-production-tests/README.md)
  - Türkçe: [cachedb-production-tests/README.md](cachedb-production-tests/README.md)

İleride karşılaştırma, benchmark veya migration dokümanları eklenirse önerilen yapı:

- `tr/docs/comparison-*.md`
- `tr/docs/benchmark-*.md`
- `tr/docs/migration-*.md`

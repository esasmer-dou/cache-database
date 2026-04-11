# Dokuman Haritasi

Bu klasor, proje dokumanlarinin Turkce surumlerini tutar.

Kural:

- kokteki veya `docs` altindaki her Markdown dosyasinin Turkce karsiligi `tr` altinda ayni goreli yapida tutulur
- kok dosya degisirse `tr` altindaki karsiligi da guncellenir
- yeni modul README'leri icin de `tr/<modul-adi>/README.md` yolu kullanilir
- `docs` altina yeni teknik dokuman eklenirse `tr/docs` altinda Turkce surumu olusturulur

Mevcut eslesmeler:

- Kullanici / genel bakis dokumani:
  - kaynak: [../README.md](../README.md)
  - Turkce: [README.md](README.md)
- Teknik mimari dokumani:
  - kaynak: [../docs/architecture.md](../docs/architecture.md)
  - Turkce: [docs/architecture.md](docs/architecture.md)
- Production test raporu:
  - kaynak: [../docs/production-test-report.md](../docs/production-test-report.md)
  - Turkce: [docs/production-test-report.md](docs/production-test-report.md)
- Production readiness raporu:
  - kaynak: [../docs/production-readiness-report.md](../docs/production-readiness-report.md)
  - Turkce: [docs/production-readiness-report.md](docs/production-readiness-report.md)
- Production test modulu:
  - kaynak: [../cachedb-production-tests/README.md](../cachedb-production-tests/README.md)
  - Turkce: [cachedb-production-tests/README.md](cachedb-production-tests/README.md)

Ileride karsilastirma, benchmark veya migration dokumanlari eklenirse onerilen yapi:

- `tr/docs/comparison-*.md`
- `tr/docs/benchmark-*.md`
- `tr/docs/migration-*.md`

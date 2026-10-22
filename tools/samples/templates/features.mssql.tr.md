**SQL Server önkoşulu:** yerel Compose içindeki `sql-init` servisi, geçici
`cachedb_sample` veritabanını oluşturur ve bu veritabanında
`ALLOW_SNAPSHOT_ISOLATION` ayarını açar. Varsayılan JDBC adresi artık `tempdb`
yerine bu veritabanını gösterir. Eski demo `tempdb` içindeyse otomatik taşınmaz;
geçici demo verisini yeniden oluşturun veya bilinçli olarak seçtiğiniz `DB_URL`
değerini kullanın. Entegrasyon konteyneri de aynı ayarı kendi test altyapısında
hazırlar. Üretimde kalıcı uygulama veritabanı seçin ve snapshot hazırlamadan önce
snapshot isolation için DBA onayı alın. Uygulama başlangıcı, veritabanının genel
isolation ayarlarını değiştirmez.

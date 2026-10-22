-- Disposable Docker fixture only. Production database settings belong to the DBA.
IF DB_ID('cachedb_sample') IS NULL EXEC('CREATE DATABASE cachedb_sample');
GO
ALTER DATABASE cachedb_sample SET ALLOW_SNAPSHOT_ISOLATION ON;
GO

-- Install after V019 and V019_1 with the application's migration runner.
-- LIST is a native bounded scan kind. Unknown kinds remain rejected.
ALTER TABLE cachedb_purge_scan ADD CONSTRAINT ck_scan_kind_v019_2
    CHECK (scan_kind IN ('OWNER','NAMESPACE','SET','ZSET','LIST','STREAM'));
-- Install the validated replacement before removing the old five-kind constraint.
ALTER TABLE cachedb_purge_scan DROP CONSTRAINT ck_scan_kind;

package com.reactor.cachedb.core.protection;

import com.reactor.cachedb.core.crypto.EncryptedStorageChecks;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Binding;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Explicit source-backed ID window. Not a foreground source fallback or all-history owner ledger. */
public final class InitialSourceHotWindow<T, ID> {
    private final Binding binding;
    private final EntityMetadata<T, ID> metadata;
    private final EntityCodec<T> codec;
    private final SourceOwnerOnboarding.IdCodec<ID> ids;
    private final String idFormat, first, last, fingerprint;
    public static final int MAX_PAGES = 128;
    public static final long MAX_RESIDENT_BYTES = 64L * 1048576;
    /** No route quota reservation is supported by this initial point-entity profile. */
    public enum Profile { POINT_NO_QUOTA }
    private final int maxRows, pageBytes, rowBytes;
    private final long residentBytes;
    private final Profile profile;

    public InitialSourceHotWindow(Binding binding, EntityMetadata<T, ID> metadata, EntityCodec<T> boundCodec,
            SourceOwnerOnboarding.IdCodec<ID> ids, String idFormatToken, ID firstInclusive, ID lastInclusive,
            int maxRows, int pageBytes, int rowBytes, long residentBytes, Profile profile) {
        this.binding = Objects.requireNonNull(binding); this.metadata = Objects.requireNonNull(metadata);
        this.codec = Objects.requireNonNull(boundCodec); this.ids = Objects.requireNonNull(ids);
        idFormat = ProtectionKey.token(idFormatToken);
        if (!binding.tenantPurgeEnabled() || !metadata.tenantPurgeEnabled()
                || binding.entityType() != metadata.entityType() || !binding.scope().equals(metadata.redisNamespace())
                || !binding.source().table().equals(metadata.tableName())
                || !binding.source().idColumn().equals(metadata.idColumn())
                || !binding.source().tenantColumn().equals(metadata.tenantFieldName())
                || metadata.columns().isEmpty() || metadata.columns().size() > 128
                || maxRows < 1 || maxRows > 10000 || rowBytes < 1024 || rowBytes > 1048576
                || pageBytes < rowBytes || pageBytes > 8 * 1048576
                || residentBytes < pageBytes || residentBytes > MAX_RESIDENT_BYTES || profile != Profile.POINT_NO_QUOTA) invalid();
        new TenantPurgeProtocol.SourcePlan(metadata.tableName(), metadata.versionColumn(), metadata.idColumn());
        for (String column : metadata.columns())
            new TenantPurgeProtocol.SourcePlan(metadata.tableName(), column, metadata.idColumn());
        EncryptedStorageChecks.requireBound(metadata, boundCodec);
        // SQL collation is not a canonical String/UUID ordering proof. This initial profile is integral Long only.
        if (!(firstInclusive instanceof Long) || !(lastInclusive instanceof Long))
            throw new ProtectionException(ProtectionException.Code.MIGRATION_REQUIRED);
        first = canonical(firstInclusive); last = canonical(lastInclusive);
        if (firstInclusive instanceof Long lower && lastInclusive instanceof Long upper && lower > upper) invalid();
        this.maxRows = maxRows; this.pageBytes = pageBytes; this.rowBytes = rowBytes;
        this.residentBytes = residentBytes; this.profile = profile;
        fingerprint = mappingFingerprint();

    }
    public Binding binding() { return binding; }
    public EntityMetadata<T, ID> metadata() { return metadata; }
    public EntityCodec<T> codec() { return codec; }
    public SourceOwnerOnboarding.IdCodec<ID> ids() { return ids; }
    public String idFormatToken() { return idFormat; }
    public String firstCanonical() { return first; }
    public String lastCanonical() { return last; }
    public int maxRows() { return maxRows; }
    public int pageBytes() { return pageBytes; }
    public int rowBytes() { return rowBytes; }
    public long residentBytes() { return residentBytes; }
    public Profile profile() { return profile; }
    public String fingerprint() { return fingerprint; }
    public String canonical(ID id) {
        if (!(id instanceof Long actual)) throw new ProtectionException(ProtectionException.Code.MIGRATION_REQUIRED);
        String value = SourceOwnerOnboarding.canonicalId(ids.encode(id));
        ID decoded = ids.decode(value);
        if (!Long.toString(actual).equals(value) || !(decoded instanceof Long) || !id.equals(decoded)
                || !value.equals(ids.encode(decoded))) invalid();
        return value;
    }
    private String mappingFingerprint() {
        var fields = new ArrayList<String>();
        fields.addAll(List.of("initial-source-v2", binding.fingerprint(), metadata.entityType().getName(),
                metadata.entityName(), metadata.tableName(), metadata.redisNamespace(), metadata.idColumn(),
                metadata.versionColumn(), idFormat, first, last, Integer.toString(maxRows),
                Integer.toString(pageBytes), Integer.toString(rowBytes), Long.toString(residentBytes), profile.name(),
                Integer.toString(metadata.schemaVersion()), Integer.toString(metadata.compressionThresholdBytes()),
                Integer.toString(metadata.payloadUpcaster().targetVersion()), metadata.payloadUpcaster().getClass().getName(),
                codec.getClass().getName(), Boolean.toString(codec.encryptedMapping())));
        fields.add(metadata.tenantFieldName()); fields.add(metadata.deletedColumn());
        fields.add(metadata.activeMarkerValue()); fields.add(metadata.deletedMarkerValue());
        fields.add(Integer.toString(metadata.columns().size()));
        for (String column : metadata.columns()) {
            fields.add(column); fields.add(metadata.columnTypes().get(column));
        }
        if (metadata.columnTypes().size() > 130) invalid();
        var sortedTypes = new java.util.TreeMap<>(metadata.columnTypes());
        fields.add(Integer.toString(sortedTypes.size()));
        sortedTypes.forEach((column, type) -> { fields.add(column); fields.add(type); });
        if (metadata.encryptedColumns().size() > 128) invalid();
        var encrypted = new java.util.TreeMap<>(metadata.encryptedColumns());
        fields.add(Integer.toString(encrypted.size()));
        encrypted.forEach((column, descriptor) -> {
            fields.add(column); fields.add(descriptor.keyRing()); fields.add(descriptor.scope().name());
            fields.add(Boolean.toString(descriptor.blindIndex()));
        });
        // Index layout is already part of Binding.layoutToken; bind this profile's declared index metadata too.
        if (metadata.indexedColumns().size() > 128) invalid();
        var indexed = new java.util.TreeSet<>(metadata.indexedColumns());
        fields.add(Integer.toString(indexed.size())); fields.addAll(indexed);
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new java.security.DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                out.writeInt(fields.size());
                for (String field : fields) {
                    if (field == null) { out.writeInt(-1); continue; }
                    long length = com.reactor.cachedb.core.codec.Utf8Length.of(field);
                    if (length > 4096) invalid();
                    byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(encoded.length); out.write(encoded);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new IllegalStateException("Fingerprint unavailable"); }
    }
    @Override public String toString() { return "InitialSourceHotWindow[redacted]"; }
    private static void invalid() { throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST); }
}

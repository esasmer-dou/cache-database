package com.reactor.cachedb.core.crypto;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Deliberately deterministic fixtures, never production keys. */
final class CryptoTestKeys {
    private CryptoTestKeys() {}

    static byte[] aes(int version) {
        byte[] key = new byte[KeyMaterial.KEY_BYTES];
        Arrays.fill(key, (byte) 0x31);
        ByteBuffer.wrap(key).putInt(version);
        return key;
    }

    static byte[] hmac() {
        byte[] key = new byte[KeyMaterial.KEY_BYTES];
        Arrays.fill(key, (byte) 0x72);
        return key;
    }

    static KeyMaterial key(int version) { return new KeyMaterial("k" + version, aes(version), hmac()); }

    static StaticKeyProvider provider(String active, KeyMaterial... keys) {
        return new StaticKeyProvider(Map.of("default", List.of(keys)), Map.of("default", active));
    }
}

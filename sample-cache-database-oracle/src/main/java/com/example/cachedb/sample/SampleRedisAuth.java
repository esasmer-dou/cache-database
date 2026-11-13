package com.example.cachedb.sample;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import redis.clients.jedis.DefaultJedisClientConfig;

/** Disposable sample authentication; credentials never belong in the input fixture URI. */
public final class SampleRedisAuth {
    public static String withPassword(String value, String password) {
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IllegalArgumentException("Invalid credential-free Redis fixture URI");
        }
        if (!("redis".equals(uri.getScheme()) || "rediss".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535)
            throw new IllegalArgumentException("Invalid credential-free Redis fixture URI");
        String secret = optionalPassword(password);
        if (secret == null) return value;
        String encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8).replace("+", "%20");
        return uri.getScheme() + "://:" + encoded + "@" + value.substring(uri.getScheme().length() + 3);
    }

    public static DefaultJedisClientConfig purgeClientConfig(String password) {
        var builder = DefaultJedisClientConfig.builder().connectionTimeoutMillis(2000)
                .socketTimeoutMillis(2000).database(0);
        String secret = optionalPassword(password);
        if (secret != null) builder.password(secret);
        return builder.build();
    }

    private static String optionalPassword(String password) {
        if (password == null || password.isEmpty()) return null;
        if (password.length() > 4096 || !StandardCharsets.UTF_8.newEncoder().canEncode(password))
            throw new IllegalArgumentException("Invalid bounded Redis fixture password");
        return password;
    }

    private SampleRedisAuth() {}
}

package com.example.cachedb.sample.controls;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Demo credential mapping only. Replace with the application's verified identity provider. */
@Component
public final class ControlsAccess {
    private final String supplierToken;
    private final String tenantAToken;
    private final String tenantBToken;

    public ControlsAccess(@Value("${sample.controls.supplier-token:}") String supplierToken,
            @Value("${sample.controls.tenant-a-token:}") String tenantAToken,
            @Value("${sample.controls.tenant-b-token:}") String tenantBToken) {
        this.supplierToken = supplierToken;
        this.tenantAToken = tenantAToken;
        this.tenantBToken = tenantBToken;
        var values = java.util.stream.Stream.of(supplierToken, tenantAToken, tenantBToken)
                .filter(value -> !value.isBlank()).toList();
        if (values.stream().anyMatch(value -> value.length() > 256)
                || values.stream().distinct().count() != values.size())
            throw new IllegalArgumentException("Controls tokens must be distinct and at most 256 characters");
    }

    public String tenant(String authorization) {
        String token = bearer(authorization);
        if (same(token, tenantAToken)) return "shop-a";
        if (same(token, tenantBToken)) return "shop-b";
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant credential required");
    }

    public void supplier(String authorization) {
        if (!same(bearer(authorization), supplierToken))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Supplier credential required");
    }

    private String bearer(String authorization) {
        if (supplierToken.isBlank() && tenantAToken.isBlank() && tenantBToken.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Controls credentials are not configured");
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 263)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bearer credential required");
        String token = authorization.substring(7);
        if (!same(token, supplierToken) && !same(token, tenantAToken) && !same(token, tenantBToken))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown credential");
        return token;
    }

    private static boolean same(String actual, String expected) {
        return !expected.isBlank() && MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}

package com.example.cachedb.sample.rangedata;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = RangeDataController.class)
public final class RangeDataErrors {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RangeDataErrors.class);
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> status(ResponseStatusException error) {
        var response = ResponseEntity.status(error.getStatusCode());
        if (error.getStatusCode().value() == 429) response.header("Retry-After", "1");
        return response.body(Map.of("error", error.getReason() == null ? "Request rejected" : error.getReason()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid bounded input or cursor"));
    }
    @ExceptionHandler(com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException.class)
    ResponseEntity<Map<String, String>> forbidden(com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException error) {
        return ResponseEntity.status(403).body(Map.of("error", "Tenant ownership rejected"));
    }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> unavailable(IllegalStateException error) {
        LOG.warn("Redis-resident range/data operation unavailable", error);
        return ResponseEntity.status(409).body(Map.of("error", "Resident query unavailable or its budget was exceeded"));
    }
}

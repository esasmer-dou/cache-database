package com.example.cachedb.sample.audit;

import java.util.Map;
import com.reactor.cachedb.core.retention.RetentionException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = AuditNoteController.class)
public final class AuditErrors {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AuditErrors.class);
    @ExceptionHandler(AuditNoteController.WriteUnconfirmed.class)
    ResponseEntity<Map<String, Object>> writeUnconfirmed(AuditNoteController.WriteUnconfirmed error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", error.getReason(),
                "accepted", java.util.List.of(error.accepted()), "sqlDurabilityConfirmed", false, "retrySafe", false));
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of(
                "error", error.getReason() == null ? "Request rejected" : error.getReason(),
                "sqlDurabilityConfirmed", false, "retrySafe", false));
    }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, Object>> invalid(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid bounded audit input", "retrySafe", false));
    }
    @ExceptionHandler(RetentionException.class)
    ResponseEntity<Map<String, Object>> retention(RetentionException error) {
        int status = error.code() == RetentionException.Code.BUSY ? 429 : 503;
        var response = ResponseEntity.status(status);
        if (status == 429) response.header("Retry-After", "1");
        LOG.warn("Audit retention pass did not confirm completion: {}", error.code());
        return response.body(Map.of("error", error.code().name(), "outcomeUnknown", true,
                "sqlDurabilityConfirmed", false, "sourceErasureConfirmed", false, "retrySafe", false));
    }
    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, Object>> unknown(RuntimeException error) {
        LOG.warn("Audit operation did not confirm completion");
        return ResponseEntity.status(503).body(Map.of("error", "Operation outcome unconfirmed",
                "outcomeUnknown", true, "sqlDurabilityConfirmed", false, "retrySafe", false));
    }
}

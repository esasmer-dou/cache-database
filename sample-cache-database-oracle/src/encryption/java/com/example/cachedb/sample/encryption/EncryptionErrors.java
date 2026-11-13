package com.example.cachedb.sample.encryption;

import com.reactor.cachedb.core.crypto.CacheEncryptionException;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = EncryptedNoteController.class)
public final class EncryptionErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid() { return ResponseEntity.badRequest().body(Map.of("code", "INVALID_NOTE")); }
    @ExceptionHandler(CacheEncryptionException.class)
    ResponseEntity<Map<String, String>> crypto() { return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code", "ENCRYPTION_UNAVAILABLE")); }
}

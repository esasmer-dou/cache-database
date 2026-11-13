package com.example.cachedb.sample.cacheapi;

import com.reactor.cachedb.core.async.CacheAsyncRejectedException;
import com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = CacheApiController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "sample.cache-api", name = "enabled", havingValue = "true")
public class CacheApiErrors {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> failure(Exception failure) {
        Throwable cause = failure;
        for (int depth = 0; depth < 8 && cause.getCause() != null
                && (cause instanceof CompletionException || cause instanceof ExecutionException); depth++)
            cause = cause.getCause();
        HttpStatusCode status = HttpStatus.INTERNAL_SERVER_ERROR;
        String detail = "Cache operation failed; no rollback or source durability is implied";
        boolean retry = false;
        if (cause instanceof ResponseStatusException response) {
            status = response.getStatusCode(); detail = response.getReason();
        } else if (cause instanceof CacheAsyncRejectedException) {
            status = HttpStatus.TOO_MANY_REQUESTS; detail = "Async admission is saturated or closed"; retry = true;
        } else if (cause instanceof java.util.concurrent.RejectedExecutionException) {
            status = HttpStatus.TOO_MANY_REQUESTS; detail = "Cache operation admission rejected"; retry = true;
        } else if (cause instanceof TimeoutException) {
            status = HttpStatus.GATEWAY_TIMEOUT; detail = "Response deadline exceeded; operation may still complete";
        } else if (cause instanceof CacheTenantAccessDeniedException) {
            status = HttpStatus.FORBIDDEN; detail = "Tenant access denied";
        } else if (cause instanceof IllegalArgumentException
                || cause instanceof org.springframework.web.bind.MethodArgumentNotValidException
                || cause instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
                || cause instanceof org.springframework.web.bind.MissingServletRequestParameterException
                || cause instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
            status = HttpStatus.BAD_REQUEST; detail = "Invalid bounded request";
        } else if (cause instanceof IllegalStateException && cause.getMessage() != null
                && cause.getMessage().startsWith("Causal ")) {
            status = HttpStatus.CONFLICT; detail = "Required Redis version is unavailable";
        }
        var response = ResponseEntity.status(status);
        if (retry) response.header(HttpHeaders.RETRY_AFTER, "1");
        return response.body(ProblemDetail.forStatusAndDetail(status, detail == null ? "Request failed" : detail));
    }
}

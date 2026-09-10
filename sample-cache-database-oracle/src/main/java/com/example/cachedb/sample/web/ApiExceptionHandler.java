package com.example.cachedb.sample.web;

import com.example.cachedb.sample.application.SampleEntityNotFoundException;
import com.example.cachedb.sample.application.SampleHotDataUnavailableException;
import com.example.cachedb.sample.service.DurableReferenceUnavailableException;
import com.reactor.cachedb.core.model.OptimisticWriteConflictException;
import com.reactor.cachedb.core.repository.HotRouteUnavailableException;
import com.reactor.cachedb.core.repository.HotUpdateUnavailableException;
import com.reactor.cachedb.spring.boot.CacheDistributedJobQueueFullException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalidBody(MethodArgumentNotValidException exception) {
        Map<String, String> violations = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                violations.putIfAbsent(error.getField(), error.getDefaultMessage())
        );
        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "Request validation failed");
        detail.setProperty("violations", violations);
        return ResponseEntity.badRequest().body(detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> invalidParameter(ConstraintViolationException exception) {
        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "Request parameter validation failed");
        detail.setProperty("violations", exception.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .toList());
        return ResponseEntity.badRequest().body(detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> invalidQuery(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, exception.getMessage()));
    }

    @ExceptionHandler(DurableReferenceUnavailableException.class)
    ResponseEntity<ProblemDetail> parentNotDurable(DurableReferenceUnavailableException exception) {
        ProblemDetail detail = problem(HttpStatus.CONFLICT, exception.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(detail);
    }

    @ExceptionHandler(SampleEntityNotFoundException.class)
    ResponseEntity<ProblemDetail> entityNotFound(SampleEntityNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(problem(HttpStatus.NOT_FOUND, exception.getMessage()));
    }

    @ExceptionHandler(SampleHotDataUnavailableException.class)
    ResponseEntity<ProblemDetail> hotDataUnavailable(SampleHotDataUnavailableException exception) {
        ProblemDetail detail = problem(HttpStatus.CONFLICT, exception.getMessage());
        detail.setProperty("cacheStatus", exception.lookupStatus().name());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(detail);
    }

    @ExceptionHandler(HotRouteUnavailableException.class)
    ResponseEntity<ProblemDetail> hotRouteUnavailable(HotRouteUnavailableException exception) {
        ProblemDetail detail = problem(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        detail.setProperty("route", exception.coverage().routeName());
        detail.setProperty("scope", exception.coverage().scope());
        detail.setProperty("coverageStatus", exception.coverage().status().name());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(detail);
    }

    @ExceptionHandler(HotUpdateUnavailableException.class)
    ResponseEntity<ProblemDetail> hotUpdateUnavailable(HotUpdateUnavailableException exception) {
        ProblemDetail detail = problem(HttpStatus.CONFLICT, exception.getMessage());
        detail.setProperty("entityId", exception.id());
        detail.setProperty("requiredAction", "warm-or-explicit-source-command");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(detail);
    }

    @ExceptionHandler(OptimisticWriteConflictException.class)
    ResponseEntity<ProblemDetail> optimisticConflict(OptimisticWriteConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(problem(HttpStatus.CONFLICT, exception.getMessage()));
    }

    @ExceptionHandler(CacheDistributedJobQueueFullException.class)
    ResponseEntity<ProblemDetail> warmQueueFull(CacheDistributedJobQueueFullException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage()));
    }

    private ProblemDetail problem(HttpStatus status, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setTitle(status.getReasonPhrase());
        return detail;
    }
}

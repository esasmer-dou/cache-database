package com.example.cachedb.sample.web;

import com.example.cachedb.sample.service.DurableReferenceUnavailableException;
import com.example.cachedb.sample.service.WarmQueueFullException;
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

    @ExceptionHandler(SampleConflictException.class)
    ResponseEntity<ProblemDetail> conflict(SampleConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(problem(HttpStatus.CONFLICT, exception.getMessage()));
    }

    @ExceptionHandler(WarmQueueFullException.class)
    ResponseEntity<ProblemDetail> warmQueueFull(WarmQueueFullException exception) {
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

package com.example.shortener.api;

import com.example.shortener.service.error.AliasAlreadyTakenException;
import com.example.shortener.service.error.InvalidAliasException;
import com.example.shortener.service.error.InvalidUrlException;
import com.example.shortener.service.error.LinkNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.stream.Collectors;

/**
 * Translates domain failures into RFC 9457 problem responses.
 *
 * <p>Two rules are enforced here. Every expected failure gets a specific status and a
 * message the caller can act on, and no unexpected failure ever leaks a stack trace or
 * an internal identifier to the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(LinkNotFoundException.class)
    public ProblemDetail handleNotFound(LinkNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Link not found", e.getMessage(), "link-not-found");
    }

    @ExceptionHandler({InvalidUrlException.class, InvalidAliasException.class})
    public ProblemDetail handleBadRequest(RuntimeException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage(), "invalid-request");
    }

    @ExceptionHandler(AliasAlreadyTakenException.class)
    public ProblemDetail handleConflict(AliasAlreadyTakenException e) {
        return problem(HttpStatus.CONFLICT, "Alias unavailable", e.getMessage(), "alias-taken");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", detail, "invalid-request");
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(RateLimitExceededException e) {
        ProblemDetail body = problem(HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
                "Rate limit exceeded. Retry after " + e.getRetryAfterSeconds() + " seconds.",
                "rate-limited");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(body);
    }

    /**
     * Without this, the catch-all below would turn a deliberate 4xx raised inside a
     * controller into a 500. Caught by an integration test asserting 400 on a bad
     * stats window.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException e) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        ProblemDetail body = problem(status, status.getReasonPhrase(),
                e.getReason() == null ? status.getReasonPhrase() : e.getReason(), "invalid-request");
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "The request could not be completed.", "internal-error");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://example.com/problems/" + type));
        return problem;
    }
}

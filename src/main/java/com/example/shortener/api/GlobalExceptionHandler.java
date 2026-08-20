package com.example.shortener.api;

import com.example.shortener.service.error.AliasAlreadyTakenException;
import com.example.shortener.service.error.InvalidAliasException;
import com.example.shortener.service.error.InvalidUrlException;
import com.example.shortener.service.error.LinkNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.stream.Collectors;

/**
 * Translates every failure into an RFC 9457 problem response.
 *
 * <p>Three rules are enforced here. Every expected failure gets a specific status and a
 * message the caller can act on; no unexpected failure ever leaks a stack trace, a class
 * name or an internal identifier to the client; and the response shape is identical
 * whether the failure came from this application or from the framework underneath it.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} is what makes the third rule true.
 * As a plain advice, the catch-all below intercepted Spring MVC's own exceptions before
 * Spring could assign them their proper status, so a malformed JSON body, an unknown
 * content type and a wrong HTTP method all surfaced as {@code 500}. The parent class
 * already maps those correctly; the catch-all now only ever sees genuinely unexpected
 * failures, which is the only thing it was ever meant to cover.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // --- domain failures --------------------------------------------------------------

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

    /**
     * Rethrown rather than answered.
     *
     * <p>This advice runs inside the dispatcher servlet, so it sees an access denial
     * <em>before</em> Spring Security's {@code ExceptionTranslationFilter} does. Answering
     * it here would flatten a real distinction: an anonymous caller should be told to
     * authenticate (401), while an authenticated caller lacking the role should be refused
     * (403). Letting it propagate hands that judgement back to the component that can make
     * it, and keeps the response identical to every other authorization failure.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public void handleAccessDenied(AccessDeniedException e) {
        throw e;
    }

    // --- framework failures -----------------------------------------------------------

    /**
     * Overridden rather than declared with {@code @ExceptionHandler}: the parent already
     * maps this exception, and a second mapping for the same type fails at startup.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(problem(HttpStatus.BAD_REQUEST, "Invalid request", detail, "invalid-request"));
    }

    /**
     * Every exception the parent class handles funnels through here, which is where the
     * framework's default body is replaced with one shaped like the rest of the API.
     *
     * <p>The detail is written from the status rather than from the exception. Spring's own
     * messages are helpful to a developer and occasionally quote parser internals or class
     * names back to the caller, and an error contract is a poor place to disclose the
     * shape of the code producing it.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e,
                                                             Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        if (status.is5xxServerError()) {
            log.error("Framework-level failure", e);
        }
        return ResponseEntity.status(status)
                .headers(headers)
                .body(problem(status, status.getReasonPhrase(), detailFor(status), typeFor(status)));
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "The request could not be completed.", "internal-error");
    }

    private String detailFor(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "The request could not be read. Check the body and query parameters.";
            case METHOD_NOT_ALLOWED -> "That method is not supported on this endpoint.";
            case UNSUPPORTED_MEDIA_TYPE -> "Unsupported content type. Use application/json.";
            case NOT_ACCEPTABLE -> "No supported representation is available for the requested type.";
            case NOT_FOUND -> "No such endpoint.";
            case PAYLOAD_TOO_LARGE -> "The request body is too large.";
            default -> status.is4xxClientError()
                    ? "The request could not be processed."
                    : "The request could not be completed.";
        };
    }

    private String typeFor(HttpStatus status) {
        if (status.is5xxServerError()) {
            return "internal-error";
        }
        return status == HttpStatus.NOT_FOUND ? "not-found" : "invalid-request";
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://example.com/problems/" + type));
        return problem;
    }
}

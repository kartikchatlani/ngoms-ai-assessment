package com.shortener.api;

import com.shortener.link.AliasTakenException;
import com.shortener.link.CodeSpaceExhaustedException;
import com.shortener.link.InvalidRequestException;
import com.shortener.link.LinkGoneException;
import com.shortener.link.LinkNotFoundException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every failure to an RFC 9457 Problem Details body (application/problem+json) with a
 * stable machine-readable {@code errorCode}, so clients never parse human messages (ADR-0005).
 * Extending ResponseEntityExceptionHandler gives the same format for Spring's own errors
 * (malformed JSON, wrong method, unknown path, bad query param types).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail invalid(InvalidRequestException ex) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage(), "INVALID_REQUEST");
        pd.setProperty("errors", List.of(Map.of("field", ex.field(), "message", ex.getMessage())));
        return pd;
    }

    @ExceptionHandler(LinkNotFoundException.class)
    ProblemDetail notFound(LinkNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Link not found", ex.getMessage(), "LINK_NOT_FOUND");
    }

    @ExceptionHandler(LinkGoneException.class)
    ProblemDetail gone(LinkGoneException ex) {
        return problem(HttpStatus.GONE, "Link no longer available", ex.getMessage(), "LINK_" + ex.status().name());
    }

    @ExceptionHandler(AliasTakenException.class)
    ProblemDetail aliasTaken(AliasTakenException ex) {
        return problem(HttpStatus.CONFLICT, "Alias already taken", ex.getMessage(), "ALIAS_TAKEN");
    }

    @ExceptionHandler(CodeSpaceExhaustedException.class)
    ProblemDetail exhausted(CodeSpaceExhaustedException ex) {
        log.error("Code generation exhausted, investigate code length / generator", ex);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unable to create links",
                "Please retry shortly", "CODE_SPACE_EXHAUSTED");
    }

    /** Database down or unreachable: tell clients it is transient instead of a generic 500. */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    ResponseEntity<ProblemDetail> databaseUnavailable(DataAccessResourceFailureException ex) {
        log.error("Database unavailable", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable",
                        "Please retry shortly", "STORAGE_UNAVAILABLE"));
    }

    /** Last resort: log everything, reveal nothing (no stack traces or SQL to clients). */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        log.error("Unhandled error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "An unexpected error occurred", "INTERNAL_ERROR");
    }

    /** Adds per-field details to Bean Validation failures (Spring's default omits them). */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ex.getBody();
        body.setTitle("Invalid request");
        body.setProperty("errorCode", "INVALID_REQUEST");
        body.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message", fe.getField() + " " + fe.getDefaultMessage()))
                .toList());
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String errorCode) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setProperty("errorCode", errorCode);
        return pd;
    }
}

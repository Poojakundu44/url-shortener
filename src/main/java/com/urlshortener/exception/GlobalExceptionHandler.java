package com.urlshortener.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Centralized exception handling for all controllers.
 *
 * INTERVIEW: "What is @RestControllerAdvice?"
 * @ControllerAdvice + @ResponseBody. Intercepts exceptions thrown
 * from any @Controller or @RestController in the application.
 * Without this, Spring returns HTML error pages or raw stack traces.
 *
 * WHY centralize exception handling?
 * 1. Single place to change error format — affects the whole API.
 * 2. Controllers stay clean — no try/catch blocks.
 * 3. Consistent error structure across all endpoints.
 * 4. Logging in one place rather than scattered across controllers.
 *
 * INTERVIEW: "What's the order of @ExceptionHandler methods?"
 * Spring picks the most specific handler — a handler for
 * UrlNotFoundException takes priority over one for RuntimeException.
 * If no specific handler matches, it falls through to the general one.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Handles missing or deactivated short URLs.
     * HTTP 404 Not Found.
     */
    @ExceptionHandler(UrlNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUrlNotFound(
            UrlNotFoundException ex,
            HttpServletRequest request) {

        log.warn("URL not found: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    /**
     * Handles expired short URLs.
     * HTTP 410 Gone — semantically stronger than 404.
     *
     * INTERVIEW: "Why 410 Gone instead of 404 Not Found?"
     * 404 = "I don't know about this resource."
     * 410 = "I knew about this resource, it existed, and it's permanently gone."
     * For expired URLs, 410 is more semantically accurate.
     * Search engines also de-index 410 resources faster than 404.
     */
    @ExceptionHandler(UrlExpiredException.class)
    public ResponseEntity<ErrorResponse> handleUrlExpired(
            UrlExpiredException ex,
            HttpServletRequest request) {

        log.warn("URL expired: {}", ex.getMessage());
        return buildResponse(HttpStatus.GONE, ex.getMessage(), request);
    }

    /**
     * Handles custom alias conflicts.
     * HTTP 409 Conflict — the resource already exists.
     */
    @ExceptionHandler(ShortCodeConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(
            ShortCodeConflictException ex,
            HttpServletRequest request) {

        log.warn("Short code conflict: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /**
     * Handles @Valid validation failures (e.g., blank URL, invalid format).
     * HTTP 400 Bad Request.
     *
     * MethodArgumentNotValidException is thrown by Spring when
     * @Valid finds constraint violations on @RequestBody.
     *
     * We extract all field errors into a map: { "originalUrl": "Must be a valid URL" }
     * This lets clients highlight the specific field that failed.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        Map<String, String> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null
                                ? fe.getDefaultMessage()
                                : "Invalid value",
                        // Merge function: if same field has multiple errors, keep first
                        (existing, replacement) -> existing
                ));

        log.warn("Validation failed: {}", fieldErrors);

        ErrorResponse body = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Validation Failed")
                .message("One or more fields are invalid")
                .path(request.getRequestURI())
                .validationErrors(fieldErrors)
                .build();

        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Handles illegal argument exceptions from service layer
     * (e.g., invalid custom alias format, reserved alias).
     * HTTP 400 Bad Request.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request) {

        log.warn("Bad request: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /**
     * Catch-all for anything not explicitly handled above.
     * HTTP 500 Internal Server Error.
     *
     * CRITICAL: log the full stack trace here — this is unexpected.
     * For the client, return a generic message — never expose
     * internal stack traces or system details to clients.
     * That's an information disclosure vulnerability.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(
            Exception ex,
            HttpServletRequest request) {

        log.error("Unexpected error on {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later.",
                request);
    }

    private ResponseEntity<ErrorResponse> buildResponse(
            HttpStatus status,
            String message,
            HttpServletRequest request) {

        ErrorResponse body = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.status(status).body(body);
    }
}
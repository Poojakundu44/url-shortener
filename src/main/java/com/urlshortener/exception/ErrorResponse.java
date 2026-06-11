package com.urlshortener.exception;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Standardized error response body.
 *
 * INTERVIEW: "What should an error response contain?"
 * At minimum: timestamp, status code, human-readable message, request path.
 * For validation errors: a map of field → error message pairs.
 *
 * WHY include a timestamp?
 * Correlates client-reported errors with server logs.
 * Without it, "I got an error at some point today" is undiagnosable.
 *
 * WHY include the path?
 * When clients report errors, they often don't tell you which endpoint failed.
 * Including the path eliminates that ambiguity.
 */
@Data
@Builder
public class ErrorResponse {
    private LocalDateTime timestamp;
    private int status;
    private String error;
    private String message;
    private String path;
    private Map<String, String> validationErrors; // non-null only for 400s
}
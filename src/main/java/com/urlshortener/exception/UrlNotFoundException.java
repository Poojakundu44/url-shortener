package com.urlshortener.exception;

/**
 * Thrown when a short code doesn't exist or has been deactivated.
 * Maps to HTTP 404 Not Found.
 *
 * WHY extend RuntimeException and not Exception?
 * Checked exceptions (extending Exception) force callers to handle
 * or declare them, polluting method signatures throughout the codebase.
 * Spring's convention is unchecked exceptions — the exception handler
 * catches them centrally. @Transactional also only auto-rolls-back
 * on unchecked exceptions by default.
 */
public class UrlNotFoundException extends RuntimeException {
    public UrlNotFoundException(String message) {
        super(message);
    }
}
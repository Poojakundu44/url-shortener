package com.urlshortener.exception;

/** Thrown when a custom alias is already taken. Maps to HTTP 409 Conflict. */
public class ShortCodeConflictException extends RuntimeException {
    public ShortCodeConflictException(String message) {
        super(message);
    }
}
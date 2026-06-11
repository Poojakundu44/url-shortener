package com.urlshortener.exception;

/** Thrown when a short URL exists but has passed its expiry date. Maps to HTTP 410 Gone. */
public class UrlExpiredException extends RuntimeException {
    public UrlExpiredException(String message) {
        super(message);
    }
}
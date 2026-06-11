package com.urlshortener.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Response DTO — what the client receives after creating a short URL.
 *
 * WHY expose shortUrl as a full URL (not just the code)?
 * Clients shouldn't have to know your base URL to construct the redirect link.
 * The server computes http://localhost:8080/aB3xY7k and returns it ready to use.
 */
@Data
@Builder
public class UrlResponse {

    private String shortCode;
    private String shortUrl;        // full URL: base + "/" + shortCode
    private String originalUrl;
    private Long clickCount;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private boolean active;
}
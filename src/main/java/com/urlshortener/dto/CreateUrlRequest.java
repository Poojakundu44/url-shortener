package com.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

import java.time.LocalDateTime;

/**
 * Request DTO for creating a short URL.
 *
 * WHY a separate DTO?
 * 1. Decouples your API contract from your DB schema.
 *    If you rename a DB column, your API doesn't break.
 * 2. Prevents mass-assignment vulnerabilities — a user can't
 *    POST {"active": false, "clickCount": 999} and corrupt data.
 * 3. Lets you validate at the boundary before hitting any business logic.
 *
 * INTERVIEW: "What is the difference between an Entity and a DTO?"
 * Entity = DB-mapped domain object with JPA annotations.
 * DTO = data transfer object for crossing boundaries (API in/out, service calls).
 * They can look similar but serve different purposes.
 */
@Data
@Builder
public class CreateUrlRequest {

    @NotBlank(message = "Original URL is required")
    @URL(message = "Must be a valid URL")
    @Size(max = 2048, message = "URL must not exceed 2048 characters")
    private String originalUrl;

    /**
     * Optional custom alias. If provided, used as the short code.
     * Validated: alphanumeric + hyphens only, 3-50 chars.
     */
    @Size(min = 3, max = 50, message = "Custom alias must be between 3 and 50 characters")
    private String customAlias;

    /**
     * Optional explicit expiry. If null, app config default is used.
     */
    private LocalDateTime expiresAt;

    private String userId;
}
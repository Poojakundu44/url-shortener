package com.urlshortener.service;

import com.urlshortener.config.AppProperties;
import com.urlshortener.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Generates unique short codes, handling both auto-generated
 * and custom-alias cases.
 *
 * WHY a separate component from Base62Encoder?
 * Single Responsibility Principle:
 * - Base62Encoder: pure encoding math (no Spring, no DB, easily unit-tested)
 * - ShortCodeGenerator: orchestrates encoding + collision checking + validation
 *
 * This separation also means you can unit test Base62Encoder without
 * any Spring context at all — just plain Java.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ShortCodeGenerator {

    private final Base62Encoder base62Encoder;
    private final UrlRepository urlRepository;
    private final AppProperties appProperties;

    /**
     * Reserved slugs that must never be used as short codes.
     * Without this, a user could claim "/api", "/health", "/admin"
     * and intercept internal endpoints.
     *
     * INTERVIEW: "How do you prevent users from taking over system paths?"
     * This blocklist + exact-match check is the answer.
     * At larger scale: store in a DB table or Redis set for runtime updates.
     */
    private static final java.util.Set<String> RESERVED_CODES = java.util.Set.of(
            "api", "health", "admin", "login", "logout",
            "register", "dashboard", "analytics", "static", "assets"
    );

    /**
     * Generates a short code for an auto-incremented database ID.
     *
     * WHY does this method take an ID parameter?
     * The flow is:
     *   1. Service calls urlRepository.save(url) → DB assigns the ID
     *   2. Service calls generateFromId(url.getId()) to get the short code
     *   3. Service sets url.setShortCode(code) and saves again
     *
     * This is a two-save pattern. It's necessary because the DB ID
     * isn't known until after the first save.
     *
     * ALTERNATIVE: Pre-generate a random code without using the ID.
     * That requires collision checking, which this approach avoids.
     *
     * INTERVIEW: "Can two threads generate the same short code?"
     * With Base62(ID): NO — each thread gets a unique ID from the DB
     * (IDENTITY sequence is atomic). Therefore codes are unique by construction.
     * This is the key advantage over random generation.
     */
    public String generateFromId(long id) {
        String code = base62Encoder.encode(id);

        /*
         * Pad to minimum length for uniform appearance.
         * "1" → "0000001" (if shortCodeLength = 7)
         *
         * WHY pad? Short codes of varying length look unprofessional.
         * Also, very short codes (1-2 chars) are trivially guessable.
         *
         * NOTE: As the table grows, codes naturally reach full length.
         * 62^7 = 3.5T codes — padding only affects the first ~62^6 entries.
         */
        int targetLength = appProperties.getShortCodeLength();
        if (code.length() < targetLength) {
            code = "0".repeat(targetLength - code.length()) + code;
        }

        return code;
    }

    /**
     * Validates and registers a user-supplied custom alias.
     *
     * Applies three checks in order (fail-fast pattern):
     * 1. Format validation — alphanumeric + hyphens only
     * 2. Reserved word check — no system path takeover
     * 3. Uniqueness check — no collision with existing codes
     *
     * WHY not just let the DB unique constraint handle it?
     * The DB constraint is the safety net. Application-level validation
     * gives a meaningful error message rather than a DataIntegrityViolationException
     * that you'd have to parse and translate.
     *
     * INTERVIEW: "What's the principle here?"
     * → Validate early, fail fast, give meaningful errors.
     *   DB constraints are backup, not primary validation.
     */
    public String validateAndNormalizeCustomAlias(String alias) {
        // Normalize: lowercase and trim
        String normalized = alias.strip().toLowerCase();

        // Format: 3-50 chars, alphanumeric and hyphens only
        if (!normalized.matches("^[a-z0-9-]{3,50}$")) {
            throw new IllegalArgumentException(
                    "Custom alias must be 3-50 characters, " +
                            "lowercase alphanumeric and hyphens only. Got: '" + normalized + "'");
        }

        // Reserved word check
        if (RESERVED_CODES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "'" + normalized + "' is a reserved alias and cannot be used.");
        }

        // Uniqueness check
        if (urlRepository.existsByCustomAlias(normalized)) {
            throw new IllegalArgumentException(
                    "Custom alias '" + normalized + "' is already taken.");
        }

        log.debug("Custom alias '{}' passed all validation checks", normalized);
        return normalized;
    }

    /**
     * Checks if a given short code is already in use.
     * Used as a guard before inserting to prevent race-condition collisions.
     *
     * CONCURRENCY NOTE: There's a TOCTOU (Time-of-Check-Time-of-Use) window
     * between existsByShortCode() and the actual insert. Two threads could
     * both check, both see "not exists", and both try to insert the same code.
     *
     * Why is this not a problem with Base62(ID)?
     * Because DB IDENTITY generation is atomic — two threads can never get
     * the same ID, so they can never produce the same short code.
     *
     * This method is here for the custom alias path, where TOCTOU is a real
     * risk. The DB unique constraint is the final backstop.
     */
    public boolean isShortCodeAvailable(String shortCode) {
        return !urlRepository.existsByShortCode(shortCode);
    }
}
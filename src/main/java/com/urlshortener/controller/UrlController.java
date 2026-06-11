package com.urlshortener.controller;

import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.UrlResponse;
import com.urlshortener.service.UrlService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for URL management operations.
 *
 * INTERVIEW: "What HTTP status code should URL creation return?"
 * 201 Created — not 200 OK. 201 signals that a new resource was
 * created as a result of the request. Always return 201 for POST
 * endpoints that create resources.
 *
 * INTERVIEW: "What's the difference between @RestController and @Controller?"
 * @RestController = @Controller + @ResponseBody on every method.
 * @ResponseBody tells Spring to serialize return values to JSON
 * instead of resolving them as view names (Thymeleaf/JSP).
 * Use @Controller only when returning views.
 *
 * URL DESIGN: We use /api/v1/ prefix for two reasons:
 * 1. Versioning from day one. Adding /v2/ later is trivial.
 * 2. Separates management API from the redirect endpoint (/).
 *    The redirect endpoint lives at the root — short and clean.
 */
@RestController
@RequestMapping("/api/v1/urls")
@RequiredArgsConstructor
@Slf4j
public class UrlController {

    private final UrlService urlService;

    /**
     * POST /api/v1/urls
     * Creates a new short URL.
     *
     * @Valid triggers Bean Validation on CreateUrlRequest before the method body runs.
     * If validation fails, Spring throws MethodArgumentNotValidException,
     * which our GlobalExceptionHandler maps to a structured 400 response.
     *
     * INTERVIEW: "Where should input validation happen?"
     * At the boundary — controller entry point, before any business logic.
     * Never pass unvalidated data into the service layer.
     */
    @PostMapping
    public ResponseEntity<UrlResponse> createShortUrl(
            @Valid @RequestBody CreateUrlRequest request) {

        log.info("POST /api/v1/urls - originalUrl: {}", request.getOriginalUrl());
        UrlResponse response = urlService.createShortUrl(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /api/v1/urls/{shortCode}
     * Returns metadata for a short URL — NOT a redirect.
     * Used by dashboards and analytics UIs.
     */
    @GetMapping("/{shortCode}")
    public ResponseEntity<UrlResponse> getUrlDetails(
            @PathVariable String shortCode) {

        UrlResponse response = urlService.getUrlDetails(shortCode);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/urls/user/{userId}
     * Lists all active URLs for a user.
     *
     * SCALABILITY NOTE: No pagination yet — intentional for Phase 1.
     * In production: add Pageable parameter and return Page<UrlResponse>.
     * Returning unbounded lists to a client is a latency and memory risk.
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<UrlResponse>> getUserUrls(
            @PathVariable String userId) {

        List<UrlResponse> urls = urlService.getUserUrls(userId);
        return ResponseEntity.ok(urls);
    }

    /**
     * DELETE /api/v1/urls/{shortCode}
     * Soft-deletes (deactivates) a short URL.
     *
     * WHY 204 No Content and not 200 OK?
     * 204 signals success with no response body.
     * Returning 200 with an empty body is technically wrong —
     * 200 implies a body is present. This is a subtle distinction
     * interviewers notice.
     */
    @DeleteMapping("/{shortCode}")
    public ResponseEntity<Void> deactivateUrl(
            @PathVariable String shortCode) {

        urlService.deactivateUrl(shortCode);
        return ResponseEntity.noContent().build();
    }
}
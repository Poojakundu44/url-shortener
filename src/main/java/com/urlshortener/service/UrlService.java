package com.urlshortener.service;

import com.urlshortener.config.AppProperties;
import com.urlshortener.domain.Url;
import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.UrlResponse;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UrlService {

    private final UrlRepository urlRepository;
    private final ShortCodeGenerator shortCodeGenerator;
    private final AppProperties appProperties;
    private final UrlCacheService urlCacheService;

    @Transactional
    public UrlResponse createShortUrl(CreateUrlRequest request) {
        log.info("Creating short URL for: {}", request.getOriginalUrl());

        if (request.getCustomAlias() != null && !request.getCustomAlias().isBlank()) {
            return createWithCustomAlias(request);
        }
        return createWithGeneratedCode(request);
    }

    private UrlResponse createWithGeneratedCode(CreateUrlRequest request) {
        LocalDateTime expiry = resolveExpiry(request.getExpiresAt());

        Url url = Url.builder()
                .originalUrl(request.getOriginalUrl())
                .userId(request.getUserId())
                .expiresAt(expiry)
                .clickCount(0L)
                .active(true)
                .build();

        Url savedUrl = urlRepository.save(url);

        String shortCode = shortCodeGenerator.generateFromId(savedUrl.getId());
        savedUrl.setShortCode(shortCode);
        Url finalUrl = urlRepository.save(savedUrl);

        log.info("Created short URL: {} -> {}", shortCode, request.getOriginalUrl());
        return toResponse(finalUrl);
    }

    private UrlResponse createWithCustomAlias(CreateUrlRequest request) {
        String alias = shortCodeGenerator.validateAndNormalizeCustomAlias(
                request.getCustomAlias());

        LocalDateTime expiry = resolveExpiry(request.getExpiresAt());

        Url url = Url.builder()
                .originalUrl(request.getOriginalUrl())
                .shortCode(alias)
                .customAlias(alias)
                .userId(request.getUserId())
                .expiresAt(expiry)
                .clickCount(0L)
                .active(true)
                .build();

        Url savedUrl = urlRepository.save(url);

        log.info("Created custom alias: {} -> {}", alias, request.getOriginalUrl());
        return toResponse(savedUrl);
    }

    @Transactional
    public String resolveShortCode(String shortCode) {
        log.debug("Resolving short code: {}", shortCode);

        Optional<UrlResponse> cached = urlCacheService.get(shortCode);
        if (cached.isPresent()) {
            UrlResponse cachedUrl = cached.get();
            if (!cachedUrl.isActive()) {
                throw new UrlNotFoundException("Short URL has been deactivated: " + shortCode);
            }
            if (cachedUrl.getExpiresAt() != null &&
                    LocalDateTime.now().isAfter(cachedUrl.getExpiresAt())) {
                throw new UrlExpiredException("Short URL has expired: " + shortCode);
            }
            incrementClickCountAsync(shortCode);
            return cachedUrl.getOriginalUrl();
        }

        Url url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(
                        "Short URL not found: " + shortCode));

        if (!url.isActive()) {
            throw new UrlNotFoundException("Short URL has been deactivated: " + shortCode);
        }
        if (url.isExpired()) {
            throw new UrlExpiredException("Short URL has expired: " + shortCode);
        }

        url.incrementClickCount();
        Url saved = urlRepository.save(url);
        UrlResponse response = toResponse(saved);

        urlCacheService.put(shortCode, response);

        return url.getOriginalUrl();
    }

    @Transactional(readOnly = true)
    public UrlResponse getUrlDetails(String shortCode) {
        Url url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
        return toResponse(url);
    }

    @Transactional(readOnly = true)
    public List<UrlResponse> getUserUrls(String userId) {
        return urlRepository
                .findByUserIdAndActiveTrueOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public void deactivateUrl(String shortCode) {
        Url url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
        url.setActive(false);
        urlRepository.save(url);
        urlCacheService.evict(shortCode);
        log.info("Deactivated short URL: {}", shortCode);
    }

    @Transactional
    public int deactivateExpiredUrls() {
        int count = urlRepository.deactivateExpiredUrls(LocalDateTime.now());
        if (count > 0) {
            log.info("Deactivated {} expired URLs", count);
        }
        return count;
    }

    private LocalDateTime resolveExpiry(LocalDateTime requestedExpiry) {
        if (requestedExpiry != null) {
            return requestedExpiry;
        }
        return LocalDateTime.now()
                .plusDays(appProperties.getDefaultExpiryDays());
    }

    private UrlResponse toResponse(Url url) {
        return UrlResponse.builder()
                .shortCode(url.getShortCode())
                .shortUrl(appProperties.getBaseUrl() + "/" + url.getShortCode())
                .originalUrl(url.getOriginalUrl())
                .clickCount(url.getClickCount())
                .expiresAt(url.getExpiresAt())
                .createdAt(url.getCreatedAt())
                .active(url.isActive())
                .build();
    }

    private void incrementClickCountAsync(String shortCode) {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                urlRepository.findByShortCode(shortCode).ifPresent(url -> {
                    url.incrementClickCount();
                    urlRepository.save(url);
                });
            } catch (Exception e) {
                log.warn("Async click count update failed for {}: {}",
                        shortCode, e.getMessage());
            }
        });
    }
}
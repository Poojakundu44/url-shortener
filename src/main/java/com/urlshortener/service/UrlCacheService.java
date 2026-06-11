package com.urlshortener.service;

import com.urlshortener.dto.UrlResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Encapsulates all direct Redis operations.
 *
 * WHY a dedicated CacheService instead of using @Cacheable everywhere?
 * @Cacheable is great for simple key=value caching with uniform TTL.
 * But we need:
 * 1. Per-entry TTL matching the URL's own expiry date
 * 2. Manual eviction when a URL is deactivated
 * 3. Graceful degradation when Redis is unavailable
 *
 * Direct RedisTemplate gives us that control.
 *
 * INTERVIEW: "How do you handle Redis being unavailable?"
 * The try/catch in every method — on Redis failure, we log a warning
 * and return empty. The caller then falls back to the database.
 * This is the resilience pattern: cache is an optimization, not a dependency.
 * The service MUST work without it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UrlCacheService {

    private static final String KEY_PREFIX = "url:";

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Stores a URL response in Redis.
     *
     * KEY DESIGN: "url:{shortCode}" — prefix prevents key collisions
     * if this Redis instance is shared with other services.
     *
     * TTL: we use the URL's remaining lifetime, not a fixed TTL.
     * If the URL expires in 2 hours, the cache entry expires in 2 hours.
     * This ensures expired URLs are never served from cache.
     */
    public void put(String shortCode, UrlResponse urlResponse) {
        try {
            String key = buildKey(shortCode);
            Duration ttl = computeTtl(urlResponse);
            redisTemplate.opsForValue().set(key, urlResponse, ttl);
            log.debug("Cached URL: {} with TTL: {}", shortCode, ttl);
        } catch (Exception e) {
            // Never let a cache write failure break the request
            log.warn("Failed to cache URL {}: {}", shortCode, e.getMessage());
        }
    }

    /**
     * Retrieves a cached URL response.
     * Returns Optional.empty() on cache miss OR Redis failure.
     */
    public Optional<UrlResponse> get(String shortCode) {
        try {
            Object cached = redisTemplate.opsForValue().get(buildKey(shortCode));
            if (cached instanceof UrlResponse response) {
                log.debug("Cache HIT for short code: {}", shortCode);
                return Optional.of(response);
            }
            log.debug("Cache MISS for short code: {}", shortCode);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Cache read failed for {}: {}", shortCode, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Evicts a cache entry.
     * Called when a URL is deactivated or modified.
     *
     * WHY is eviction critical here?
     * Without eviction: a deactivated URL would still redirect for up to
     * 1 hour (the cache TTL). Users could access URLs you've intentionally
     * disabled. This is a correctness bug, not just a staleness issue.
     */
    public void evict(String shortCode) {
        try {
            redisTemplate.delete(buildKey(shortCode));
            log.debug("Evicted cache entry for: {}", shortCode);
        } catch (Exception e) {
            log.warn("Cache eviction failed for {}: {}", shortCode, e.getMessage());
        }
    }

    private String buildKey(String shortCode) {
        return KEY_PREFIX + shortCode;
    }

    /**
     * Computes TTL as minimum of: fixed max TTL vs time until URL expiry.
     *
     * Example: URL expires in 30 minutes → cache for 30 minutes, not 1 hour.
     * This prevents serving stale data for URLs that expire before the cache TTL.
     */
    private Duration computeTtl(UrlResponse urlResponse) {
        Duration maxTtl = Duration.ofHours(1);

        if (urlResponse.getExpiresAt() == null) {
            return maxTtl;
        }

        Duration untilExpiry = Duration.between(
                java.time.LocalDateTime.now(),
                urlResponse.getExpiresAt()
        );

        if (untilExpiry.isNegative() || untilExpiry.isZero()) {
            return Duration.ofSeconds(1); // effectively expired — very short TTL
        }

        return untilExpiry.compareTo(maxTtl) < 0 ? untilExpiry : maxTtl;
    }
}
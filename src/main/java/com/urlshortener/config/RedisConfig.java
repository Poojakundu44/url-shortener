package com.urlshortener.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis and Spring Cache configuration.
 *
 * INTERVIEW: "Why configure serialization explicitly?"
 * Default Spring Redis serialization uses Java serialization (JDK).
 * Problems:
 * 1. JDK serialization is not human-readable — can't inspect cache in Redis CLI
 * 2. Breaks if you rename/move a class (ClassNotFoundException)
 * 3. Larger payload than JSON
 *
 * JSON serialization is readable, portable, and version-tolerant.
 *
 * INTERVIEW: "What is @EnableCaching?"
 * Activates Spring's annotation-driven cache management —
 * without this, @Cacheable/@CacheEvict annotations are silently ignored.
 * A very common bug: developers add @Cacheable but forget @EnableCaching.
 */
@Configuration
@EnableCaching
@RequiredArgsConstructor
public class RedisConfig {

    private final AppProperties appProperties;

    /**
     * Configures the RedisTemplate for manual cache operations.
     * Used in UrlService when we need fine-grained control
     * (e.g., setting per-entry TTL, which @Cacheable doesn't support).
     *
     * Key serializer: StringRedisSerializer → human-readable keys in Redis CLI.
     * Value serializer: GenericJackson2JsonRedisSerializer → JSON values.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory) {

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Keys as plain strings: "url:aB3xY7k"
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // Values as JSON
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper());

        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();

        return template;
    }

    /**
     * Configures the CacheManager used by @Cacheable/@CacheEvict.
     *
     * RedisCacheConfiguration sets defaults for ALL caches:
     * - TTL: from application config
     * - Key prefix: cache name + "::" (e.g., "urls::aB3xY7k")
     * - Serialization: JSON
     * - Null values: disabled (don't cache null — prevents cache pollution)
     *
     * INTERVIEW: "Why disable caching null values?"
     * If you cache a null (URL not found), the next request for that code
     * returns null from cache without hitting the DB — even if the URL
     * was created after the null was cached. This is called a
     * "cache negative" or "null poisoning" bug.
     * The safer default is to not cache nulls and let the DB be the source of truth.
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        long ttlSeconds = appProperties.getCache().getUrlTtlSeconds();

        RedisCacheConfiguration config = RedisCacheConfiguration
                .defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(ttlSeconds))
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(
                                redisObjectMapper())))
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }

    /**
     * Custom ObjectMapper for Redis serialization.
     *
     * WHY not reuse the Spring MVC ObjectMapper?
     * The Redis serializer needs type information embedded in the JSON
     * so it can deserialize back to the correct class.
     * activateDefaultTyping adds "@class" metadata to JSON values.
     * The MVC ObjectMapper should NOT have this — it would expose
     * class names in API responses, which is an information leak.
     *
     * WHY JavaTimeModule?
     * Java 8+ date/time types (LocalDateTime, Instant) are not
     * serializable by default Jackson — JavaTimeModule adds that support.
     */
    private ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );
        return mapper;
    }
}
package com.urlshortener.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {

    private String baseUrl;
    private int shortCodeLength;
    private int defaultExpiryDays;
    private Cache cache = new Cache();

    @Getter
    @Setter
    public static class Cache {
        private long urlTtlSeconds = 3600;
        private String urlCacheName = "urls";
    }
}
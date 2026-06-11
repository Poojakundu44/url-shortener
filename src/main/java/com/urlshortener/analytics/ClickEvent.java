package com.urlshortener.analytics;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Represents a single redirect event.
 *
 * WHY a dedicated event class and not just passing the shortCode?
 * Rich events let you add analytics dimensions without changing
 * the event publication site. Add referrer, country, device type
 * here and the listener gets it automatically.
 *
 * In Phase 5 (Kafka), this becomes a Kafka message payload.
 * The structure stays the same — only the transport changes.
 */
@Data
@Builder
public class ClickEvent {
    private String shortCode;
    private String ipAddress;
    private String userAgent;
    private String referrer;
    private LocalDateTime timestamp;
}
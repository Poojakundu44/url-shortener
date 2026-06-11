package com.urlshortener.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes click events using Spring's ApplicationEventPublisher.
 *
 * WHY Spring ApplicationEvents and not Kafka yet?
 * ApplicationEvents are in-process, zero-infrastructure async events.
 * They're the right choice for Phase 1 — same JVM, no broker needed.
 *
 * The abstraction (publisher → listener) means switching to Kafka in
 * Phase 5 is a listener-only change. The publisher doesn't care.
 *
 * INTERVIEW: "How is this different from calling the service directly?"
 * Direct call: synchronous, same thread, same transaction.
 *   Failure in analytics rolls back the redirect. Wrong.
 * Event: decoupled, different thread (@Async listener), different transaction.
 *   Analytics failure is isolated. The redirect always succeeds.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClickEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    public void publish(ClickEvent event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            // Publishing must never fail the redirect
            log.warn("Failed to publish click event for {}: {}",
                    event.getShortCode(), e.getMessage());
        }
    }
}
package com.urlshortener.analytics;

import com.urlshortener.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processes click events off the redirect thread.
 *
 * @Async: runs this method in a separate thread from Spring's task executor.
 * The redirect returns to the client BEFORE this method completes.
 *
 * INTERVIEW: "What thread pool does @Async use by default?"
 * SimpleAsyncTaskExecutor — creates a new thread for every task.
 * That's dangerous at high concurrency (thread explosion).
 *
 * Production fix: configure a bounded ThreadPoolTaskExecutor (below).
 * Always do this — SimpleAsyncTaskExecutor is fine for demos,
 * catastrophic under load.
 *
 * @Transactional here is a NEW transaction — not the redirect's transaction.
 * If this fails, the click isn't counted, but the redirect already succeeded.
 * This is acceptable for analytics: eventual consistency is fine.
 * Losing a click count is not a business-critical failure.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClickEventListener {

    private final UrlRepository urlRepository;

    @Async("analyticsExecutor")
    @EventListener
    @Transactional
    public void handleClickEvent(ClickEvent event) {
        try {
            urlRepository.findByShortCode(event.getShortCode())
                    .ifPresent(url -> {
                        url.incrementClickCount();
                        urlRepository.save(url);
                        log.debug("Incremented click count for: {}",
                                event.getShortCode());
                    });
        } catch (Exception e) {
            log.error("Failed to process click event for {}: {}",
                    event.getShortCode(), e.getMessage());
            // Don't rethrow — analytics failures are non-fatal
        }
    }
}
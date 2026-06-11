package com.urlshortener.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background job that deactivates expired URLs.
 *
 * INTERVIEW: "Why not just check expiry on every redirect?"
 * We do check on redirect (isExpired() guard in resolveShortCode).
 * But expired rows accumulate in the DB over time — they waste space
 * and slow down queries that don't filter by active status.
 *
 * This job runs nightly to soft-delete expired entries in bulk.
 *
 * INTERVIEW: "What if multiple instances run this job simultaneously?"
 * In a horizontally scaled deployment, every instance runs @Scheduled.
 * Multiple instances deactivating the same rows is harmless (idempotent),
 * but wasteful. Production solution: ShedLock or Quartz clustering
 * ensures only one instance runs the job at a time.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UrlExpiryCleanupJob {

    private final UrlService urlService;

    /**
     * Runs daily at 2 AM.
     * Cron: second minute hour day month weekday
     *
     * WHY 2 AM? Lowest traffic period — minimizes lock contention
     * during the bulk UPDATE operation.
     *
     * INTERVIEW: "How would you make this configurable?"
     * Move the cron expression to application.yml and inject via @Value.
     * That way ops can adjust the schedule without a code deploy.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void cleanupExpiredUrls() {
        log.info("Starting expired URL cleanup job");
        int count = urlService.deactivateExpiredUrls();
        log.info("Expired URL cleanup complete. Deactivated: {}", count);
    }
}
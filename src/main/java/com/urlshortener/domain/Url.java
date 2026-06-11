package com.urlshortener.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Core domain entity representing a shortened URL mapping.
 *
 * INTERVIEW NOTE: This is intentionally a JPA @Entity (ORM-mapped POJO).
 * An alternative approach is to use plain SQL with jOOQ or JDBC Template
 * for more control — worth mentioning at senior level.
 *
 * SCALABILITY NOTE: This table will be read ~100x more than written.
 * Design decisions here cascade into index strategy and cache key design.
 */
@Entity
@Table(
        name = "urls",
        indexes = {
                /*
                 * WHY THIS INDEX?
                 * Every redirect hits: SELECT * FROM urls WHERE short_code = ?
                 * Without this index, that's a full table scan.
                 * At 1M rows, a full scan takes ~seconds. With this index: microseconds.
                 *
                 * INTERVIEW: "What indexes would you add to this table?"
                 * This is the #1 answer. Unique constraint also prevents collisions
                 * at the DB level — a safety net on top of our app-level logic.
                 */
                @Index(name = "idx_urls_short_code", columnList = "short_code", unique = true),

                /*
                 * WHY THIS INDEX?
                 * Supports: "find all URLs created by user X" and
                 * "has this long URL been shortened before?" (deduplication).
                 * Not unique — same long URL can have multiple short codes.
                 */
                @Index(name = "idx_urls_original_url", columnList = "original_url"),

                /*
                 * WHY THIS INDEX?
                 * Future feature: "show me all URLs created by user X".
                 * Also useful for multi-tenant scenarios.
                 * Nullable — anonymous users don't have an ID.
                 */
                @Index(name = "idx_urls_user_id", columnList = "user_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
/*
 * WHY @Builder?
 * Immutable-style construction. Forces explicit field-setting at creation time.
 * Preferred over setter chaining for domain objects — clearer intent.
 * INTERVIEW: Discuss Builder pattern vs constructors vs setters and when to use each.
 */
public class Url {

    /**
     * Numeric surrogate primary key.
     *
     * WHY Long, not UUID?
     * 1. B-tree indexes on Long are 4-8x more compact than UUID strings.
     * 2. The Base62 encoding in Step 4 converts this to the short code.
     * 3. Sequential inserts are cache-friendly for B-tree pages.
     *
     * SCALABILITY NOTE: IDENTITY strategy means DB generates the ID.
     * In a distributed system with multiple DB writers, you'd switch to
     * a Snowflake ID generator (like your architecture diagram shows)
     * to avoid ID coordination across nodes.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The 7-character Base62-encoded short code (e.g., "aB3xY7k").
     *
     * WHY nullable = false + unique = true?
     * 1. Every URL must have a short code — it's the core of this service.
     * 2. Unique constraint is a DB-level safety net against race conditions.
     *    App logic prevents collisions first; DB constraint is the fallback.
     *
     * WHY length = 10 and not 7?
     * Custom aliases (optional feature) can be longer. 10 chars gives headroom.
     */
    @Column(name = "short_code", nullable = false, unique = true, length = 10)
    private String shortCode;

    /**
     * The original long URL to redirect to.
     *
     * WHY length = 2048?
     * RFC 2616 doesn't define a URL length limit, but browsers cap at ~2048.
     * Most real URLs are well under this. Going higher wastes storage
     * and makes the index larger.
     *
     * INTERVIEW: What if someone submits a URL that's 10,000 chars?
     * → Validate at DTO layer with @URL and @Size(max = 2048).
     */
    @Column(name = "original_url", nullable = false, length = 2048)
    private String originalUrl;

    /**
     * Optional custom alias provided by the user (e.g., "my-blog").
     * If null, a Base62 code is generated. If set, it IS the short code.
     *
     * INTERVIEW: How do you prevent users from claiming reserved aliases
     * like "api", "health", "admin"?
     * → Maintain a blocklist and check before saving.
     */
    @Column(name = "custom_alias", length = 50)
    private String customAlias;

    /**
     * Click counter — incremented on every successful redirect.
     *
     * SCALABILITY RED FLAG: If you update this on every redirect
     * in a synchronous DB write, at 100K req/s you'll saturate the DB.
     *
     * PRODUCTION SOLUTION (from your architecture diagram):
     * → Fire-and-forget async event to Kafka → consumer batch-updates counts.
     * → Or use Redis INCR (atomic, in-memory) and sync to DB periodically.
     *
     * For Phase 1, synchronous is fine. We'll fix this in Step 12.
     */
    @Column(name = "click_count", nullable = false)
    @Builder.Default
    private Long clickCount = 0L;

    /**
     * Expiration timestamp. Null means never expires.
     *
     * WHY LocalDateTime and not Instant?
     * Instant is timezone-agnostic (always UTC). LocalDateTime is
     * ambiguous without a timezone. For a global service, Instant is correct.
     *
     * INTERVIEW TRAP: Many candidates use LocalDateTime here. Challenge them
     * on what happens when servers are in different timezones.
     *
     * We use LocalDateTime for simplicity in Phase 1 but NOTE this as a
     * known improvement — shows senior awareness.
     */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /**
     * Optional. For future multi-tenant or user-specific features.
     * Null for anonymous URL creation.
     */
    @Column(name = "user_id")
    private String userId;

    /**
     * Whether this URL has been soft-deleted / deactivated.
     *
     * WHY soft delete vs hard delete?
     * Hard delete makes analytics impossible (click history gone).
     * Soft delete lets you audit, restore, and maintain referential integrity.
     *
     * INTERVIEW: What are the downsides of soft delete?
     * → Queries must always filter WHERE active = true (easy to forget).
     * → Table never shrinks — needs a periodic archival job.
     */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * Audit timestamps — non-negotiable in any production schema.
     *
     * @CreationTimestamp / @UpdateTimestamp are Hibernate annotations
     * that auto-set these without you doing anything.
     *
     * INTERVIEW: How do you audit who changed what and when?
     * → Add created_by, updated_by columns.
     * → Or use a separate audit_log table (Spring Data Envers does this).
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ── Domain logic lives on the entity, not the service ──────────────────

    /**
     * Pure domain method: is this URL still usable?
     *
     * WHY put this on the entity?
     * This is business logic about the URL's own state.
     * Keeping it here avoids "anemic domain model" anti-pattern —
     * where entities are just data bags and all logic leaks into services.
     *
     * INTERVIEW: "What is an anemic domain model and why is it a problem?"
     * → Services become bloated; domain rules are scattered; hard to test.
     */
    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isUsable() {
        return active && !isExpired();
    }

    /**
     * Increment click count. Domain logic on the entity.
     * In Phase 1 this is a simple counter. In Phase 3+ it becomes
     * an async Redis operation.
     */
    public void incrementClickCount() {
        this.clickCount++;
    }
}
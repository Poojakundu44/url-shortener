package com.urlshortener.repository;

import com.urlshortener.domain.Url;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Data access layer for the Url entity.
 *
 * WHY extend JpaRepository and not CrudRepository or PagingAndSortingRepository?
 *
 * Hierarchy (each extends the previous):
 *   Repository (marker)
 *     └─ CrudRepository        → save, findById, findAll, delete, count
 *         └─ PagingAndSortingRepository → + paging/sorting
 *             └─ JpaRepository → + flush, saveAndFlush, deleteInBatch
 *
 * JpaRepository gives you everything. The cost is a slightly wider interface
 * surface — some argue for narrower interfaces (Interface Segregation Principle).
 * At senior level: mention you could expose a narrower custom interface
 * to callers and implement it with JpaRepository internally.
 */
@Repository
public interface UrlRepository extends JpaRepository<Url, Long> {

    /**
     * THE most critical query in this entire service.
     * Called on every single redirect.
     *
     * Spring Data JPA parses "findBy" + "ShortCode" and generates:
     *   SELECT * FROM urls WHERE short_code = ?
     *
     * WHY Optional<Url> and not Url?
     * Returning null is the source of countless NullPointerExceptions.
     * Optional<T> forces the caller to explicitly handle the "not found" case.
     * At senior level: you should NEVER return null from a repository method.
     *
     * PERFORMANCE: This hits the idx_urls_short_code index we defined.
     * Expected query time: O(log n) B-tree lookup — microseconds.
     *
     * INTERVIEW: "What would happen if you forgot the unique index on short_code?"
     * → Full table scan on every redirect. At 1M URLs: catastrophic.
     */
    Optional<Url> findByShortCode(String shortCode);

    /**
     * Used to check if a short code is already taken before inserting.
     * More efficient than findByShortCode() when you only need existence,
     * not the full entity — avoids fetching all columns.
     *
     * Spring generates: SELECT COUNT(*) > 0 FROM urls WHERE short_code = ?
     *
     * INTERVIEW: "Why existsBy instead of findBy + isPresent()?"
     * → existsBy generates a COUNT query — no data transfer, just a boolean.
     *   findBy fetches the whole row across the network. At scale, this matters.
     */
    boolean existsByShortCode(String shortCode);

    /**
     * Check if a custom alias is already in use.
     * Same efficiency argument as above.
     */
    boolean existsByCustomAlias(String customAlias);

    /**
     * Deduplication query: has this long URL been shortened before?
     *
     * WHY might you want this?
     * If the same user submits "https://google.com" twice, do you create
     * two short codes or return the existing one?
     *
     * Design decision: for anonymous users, always create a new code.
     * For authenticated users, optionally return the existing one.
     * This query supports that second path.
     *
     * NOTE: We include `active = true` — don't return a deactivated mapping.
     */
    Optional<Url> findByOriginalUrlAndActiveTrue(String originalUrl);

    /**
     * Admin / user-facing: list all URLs for a given user.
     *
     * WHY List and not Page<Url>?
     * For now, simplicity. In production, ALWAYS paginate list endpoints.
     * Returning all rows for a power user with 10,000 URLs will OOM your server.
     *
     * We'll add Pageable support in a later step.
     * Mentioning this unprompted in interviews scores points.
     */
    List<Url> findByUserIdAndActiveTrueOrderByCreatedAtDesc(String userId);

    /**
     * Scheduled cleanup job query: find all expired URLs to archive or delete.
     *
     * WHY @Query here instead of derived method?
     * The derived method name would be:
     *   findByExpiresAtIsNotNullAndExpiresAtBeforeAndActiveTrue(LocalDateTime)
     * That's unreadable. @Query with JPQL is cleaner for complex predicates.
     *
     * JPQL vs native SQL:
     * JPQL operates on entity names/field names (Url, expiresAt).
     * Native SQL operates on table/column names (urls, expires_at).
     * JPQL is DB-agnostic — works with H2, PostgreSQL, MySQL unchanged.
     */
    @Query("""
            SELECT u FROM Url u
            WHERE u.expiresAt IS NOT NULL
              AND u.expiresAt < :now
              AND u.active = true
            """)
    List<Url> findExpiredUrls(@Param("now") LocalDateTime now);

    /**
     * Bulk deactivation of expired URLs.
     *
     * WHY @Modifying?
     * Any query that mutates data (UPDATE, DELETE) requires @Modifying.
     * Without it, Spring Data throws an exception.
     *
     * WHY clearAutomatically = true?
     * After a bulk update via JPQL, the first-level cache (EntityManager)
     * still holds the OLD state of those entities. clearAutomatically
     * clears the cache so subsequent reads get fresh data.
     *
     * INTERVIEW TRAP: Candidates who don't know about first-level cache
     * will write this without clearAutomatically and then see stale reads.
     * This is a very common bug in production Spring apps.
     *
     * SCALABILITY: Bulk UPDATE is far more efficient than loading each entity,
     * setting active=false, and saving one by one (N+1 write problem).
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Url u
            SET u.active = false, u.updatedAt = :now
            WHERE u.expiresAt IS NOT NULL
              AND u.expiresAt < :now
              AND u.active = true
            """)
    int deactivateExpiredUrls(@Param("now") LocalDateTime now);

    /**
     * Analytics query: total click count across all URLs for a user.
     *
     * WHY @Query with SUM aggregation?
     * You could load all URLs and sum in Java, but that transfers potentially
     * thousands of rows across the network just to get one number.
     * Aggregation in the DB is orders of magnitude more efficient.
     *
     * INTERVIEW: "Where should aggregation happen — DB or application layer?"
     * → DB, unless you have a very good reason not to (e.g., complex logic
     *   that SQL can't express, or you're already loading the data anyway).
     */
    @Query("""
            SELECT COALESCE(SUM(u.clickCount), 0)
            FROM Url u
            WHERE u.userId = :userId
              AND u.active = true
            """)
    Long sumClickCountByUserId(@Param("userId") String userId);
}
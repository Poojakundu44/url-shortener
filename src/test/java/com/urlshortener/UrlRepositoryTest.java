package com.urlshortener;

import com.urlshortener.domain.Url;
import com.urlshortener.repository.UrlRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice test for the repository layer.
 *
 * WHY @DataJpaTest and not @SpringBootTest?
 * @DataJpaTest is a test slice — it only loads the JPA layer:
 * entities, repositories, and an in-memory H2 DB.
 * It does NOT load controllers, services, or the full application context.
 *
 * Result: tests run in ~2 seconds instead of ~10 seconds.
 *
 * INTERVIEW: "What are Spring Boot test slices? Name some examples."
 * → @DataJpaTest (JPA), @WebMvcTest (controllers), @JsonTest (JSON serialization)
 *
 * Each slice loads only what it needs — fast, focused tests.
 */
@DataJpaTest
class UrlRepositoryTest {

    @Autowired
    private UrlRepository urlRepository;

    @Test
    @DisplayName("should save and retrieve URL by short code")
    void shouldFindByShortCode() {
        Url url = Url.builder()
                .shortCode("abc1234")
                .originalUrl("https://www.example.com/very/long/path")
                .active(true)
                .clickCount(0L)
                .build();

        urlRepository.save(url);

        Optional<Url> found = urlRepository.findByShortCode("abc1234");

        assertThat(found).isPresent();
        assertThat(found.get().getOriginalUrl()).isEqualTo("https://www.example.com/very/long/path");
    }

    @Test
    @DisplayName("should return empty Optional for unknown short code")
    void shouldReturnEmptyForUnknownCode() {
        Optional<Url> found = urlRepository.findByShortCode("doesnotexist");
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("should detect expired URLs")
    void shouldFindExpiredUrls() {
        Url expired = Url.builder()
                .shortCode("exp1234")
                .originalUrl("https://example.com")
                .active(true)
                .clickCount(0L)
                .expiresAt(LocalDateTime.now().minusDays(1)) // expired yesterday
                .build();

        urlRepository.save(expired);

        var expiredList = urlRepository.findExpiredUrls(LocalDateTime.now());

        assertThat(expiredList).hasSize(1);
        assertThat(expiredList.get(0).getShortCode()).isEqualTo("exp1234");
    }

    @Test
    @DisplayName("existsByShortCode should return true for existing code")
    void shouldDetectExistingShortCode() {
        Url url = Url.builder()
                .shortCode("exists7")
                .originalUrl("https://example.com")
                .active(true)
                .clickCount(0L)
                .build();

        urlRepository.save(url);

        assertThat(urlRepository.existsByShortCode("exists7")).isTrue();
        assertThat(urlRepository.existsByShortCode("missing7")).isFalse();
    }
}
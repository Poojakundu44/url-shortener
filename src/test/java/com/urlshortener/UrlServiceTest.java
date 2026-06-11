package com.urlshortener.service;

import com.urlshortener.config.AppProperties;
import com.urlshortener.domain.Url;
import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.UrlResponse;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * Unit tests for UrlService.
 *
 * INTERVIEW: "What's the difference between @ExtendWith(MockitoExtension.class)
 * and @SpringBootTest?"
 *
 * @ExtendWith(MockitoExtension.class):
 * - Pure Mockito, zero Spring context
 * - Instantiates the class under test directly
 * - Mocks injected via @Mock + @InjectMocks
 * - Runs in ~50ms — blazing fast
 * - Tests ONLY the class's logic, not its wiring
 *
 * @SpringBootTest:
 * - Loads full Spring application context
 * - Real beans, real DB (or TestContainers), real config
 * - Runs in 5-15 seconds
 * - Tests how components work together
 *
 * Rule of thumb: unit test every branch of business logic.
 * Integration test the wiring and happy paths.
 *
 * WHY @Nested?
 * Groups related tests together. "When creating a URL" → all creation tests.
 * "When resolving a short code" → all redirect tests. Makes failures
 * immediately obvious: "UrlServiceTest > When resolving > expired URL → FAILED"
 */
@ExtendWith(MockitoExtension.class)
class UrlServiceTest {

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private ShortCodeGenerator shortCodeGenerator;

    @Mock
    private AppProperties appProperties;

    @InjectMocks
    private UrlService urlService;

    @BeforeEach
    void setUp() {
        // Configure app properties defaults
        given(appProperties.getDefaultExpiryDays()).willReturn(365);
        given(appProperties.getBaseUrl()).willReturn("http://localhost:8080");
    }

    @Nested
    @DisplayName("When creating a short URL")
    class WhenCreating {

        @Test
        @DisplayName("should create URL with generated short code")
        void shouldCreateWithGeneratedCode() {
            // GIVEN
            CreateUrlRequest request = CreateUrlRequest.builder()
                    .originalUrl("https://example.com/very/long/path")
                    .build();

            /*
             * WHY two save stubs with different return values?
             * The two-save pattern: first save returns entity with ID,
             * second save returns entity with short code.
             * willReturn(...).willReturn(...) stubs in sequence.
             */
            Url savedWithId = Url.builder()
                    .id(1L)
                    .originalUrl("https://example.com/very/long/path")
                    .clickCount(0L)
                    .active(true)
                    .build();

            Url savedWithCode = Url.builder()
                    .id(1L)
                    .shortCode("0000001")
                    .originalUrl("https://example.com/very/long/path")
                    .clickCount(0L)
                    .active(true)
                    .createdAt(LocalDateTime.now())
                    .build();

            given(urlRepository.save(any(Url.class)))
                    .willReturn(savedWithId)   // first save
                    .willReturn(savedWithCode); // second save

            given(shortCodeGenerator.generateFromId(1L)).willReturn("0000001");

            // WHEN
            UrlResponse response = urlService.createShortUrl(request);

            // THEN
            assertThat(response.getShortCode()).isEqualTo("0000001");
            assertThat(response.getShortUrl()).isEqualTo("http://localhost:8080/0000001");
            assertThat(response.getOriginalUrl()).isEqualTo("https://example.com/very/long/path");
            then(urlRepository).should(times(2)).save(any(Url.class));
        }

        @Test
        @DisplayName("should create URL with custom alias")
        void shouldCreateWithCustomAlias() {
            CreateUrlRequest request = CreateUrlRequest.builder()
                    .originalUrl("https://example.com")
                    .customAlias("my-blog")
                    .build();

            given(shortCodeGenerator.validateAndNormalizeCustomAlias("my-blog"))
                    .willReturn("my-blog");

            Url saved = Url.builder()
                    .id(2L)
                    .shortCode("my-blog")
                    .customAlias("my-blog")
                    .originalUrl("https://example.com")
                    .clickCount(0L)
                    .active(true)
                    .createdAt(LocalDateTime.now())
                    .build();

            given(urlRepository.save(any(Url.class))).willReturn(saved);

            UrlResponse response = urlService.createShortUrl(request);

            assertThat(response.getShortCode()).isEqualTo("my-blog");
            // Custom alias: only ONE save (no two-save pattern needed)
            then(urlRepository).should(times(1)).save(any(Url.class));
        }
    }

    @Nested
    @DisplayName("When resolving a short code")
    class WhenResolving {

        @Test
        @DisplayName("should return original URL for valid active code")
        void shouldResolveValidCode() {
            Url activeUrl = Url.builder()
                    .shortCode("abc1234")
                    .originalUrl("https://example.com")
                    .active(true)
                    .clickCount(0L)
                    .expiresAt(LocalDateTime.now().plusDays(30)) // not expired
                    .build();

            given(urlRepository.findByShortCode("abc1234"))
                    .willReturn(Optional.of(activeUrl));
            given(urlRepository.save(any(Url.class))).willReturn(activeUrl);

            String result = urlService.resolveShortCode("abc1234");

            assertThat(result).isEqualTo("https://example.com");
        }

        @Test
        @DisplayName("should throw UrlNotFoundException for unknown code")
        void shouldThrowForUnknownCode() {
            given(urlRepository.findByShortCode("unknown"))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> urlService.resolveShortCode("unknown"))
                    .isInstanceOf(UrlNotFoundException.class)
                    .hasMessageContaining("unknown");
        }

        @Test
        @DisplayName("should throw UrlExpiredException for expired URL")
        void shouldThrowForExpiredUrl() {
            Url expiredUrl = Url.builder()
                    .shortCode("expired")
                    .originalUrl("https://example.com")
                    .active(true)
                    .clickCount(0L)
                    .expiresAt(LocalDateTime.now().minusDays(1)) // expired yesterday
                    .build();

            given(urlRepository.findByShortCode("expired"))
                    .willReturn(Optional.of(expiredUrl));

            assertThatThrownBy(() -> urlService.resolveShortCode("expired"))
                    .isInstanceOf(UrlExpiredException.class);
        }

        @Test
        @DisplayName("should throw UrlNotFoundException for deactivated URL")
        void shouldThrowForDeactivatedUrl() {
            Url deactivated = Url.builder()
                    .shortCode("deleted")
                    .originalUrl("https://example.com")
                    .active(false) // soft-deleted
                    .clickCount(0L)
                    .build();

            given(urlRepository.findByShortCode("deleted"))
                    .willReturn(Optional.of(deactivated));

            assertThatThrownBy(() -> urlService.resolveShortCode("deleted"))
                    .isInstanceOf(UrlNotFoundException.class);
        }

        @Test
        @DisplayName("should increment click count on successful resolve")
        void shouldIncrementClickCount() {
            Url url = Url.builder()
                    .shortCode("click1")
                    .originalUrl("https://example.com")
                    .active(true)
                    .clickCount(5L)
                    .expiresAt(LocalDateTime.now().plusDays(10))
                    .build();

            given(urlRepository.findByShortCode("click1"))
                    .willReturn(Optional.of(url));
            given(urlRepository.save(any(Url.class))).willReturn(url);

            urlService.resolveShortCode("click1");

            // Verify the entity's click count was incremented before save
            assertThat(url.getClickCount()).isEqualTo(6L);
            then(urlRepository).should().save(url);
        }
    }

    @Nested
    @DisplayName("When deactivating a URL")
    class WhenDeactivating {

        @Test
        @DisplayName("should set active to false")
        void shouldDeactivate() {
            Url url = Url.builder()
                    .shortCode("todelete")
                    .originalUrl("https://example.com")
                    .active(true)
                    .clickCount(0L)
                    .build();

            given(urlRepository.findByShortCode("todelete"))
                    .willReturn(Optional.of(url));
            given(urlRepository.save(any(Url.class))).willReturn(url);

            urlService.deactivateUrl("todelete");

            assertThat(url.isActive()).isFalse();
            then(urlRepository).should().save(url);
        }

        @Test
        @DisplayName("should throw UrlNotFoundException for unknown code")
        void shouldThrowForUnknown() {
            given(urlRepository.findByShortCode("ghost"))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> urlService.deactivateUrl("ghost"))
                    .isInstanceOf(UrlNotFoundException.class);
        }
    }
}
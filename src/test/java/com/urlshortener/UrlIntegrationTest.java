package com.urlshortener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.dto.CreateUrlRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full integration tests using the real Spring context + H2 database.
 *
 * INTERVIEW: "What does @SpringBootTest do differently from @WebMvcTest?"
 *
 * @WebMvcTest: loads ONLY the web layer (controllers, filters, exception handlers).
 * Services and repositories are mocked. Fast (~1-2s). Good for testing
 * controller logic, request mapping, and response serialization in isolation.
 *
 * @SpringBootTest: loads the FULL application context — controllers, services,
 * repositories, database, everything. Slow (~5-10s). Tests the complete stack
 * from HTTP request to database and back.
 *
 * @AutoConfigureMockMvc: wires up MockMvc without starting a real HTTP server.
 * Requests go through the full filter chain and dispatcher servlet,
 * but in-process — no TCP overhead.
 *
 * @DirtiesContext: resets the Spring context after each test class.
 * Necessary here because H2 in-memory data persists across tests
 * in the same context — test isolation requires a fresh DB each time.
 *
 * ALTERNATIVE for better isolation and speed: use @Transactional on each test
 * — Spring rolls back after each test automatically. But this doesn't work
 * for tests that check redirect behavior (since the redirect check reads
 * data written in the same test — transaction boundaries matter here).
 * @DirtiesContext is the simpler, more explicit choice for this project.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class UrlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ── CREATE tests ────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/urls → 201 with short URL in response")
    void shouldCreateShortUrl() throws Exception {
        CreateUrlRequest request = CreateUrlRequest.builder()
                .originalUrl("https://www.example.com/very/long/path/to/page")
                .build();

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").isNotEmpty())
                .andExpect(jsonPath("$.shortUrl").value(containsString("http://localhost:8080/")))
                .andExpect(jsonPath("$.originalUrl")
                        .value("https://www.example.com/very/long/path/to/page"))
                .andExpect(jsonPath("$.clickCount").value(0))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/urls → 400 for invalid URL")
    void shouldRejectInvalidUrl() throws Exception {
        CreateUrlRequest request = CreateUrlRequest.builder()
                .originalUrl("not-a-url")
                .build();

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.originalUrl").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/v1/urls → 400 for blank URL")
    void shouldRejectBlankUrl() throws Exception {
        CreateUrlRequest request = CreateUrlRequest.builder()
                .originalUrl("")
                .build();

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/v1/urls with custom alias → 201")
    void shouldCreateWithCustomAlias() throws Exception {
        CreateUrlRequest request = CreateUrlRequest.builder()
                .originalUrl("https://example.com")
                .customAlias("my-blog")
                .build();

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("my-blog"));
    }

    @Test
    @DisplayName("POST /api/v1/urls with duplicate custom alias → 400")
    void shouldRejectDuplicateAlias() throws Exception {
        CreateUrlRequest request = CreateUrlRequest.builder()
                .originalUrl("https://example.com")
                .customAlias("duplicate")
                .build();

        // First creation — should succeed
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Second creation with same alias — should fail
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("duplicate")));
    }

    // ── REDIRECT tests ──────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /{shortCode} → 302 redirect to original URL")
    void shouldRedirectToOriginalUrl() throws Exception {
        // First, create a short URL
        CreateUrlRequest request = CreateUrlRequest.builder()
                .originalUrl("https://www.example.com/target")
                .build();

        MvcResult createResult = mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        // Extract the short code from the response
        String responseBody = createResult.getResponse().getContentAsString();
        String shortCode = objectMapper.readTree(responseBody).get("shortCode").asText();

        // Now perform the redirect
        mockMvc.perform(get("/" + shortCode))
                .andExpect(status().isFound()) // 302
                .andExpect(header().string("Location", "https://www.example.com/target"));
    }

    @Test
    @DisplayName("GET /{shortCode} → 404 for unknown code")
    void shouldReturn404ForUnknownCode() throws Exception {
        mockMvc.perform(get("/doesnotexist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/doesnotexist"));
    }

    @Test
    @DisplayName("GET /{shortCode} increments click count")
    void shouldIncrementClickCount() throws Exception {
        // Create URL
        CreateUrlRequest request = CreateUrlRequest.builder()
                .originalUrl("https://example.com/analytics-test")
                .build();

        MvcResult createResult = mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        String shortCode = objectMapper.readTree(
                        createResult.getResponse().getContentAsString())
                .get("shortCode").asText();

        // Hit the redirect 3 times
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/" + shortCode))
                    .andExpect(status().isFound());
        }

        // Verify click count = 3
        mockMvc.perform(get("/api/v1/urls/" + shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clickCount").value(3));
    }

    // ── DEACTIVATION tests ──────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /api/v1/urls/{shortCode} → 204, then GET → 404")
    void shouldDeactivateUrl() throws Exception {
        // Create
        CreateUrlRequest request = CreateUrlRequest.builder()
                .originalUrl("https://example.com/to-delete")
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        String shortCode = objectMapper.readTree(
                        result.getResponse().getContentAsString())
                .get("shortCode").asText();

        // Deactivate
        mockMvc.perform(delete("/api/v1/urls/" + shortCode))
                .andExpect(status().isNoContent());

        // Redirect should now 404
        mockMvc.perform(get("/" + shortCode))
                .andExpect(status().isNotFound());
    }
}
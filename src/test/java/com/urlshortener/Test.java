package com.urlshortener;

import com.urlshortener.service.Base62Encoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Pure unit test — no Spring context, no database, no mocks.
 * Base62Encoder is a plain Java class. Tests run in milliseconds.
 *
 * INTERVIEW: "How do you decide what to unit test vs integration test?"
 * Unit test: pure logic with no external dependencies (encoding, parsing,
 * business rules). Fast, isolated, no infrastructure needed.
 * Integration test: anything involving DB, HTTP, Redis, Kafka.
 */
class Base62EncoderTest {

    private Base62Encoder encoder;

    @BeforeEach
    void setUp() {
        encoder = new Base62Encoder();
    }

    @Test
    @DisplayName("encode then decode should return original value (round-trip)")
    void roundTrip() {
        long original = 10_000_001L;
        String encoded = encoder.encode(original);
        long decoded = encoder.decode(encoded);
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("different IDs should produce different codes")
    void uniqueness() {
        String code1 = encoder.encode(1L);
        String code2 = encoder.encode(2L);
        assertThat(code1).isNotEqualTo(code2);
    }

    @Test
    @DisplayName("encode(0) should return '0'")
    void encodeZero() {
        assertThat(encoder.encode(0L)).isEqualTo("0");
    }

    @Test
    @DisplayName("encode should reject negative IDs")
    void rejectNegative() {
        assertThatThrownBy(() -> encoder.encode(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("decode should reject invalid characters")
    void rejectInvalidChars() {
        assertThatThrownBy(() -> encoder.decode("abc+def"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid character");
    }

    @Test
    @DisplayName("encode should produce only Base62 characters")
    void onlyBase62Chars() {
        for (long id = 1; id <= 10_000; id++) {
            String code = encoder.encode(id);
            assertThat(code).matches("[0-9A-Za-z]+");
        }
    }

    @Test
    @DisplayName("larger IDs produce longer or equal length codes")
    void monotonicallyNonDecreasing() {
        // 62^1 = 62 boundary
        assertThat(encoder.encode(61L).length()).isEqualTo(1);
        assertThat(encoder.encode(62L).length()).isEqualTo(2);

        // 62^6 boundary
        long boundary = (long) Math.pow(62, 6);
        assertThat(encoder.encode(boundary - 1).length()).isEqualTo(6);
        assertThat(encoder.encode(boundary).length()).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 100L, 9999L, 1_000_000L, Long.MAX_VALUE / 2})
    @DisplayName("round-trip should hold for various ID sizes")
    void roundTripParameterized(long id) {
        assertThat(encoder.decode(encoder.encode(id))).isEqualTo(id);
    }
}
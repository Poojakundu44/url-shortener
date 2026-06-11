package com.urlshortener.service;

import org.springframework.stereotype.Component;

/**
 * Converts a numeric database ID into a Base62 short code.
 *
 * WHY a @Component and not a static utility class?
 * Static utility classes are harder to mock in unit tests.
 * As a Spring bean, you can inject it, mock it with Mockito,
 * and swap the implementation without changing callers.
 *
 * INTERVIEW: "Why not just use UUID.randomUUID() for short codes?"
 * 1. UUIDs are 36 chars — defeats the purpose of a URL shortener.
 * 2. With Base62(DB ID): uniqueness is GUARANTEED by the PK.
 *    With random: you need collision checks that grow worse over time.
 * 3. Base62 is O(log₆₂ n) to generate — essentially O(1). No DB read needed.
 */
@Component
public class Base62Encoder {

    /**
     * The Base62 alphabet.
     *
     * ORDER MATTERS for security: if you use 0-9A-Za-z in sequence,
     * short codes are visually predictable (ID 1 → "1", ID 62 → "A").
     * For a production system, shuffle this alphabet — then sequential IDs
     * produce non-sequential, non-guessable codes.
     *
     * We use the standard order here for clarity. Shuffling is a
     * one-line change and a great interview talking point.
     */
    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private static final int BASE = ALPHABET.length(); // 62

    /**
     * Encodes a positive long into a Base62 string.
     *
     * ALGORITHM: Classic base conversion — same as converting decimal to hex,
     * but with base 62.
     *
     * TIME COMPLEXITY: O(log₆₂ n) — for a 64-bit long (~9.2 × 10¹⁸),
     * this is at most 11 iterations. Effectively O(1).
     *
     * SPACE COMPLEXITY: O(log₆₂ n) for the result string.
     *
     * INTERVIEW TRAP: What if id = 0?
     * → We handle it explicitly. 0 % 62 = 0, loop exits immediately,
     *   leaving an empty StringBuilder. Edge case must be caught.
     *
     * INTERVIEW TRAP: What about negative IDs?
     * → DB IDENTITY PKs are always positive. But defensive programming:
     *   we throw IllegalArgumentException rather than silently produce garbage.
     */
    public String encode(long id) {
        if (id < 0) {
            throw new IllegalArgumentException(
                    "Cannot encode negative ID: " + id);
        }
        if (id == 0) {
            return String.valueOf(ALPHABET.charAt(0)); // "0"
        }

        StringBuilder sb = new StringBuilder();
        long remaining = id;

        while (remaining > 0) {
            int remainder = (int) (remaining % BASE);
            sb.append(ALPHABET.charAt(remainder));
            remaining /= BASE;
        }

        /*
         * WHY reverse?
         * We build digits least-significant first (same as decimal division).
         * "123" in decimal: 123%10=3, 12%10=2, 1%10=1 → "321" → reverse → "123".
         * Same logic applies here.
         */
        return sb.reverse().toString();
    }

    /**
     * Decodes a Base62 string back to its original long value.
     *
     * WHY implement decode?
     * Two reasons:
     * 1. We don't actually use it in the redirect path — we look up by
     *    short_code directly (the indexed column). We do NOT decode to ID
     *    and then do findById(). That would be slower and fragile.
     * 2. But having it is useful for debugging, analytics, and admin tools.
     *    Also, interviewers will ask you to implement it.
     *
     * INTERVIEW: "Given a short code, how do you find the original URL?"
     * WRONG answer: decode to ID → findById(id)
     * RIGHT answer: findByShortCode(shortCode) — direct indexed lookup.
     * The decode method exists for completeness, not for the hot path.
     */
    public long decode(String shortCode) {
        if (shortCode == null || shortCode.isBlank()) {
            throw new IllegalArgumentException("Short code cannot be blank");
        }

        long result = 0;
        for (char c : shortCode.toCharArray()) {
            int index = ALPHABET.indexOf(c);
            if (index == -1) {
                throw new IllegalArgumentException(
                        "Invalid character in short code: '" + c + "'");
            }
            /*
             * Horner's method: process left to right, multiply accumulator by base.
             * "abc" = a*62² + b*62¹ + c*62⁰
             * Iteration 1: result = 0 * 62 + a
             * Iteration 2: result = a * 62 + b
             * Iteration 3: result = (a*62 + b) * 62 + c = a*62² + b*62 + c ✓
             */
            result = result * BASE + index;
        }
        return result;
    }
}
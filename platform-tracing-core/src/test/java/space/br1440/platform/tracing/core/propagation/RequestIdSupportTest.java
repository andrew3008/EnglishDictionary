package space.br1440.platform.tracing.core.propagation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for static request id validation and reject-and-regenerate behavior.
 */
@DisplayName("RequestIdSupport: edge-stable correlation id (validate + reject-and-regenerate)")
class RequestIdSupportTest {

    @Test
    @DisplayName("sanitizeOrNull(null) returns null")
    void sanitizeReturnsNullWhenAbsent() {
        assertThat(RequestIdSupport.sanitizeOrNull(null)).isNull();
    }

    @Test
    @DisplayName("valid incoming value is forwarded unchanged")
    void reusesValidIncoming() {
        assertThat(RequestIdSupport.sanitizeOrNull("req-123_ABC")).isEqualTo("req-123_ABC");
        assertThat(RequestIdSupport.resolve("req-123_ABC")).isEqualTo("req-123_ABC");
    }

    @Test
    @DisplayName("MAX_LENGTH boundary is accepted")
    void acceptsMaxLengthBoundary() {
        String boundary = "a".repeat(RequestIdSupport.MAX_LENGTH);
        assertThat(RequestIdSupport.sanitizeOrNull(boundary)).isEqualTo(boundary);
    }

    @Test
    @DisplayName("MAX_LENGTH + 1 is rejected")
    void rejectsOversized() {
        String oversized = "a".repeat(RequestIdSupport.MAX_LENGTH + 1);
        assertThat(RequestIdSupport.sanitizeOrNull(oversized)).isNull();
    }

    @Test
    @DisplayName("CRLF is rejected and regenerated")
    void rejectsCrlf() {
        assertThat(RequestIdSupport.sanitizeOrNull("abc\r\nSet-Cookie: x")).isNull();
        String resolved = RequestIdSupport.resolve("abc\r\nSet-Cookie: x");
        assertThat(resolved).doesNotContain("\r").doesNotContain("\n");
        assertThat(RequestIdSupport.sanitizeOrNull(resolved)).isEqualTo(resolved);
    }

    @Test
    @DisplayName("control characters, spaces, and punctuation are rejected")
    void rejectsNonAllowlistedCharacters() {
        assertThat(RequestIdSupport.sanitizeOrNull("ab\u0000cd")).isNull();
        assertThat(RequestIdSupport.sanitizeOrNull("ab cd")).isNull();
        assertThat(RequestIdSupport.sanitizeOrNull("ab.cd")).isNull();
    }

    @Test
    @DisplayName("resolve(null) returns a valid generated UUID-like fallback")
    void generatesUuidWhenAbsent() {
        String id = RequestIdSupport.resolve(null);
        assertThat(id).isNotBlank();
        assertThat(id).contains("-");
        assertThat(RequestIdSupport.sanitizeOrNull(id)).isEqualTo(id);
    }

    @Test
    @DisplayName("resolve(invalid) returns a valid generated fallback")
    void generatesFallbackWhenInvalid() {
        String id = RequestIdSupport.resolve("bad/request/id");
        assertThat(id).isNotBlank();
        assertThat(RequestIdSupport.sanitizeOrNull(id)).isEqualTo(id);
    }
}

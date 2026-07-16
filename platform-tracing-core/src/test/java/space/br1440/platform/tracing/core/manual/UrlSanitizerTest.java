package space.br1440.platform.tracing.core.manual;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UrlSanitizerTest {

    @Test
    void вырезаетUserinfoИРедактируетQuery() {
        String sanitized = UrlSanitizer.sanitize(
                "https://user:secretpass@api.example.com/v1/orders?token=abc123&page=2");

        assertThat(sanitized).doesNotContain("secretpass").doesNotContain("user:");
        assertThat(sanitized).doesNotContain("abc123");
        assertThat(sanitized).contains("token=REDACTED").contains("page=REDACTED");
        assertThat(sanitized).contains("api.example.com/v1/orders");
    }

    @Test
    void неИзменяетUrlБезQueryИUserinfo() {
        String url = "https://api.example.com/v1/orders";

        assertThat(UrlSanitizer.sanitize(url)).isEqualTo(url);
    }

    @Test
    void сохраняетFragment() {
        String sanitized = UrlSanitizer.sanitize("https://h/p?a=1#frag");

        assertThat(sanitized).endsWith("#frag");
        assertThat(sanitized).contains("a=REDACTED");
    }
}

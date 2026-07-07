package space.br1440.platform.tracing.api.span.sanitize;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SanitizerTest {

    @Test
    void sql_заменяетСтроковыеИЧисловыеЛитералыНаПлейсхолдер() {
        String sanitized = SqlSanitizer.sanitize(
                "SELECT * FROM orders WHERE id = 42 AND ssn = '123-45-6789'");
        assertThat(sanitized).doesNotContain("42").doesNotContain("123-45-6789");
        assertThat(sanitized).contains("SELECT").contains("orders").contains("?");
    }

    @Test
    void sql_схлопываетINСписок() {
        String sanitized = SqlSanitizer.sanitize("SELECT x FROM t WHERE id IN (1, 2, 3, 4)");
        assertThat(sanitized).contains("IN (?)");
    }

    @Test
    void sql_null_иПустую_возвращаетNull() {
        assertThat(SqlSanitizer.sanitize(null)).isNull();
        assertThat(SqlSanitizer.sanitize("   ")).isNull();
    }

    @Test
    void url_вырезаетUserinfoИРедактируетQuery() {
        String sanitized = UrlSanitizer.sanitize(
                "https://user:secretpass@api.example.com/v1/orders?token=abc123&page=2");
        assertThat(sanitized).doesNotContain("secretpass").doesNotContain("user:");
        assertThat(sanitized).doesNotContain("abc123");
        assertThat(sanitized).contains("token=REDACTED").contains("page=REDACTED");
        assertThat(sanitized).contains("api.example.com/v1/orders");
    }

    @Test
    void url_безQueryИUserinfo_неИзменяется() {
        String url = "https://api.example.com/v1/orders";
        assertThat(UrlSanitizer.sanitize(url)).isEqualTo(url);
    }

    @Test
    void url_сохраняетFragment() {
        String sanitized = UrlSanitizer.sanitize("https://h/p?a=1#frag");
        assertThat(sanitized).endsWith("#frag");
        assertThat(sanitized).contains("a=REDACTED");
    }
}

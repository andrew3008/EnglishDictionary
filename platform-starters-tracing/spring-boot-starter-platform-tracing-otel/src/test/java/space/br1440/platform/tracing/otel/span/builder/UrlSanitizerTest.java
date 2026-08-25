package space.br1440.platform.tracing.otel.span.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UrlSanitizer")
class UrlSanitizerTest {

    @Nested
    @DisplayName("degenerate inputs")
    class DegenerateInputs {

        @ParameterizedTest(name = "[{index}] blank/null → null")
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t", "\n", "   "})
        void nullOrBlank_returnsNull(String input) {
            assertThat(UrlSanitizer.sanitize(input)).isNull();
        }

        @Test
        @DisplayName("URL without scheme, query and userinfo — returned unchanged")
        void plainPath_returnedAsIs() {
            assertThat(UrlSanitizer.sanitize("/api/v1/orders"))
                    .isEqualTo("/api/v1/orders");
        }

        @Test
        @DisplayName("leading/trailing whitespace is stripped")
        void leadingTrailingSpaces_stripped() {
            assertThat(UrlSanitizer.sanitize("  https://host/path  "))
                    .isEqualTo("https://host/path");
        }
    }

    @Nested
    @DisplayName("userinfo removal")
    class UserinfoRemoval {

        @Test
        @DisplayName("user:pass@host — userinfo is removed completely")
        void userColonPass_removed() {
            assertThat(UrlSanitizer.sanitize("https://user:secretpass@api.example.com/v1/orders"))
                    .isEqualTo("https://api.example.com/v1/orders");
        }

        @Test
        @DisplayName("user only (no password) — userinfo is removed")
        void userOnly_removed() {
            assertThat(UrlSanitizer.sanitize("https://admin@host/path"))
                    .isEqualTo("https://host/path");
        }

        @Test
        @DisplayName("no userinfo — URL unchanged")
        void noUserinfo_unchanged() {
            assertThat(UrlSanitizer.sanitize("https://api.example.com/v1/orders"))
                    .isEqualTo("https://api.example.com/v1/orders");
        }

        @Test
        @DisplayName("userinfo + query — only userinfo removed, query redacted")
        void userinfoWithQuery_bothHandled() {
            assertThat(UrlSanitizer.sanitize(
                    "https://user:pass@api.example.com/v1?token=abc&page=2"))
                    .isEqualTo("https://api.example.com/v1?token=REDACTED&page=REDACTED");
        }

        @Test
        @DisplayName("@ at start of path segment is not treated as userinfo — regression bug #1")
        void atSignInPath_notTreatedAsUserinfo() {
            assertThat(UrlSanitizer.sanitize("https://api.example.com/@me/profile"))
                    .isEqualTo("https://api.example.com/@me/profile");
        }

        @Test
        @DisplayName("@ in query value is not treated as userinfo")
        void atSignInQueryValue_notTreatedAsUserinfo() {
            assertThat(UrlSanitizer.sanitize("https://host/path?redirect=user@example.com"))
                    .isEqualTo("https://host/path?redirect=REDACTED");
        }

        @Test
        @DisplayName("URL without path and query: scheme://user:pass@host")
        void userinfoNoPath_removed() {
            assertThat(UrlSanitizer.sanitize("ftp://user:pass@ftp.example.com"))
                    .isEqualTo("ftp://ftp.example.com");
        }

        @Test
        @DisplayName("non-standard scheme (jdbc): userinfo is removed")
        void jdbcUrl_userinfoRemoved() {
            assertThat(UrlSanitizer.sanitize("jdbc:postgresql://user:pass@db.host:5432/mydb"))
                    .isEqualTo("jdbc:postgresql://db.host:5432/mydb");
        }
    }

    @Nested
    @DisplayName("query redaction")
    class QueryRedaction {

        @Test
        @DisplayName("one parameter with value — value replaced with REDACTED")
        void singleParam_valueRedacted() {
            assertThat(UrlSanitizer.sanitize("https://host/path?token=secret"))
                    .isEqualTo("https://host/path?token=REDACTED");
        }

        @Test
        @DisplayName("multiple parameters — all values redacted, names preserved")
        void multipleParams_allValuesRedacted() {
            assertThat(UrlSanitizer.sanitize("https://host/path?a=1&b=2&c=3"))
                    .isEqualTo("https://host/path?a=REDACTED&b=REDACTED&c=REDACTED");
        }

        @Test
        @DisplayName("flag parameter without '=' — left unchanged")
        void flagParam_keptAsIs() {
            assertThat(UrlSanitizer.sanitize("https://host/path?debug&token=abc"))
                    .isEqualTo("https://host/path?debug&token=REDACTED");
        }

        @Test
        @DisplayName("empty parameter value ('key=') — REDACTED")
        void emptyParamValue_redacted() {
            assertThat(UrlSanitizer.sanitize("https://host/path?token="))
                    .isEqualTo("https://host/path?token=REDACTED");
        }

        @Test
        @DisplayName("empty query string ('?') — returned as-is")
        void emptyQuery_keptAsIs() {
            assertThat(UrlSanitizer.sanitize("https://host/path?"))
                    .isEqualTo("https://host/path?");
        }

        @Test
        @DisplayName("fragment preserved after query redaction")
        void fragment_preservedAfterQueryRedaction() {
            assertThat(UrlSanitizer.sanitize("https://host/path?a=1&b=2#section"))
                    .isEqualTo("https://host/path?a=REDACTED&b=REDACTED#section");
        }

        @Test
        @DisplayName("fragment without query — URL unchanged")
        void fragmentWithoutQuery_unchanged() {
            assertThat(UrlSanitizer.sanitize("https://host/path#section"))
                    .isEqualTo("https://host/path#section");
        }

        @Test
        @DisplayName("percent-encoded character in parameter name — name preserved literally")
        void percentEncodedParamName_preservedVerbatim() {
            assertThat(UrlSanitizer.sanitize("https://host/path?%61pi_key=secret"))
                    .isEqualTo("https://host/path?%61pi_key=REDACTED");
        }
    }

    @Nested
    @DisplayName("length truncation")
    class LengthTruncation {

        private static final int MAX_LENGTH = 1000;

        @Test
        @DisplayName("URL shorter than MAX_LENGTH — not truncated")
        void shortUrl_notTruncated() {
            String url = "https://host/" + "a".repeat(100);
            assertThat(UrlSanitizer.sanitize(url))
                    .hasSize(url.length());
        }

        @Test
        @DisplayName("URL longer than MAX_LENGTH — truncated to MAX_LENGTH")
        void longUrl_truncatedToMaxLength() {
            String url = "https://host/" + "a".repeat(MAX_LENGTH);
            assertThat(UrlSanitizer.sanitize(url))
                    .hasSizeLessThanOrEqualTo(MAX_LENGTH);
        }

        @Test
        @DisplayName("truncation does not split %XX: boundary on '%' — regression bug #2")
        void truncationDoesNotBreakPercentEncoding_percentAtCutMinus1() {
            String prefix = "https://host/";
            String padding = "a".repeat(MAX_LENGTH - 1 - prefix.length());
            String url = prefix + padding + "%AB";  // длина = MAX_LENGTH + 2

            String result = UrlSanitizer.sanitize(url);
            assertThat(result)
                    .hasSizeLessThanOrEqualTo(MAX_LENGTH)
                    .doesNotEndWith("%");
        }

        @Test
        @DisplayName("truncation does not split %XX: boundary on first hex char — regression bug #2")
        void truncationDoesNotBreakPercentEncoding_firstHexAtCutMinus1() {
            String prefix = "https://host/";
            String padding = "a".repeat(MAX_LENGTH - 2 - prefix.length());
            String url = prefix + padding + "%AB";  // длина = MAX_LENGTH + 1

            String result = UrlSanitizer.sanitize(url);
            assertThat(result)
                    .hasSizeLessThanOrEqualTo(MAX_LENGTH)
                    .doesNotEndWith("%");
        }
    }

    @Nested
    @DisplayName("production-like scenarios")
    class ProductionScenarios {

        @Test
        @DisplayName("full URL: scheme + userinfo + host + port + path + query + fragment")
        void fullUrl_allPartsHandled() {
            assertThat(UrlSanitizer.sanitize(
                    "https://admin:p%40ss@api.example.com:8443/v2/users?sort=name&filter=active#results"))
                    .isEqualTo("https://api.example.com:8443/v2/users?sort=REDACTED&filter=REDACTED#results");
        }

        @Test
        @DisplayName("actuator health-check — URL without query unchanged")
        void actuatorHealth_unchanged() {
            assertThat(UrlSanitizer.sanitize("http://localhost:8080/actuator/health"))
                    .isEqualTo("http://localhost:8080/actuator/health");
        }

        @Test
        @DisplayName("OAuth2 redirect_uri with query — values redacted")
        void oauth2Redirect_queryRedacted() {
            assertThat(UrlSanitizer.sanitize(
                    "https://auth.example.com/oauth2/authorize?client_id=myapp&redirect_uri=https://app/cb&state=xyz&nonce=abc"))
                    .isEqualTo("https://auth.example.com/oauth2/authorize" +
                            "?client_id=REDACTED&redirect_uri=REDACTED&state=REDACTED&nonce=REDACTED");
        }

        @Test
        @DisplayName("Kafka REST Proxy URL without query — unchanged")
        void kafkaRestProxy_unchanged() {
            assertThat(UrlSanitizer.sanitize(
                    "http://kafka-rest:8082/v3/clusters/abc123/topics/platform.events/records"))
                    .isEqualTo("http://kafka-rest:8082/v3/clusters/abc123/topics/platform.events/records");
        }
    }
}

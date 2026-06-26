package space.br1440.platform.errorhandling.core.util.sanitize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PathSanitizer")
class PathSanitizerTest {

    @Nested
    @DisplayName("Null / blank")
    class NullSafety {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void blank_returned_as_is(String input) {
            assertThat(PathSanitizer.sanitize(input)).isEqualTo(input);
        }
    }

    @Nested
    @DisplayName("Numeric IDs")
    class NumericIds {

        @ParameterizedTest
        @CsvSource({
                "/users/12345,                  /users/{id}",
                "/api/v1/orders/42,             /api/v1/orders/{id}",
                "/users/1/orders/2/items/3,     /users/{id}/orders/{id}/items/{id}",
                "/12345,                        /{id}"
        })
        void ascii_digits_replaced(String input, String expected) {
            assertThat(PathSanitizer.sanitize(input)).isEqualTo(expected);
        }

        @Test
        void api_version_segment_preserved() {
            assertThat(PathSanitizer.sanitize("/api/v1/orders"))
                    .isEqualTo("/api/v1/orders");
        }
    }

    @Nested
    @DisplayName("Polyglot digits (CWE-209 evasion)")
    class PolyglotDigits {

        @Test
        void arabic_indic_digits_replaced() {
            String input = "/users/\u0661\u0662\u0663\u0664\u0665/profile";

            String result = PathSanitizer.sanitize(input);

            assertThat(result).isEqualTo("/users/{id}/profile");
            assertThat(result).doesNotContain("\u0661", "\u0662", "\u0663");
        }

        @Test
        void bengali_digits_replaced() {
            String input = "/orders/\u09E6\u09E7\u09E8\u09E9";

            assertThat(PathSanitizer.sanitize(input)).isEqualTo("/orders/{id}");
        }

        @Test
        void devanagari_digits_replaced() {
            String input = "/items/\u0966\u0967\u0968";

            assertThat(PathSanitizer.sanitize(input)).isEqualTo("/items/{id}");
        }

        @Test
        void fullwidth_digits_replaced() {
            String input = "/users/\uFF11\uFF12\uFF13\uFF14";

            assertThat(PathSanitizer.sanitize(input)).isEqualTo("/users/{id}");
        }
    }

    @Nested
    @DisplayName("UUIDs")
    class Uuids {

        @ParameterizedTest
        @CsvSource({
                "/users/550e8400-e29b-41d4-a716-446655440000,                       /users/{id}",
                "/users/550E8400-E29B-41D4-A716-446655440000/orders,                /users/{id}/orders",
                "/r/550e8400-e29b-41d4-a716-446655440000/s/550e8400-e29b-41d4-a716-446655440001, /r/{id}/s/{id}"
        })
        void uuid_replaced(String input, String expected) {
            assertThat(PathSanitizer.sanitize(input)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("Hex tokens (>= 6 chars)")
    class HexTokens {

        @ParameterizedTest
        @CsvSource({
                "/sessions/abcdef0123,                  /sessions/{id}",
                "/files/deadbeefcafe,                   /files/{id}",
                "/x/abcdef,                             /x/{id}"
        })
        void hex_token_replaced(String input, String expected) {
            assertThat(PathSanitizer.sanitize(input)).isEqualTo(expected);
        }

        @Test
        void short_hex_segment_preserved() {
            assertThat(PathSanitizer.sanitize("/api/eu/users"))
                    .isEqualTo("/api/eu/users");
        }
    }

    @Nested
    @DisplayName("Query string / fragment removal")
    class QueryStripping {

        @ParameterizedTest
        @CsvSource({
                "/users/42?token=abc,                   /users/{id}",
                "/users?password=hunter2&id=1,          /users",
                "/users/42#section,                     /users/{id}"
        })
        void query_and_fragment_stripped(String input, String expected) {
            assertThat(PathSanitizer.sanitize(input)).isEqualTo(expected);
        }

        @Test
        void query_secrets_never_leak() {
            String result = PathSanitizer.sanitize("/api/login?password=hunter2&token=secret");

            assertThat(result).doesNotContain("password", "hunter2", "token", "secret");
        }
    }

    @Nested
    @DisplayName("Truncation")
    class Truncation {

        @Test
        void long_path_truncated_to_default_limit() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 50; i++) {
                sb.append("/segment").append((char) ('a' + (i % 26)));
            }
            String longStaticPath = sb.toString();

            String result = PathSanitizer.sanitize(longStaticPath);

            assertThat(result).hasSizeLessThanOrEqualTo(PathSanitizer.MAX_PATH_LENGTH + 16);
            assertThat(result).contains("[TRUNCATED]");
        }

        @Test
        void short_sanitized_path_not_truncated() {
            String collapsibleInput = "/api/" + "a".repeat(500);

            String result = PathSanitizer.sanitize(collapsibleInput);

            assertThat(result).isEqualTo("/api/{id}");
            assertThat(result).doesNotContain("[TRUNCATED]");
        }

        @Test
        void custom_max_length_respected() {
            String input = "/api/v1/users/12345/orders/67890";

            String result = PathSanitizer.sanitize(input, 20);

            assertThat(result).hasSizeLessThanOrEqualTo(20 + 16);
        }

        @Test
        void custom_max_length_returns_blank_as_is_for_empty() {
            assertThat(PathSanitizer.sanitize("", 20))
                    .as("Пустой input должен возвращаться без изменений (а не \"\" или null)")
                    .isEmpty();
        }

        @Test
        void custom_max_length_returns_blank_as_is_for_whitespace() {
            assertThat(PathSanitizer.sanitize("   ", 20))
                    .as("Whitespace input должен возвращаться без изменений")
                    .isEqualTo("   ");
        }

        @Test
        void custom_max_length_sanitizes_dynamic_segments_and_truncates() {
            String input = "/api/v1/users/12345/orders/67890";

            String result = PathSanitizer.sanitize(input, 20);

            assertThat(result)
                    .as("Динамические сегменты должны быть заменены на placeholder")
                    .startsWith("/api/v1/users/{id}");
        }

        @Test
        void custom_max_length_returns_non_empty_for_non_blank_input() {
            // Защита от мутанта EmptyObjectReturnVals: возврат "" вместо реального
            // санитизированного пути.
            assertThat(PathSanitizer.sanitize("/api/users/1", 50))
                    .as("Результат для непустого входа не должен быть пустой строкой")
                    .isNotEmpty()
                    .isNotBlank();
        }
    }

    @Nested
    @DisplayName("No-op cases")
    class NoOpCases {

        @ParameterizedTest
        @ValueSource(strings = {
                "/api/users",
                "/health",
                "/v2/orders/search",
                "/"
        })
        void static_paths_unchanged(String input) {
            assertThat(PathSanitizer.sanitize(input)).isEqualTo(input);
        }
    }
}

package space.br1440.platform.errorhandling.core.util.sanitize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MessageSanitizer")
class MessageSanitizerTest {

    @Nested
    @DisplayName("sanitize() — null-safety")
    class NullSafety {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t\n"})
        void blank_input_returned_as_is(String input) {
            assertThat(MessageSanitizer.sanitize(input)).isEqualTo(input);
        }
    }

    @Nested
    @DisplayName("Bearer / Basic auth")
    class AuthHeaders {

        @ParameterizedTest
        @ValueSource(strings = {
                "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig",
                "Bearer abc.def-ghi_jkl/mn=",
                "bearer ABCDEF"
        })
        void bearer_token_is_masked(String input) {
            String result = MessageSanitizer.sanitize(input);

            assertThat(result).contains("Bearer ***");
            assertThat(result).doesNotContain("eyJ", "abc.def", "ABCDEF");
        }

        @Test
        void basic_auth_is_masked() {
            String result = MessageSanitizer.sanitize("Authorization: Basic dXNlcjpwYXNzd29yZA==");

            assertThat(result).contains("Basic ***");
            assertThat(result).doesNotContain("dXNlcjpwYXNzd29yZA");
        }
    }

    @Nested
    @DisplayName("Card numbers")
    class CardNumbers {

        @ParameterizedTest
        @ValueSource(strings = {
                "Payment failed for card 4242424242424242",
                "card=378282246310005 declined",
                "VISA 4111111111111111 invalid"
        })
        void pan_is_masked(String input) {
            String result = MessageSanitizer.sanitize(input);

            assertThat(result).contains("***");
            assertThat(result).doesNotContain("4242424242424242", "378282246310005", "4111111111111111");
        }

        @Test
        void short_numeric_id_is_not_masked() {
            String result = MessageSanitizer.sanitize("Order 12345 not found");

            assertThat(result).contains("12345");
        }

        @Test
        void uuid_is_not_masked_as_card() {
            String input = "Resource 550e8400-e29b-41d4-a716-446655440000 missing";

            String result = MessageSanitizer.sanitize(input);

            assertThat(result).isEqualTo(input);
        }
    }

    @Nested
    @DisplayName("Query / form secrets")
    class QuerySecrets {

        @ParameterizedTest
        @CsvSource({
                "GET /api?token=abc123,                                  token=***",
                "POST body password=hunter2&user=alice,                  password=***",
                "url=https://svc?api_key=xyz&page=1,                     api_key=***",
                "Failed: client_secret=topsecret with extra,             client_secret=***",
                "?Authorization=raw-value&next=42,                       Authorization=***",
                "refresh_token=long.token.value;other=1,                 refresh_token=***"
        })
        void secret_query_param_is_masked(String input, String mustContain) {
            String result = MessageSanitizer.sanitize(input);

            assertThat(result).contains(mustContain);
            assertThat(result).doesNotContain("abc123", "hunter2", "xyz", "topsecret", "raw-value", "long.token.value");
        }

        @Test
        void non_secret_param_is_preserved() {
            String input = "GET /api?page=1&size=20";

            assertThat(MessageSanitizer.sanitize(input)).isEqualTo(input);
        }
    }

    @Nested
    @DisplayName("JDBC connection strings")
    class JdbcConnectionStrings {

        @Test
        void jdbc_password_is_masked() {
            String input = "jdbc:postgresql://db:5432/app?user=admin&password=Pa$$w0rd&ssl=true";

            String result = MessageSanitizer.sanitize(input);

            assertThat(result).contains("password=***");
            assertThat(result).doesNotContain("Pa$$w0rd");
        }
    }

    @Nested
    @DisplayName("Composition")
    class Composition {

        @Test
        void multiple_secrets_in_one_message_all_masked() {
            String input = "Authorization: Bearer JWT.token, db password=hunter2, card 4242424242424242";

            String result = MessageSanitizer.sanitize(input);

            assertThat(result).contains("Bearer ***", "password=***", "***");
            assertThat(result).doesNotContain("JWT.token", "hunter2", "4242424242424242");
        }
    }

    @Nested
    @DisplayName("sanitizeAndTruncate()")
    class SanitizeAndTruncate {

        @Test
        void mask_is_preserved_when_truncate_applied_after_sanitize() {
            String input = "Authorization: Bearer " + "x".repeat(500);

            String result = MessageSanitizer.sanitizeAndTruncate(input, 60);

            assertThat(result).contains("Bearer ***");
            assertThat(result).doesNotContain("xxxxxxxx");
        }

        @Test
        void short_input_unchanged() {
            String result = MessageSanitizer.sanitizeAndTruncate("plain message", 100);

            assertThat(result).isEqualTo("plain message");
        }
    }

    @Nested
    @DisplayName("sanitizeWith() — custom patterns")
    class CustomPatterns {

        @Test
        void custom_pattern_appended_to_defaults() {
            List<Pattern> custom = List.of(Pattern.compile("(?i)session=[A-Za-z0-9]+"));

            String result = MessageSanitizer.sanitizeWith(
                    "Bearer abc, session=XYZ123, password=top", custom);

            assertThat(result).contains("Bearer ***", MessageSanitizer.MASK, "password=***");
            assertThat(result).doesNotContain("abc", "XYZ123", "top");
        }

        @Test
        void null_input_returns_null_even_with_custom_patterns() {
            List<Pattern> custom = List.of(Pattern.compile("foo"));

            assertThat(MessageSanitizer.sanitizeWith(null, custom))
                    .as("null вход должен возвращаться как null, не пустой строкой")
                    .isNull();
        }

        @Test
        void blank_input_returns_blank_with_custom_patterns() {
            List<Pattern> custom = List.of(Pattern.compile("foo"));

            assertThat(MessageSanitizer.sanitizeWith("   ", custom))
                    .as("blank вход не должен меняться даже при наличии custom-паттернов")
                    .isEqualTo("   ");
        }

        @Test
        void empty_custom_list_falls_back_to_default_sanitize() {
            // Защита от мутанта RemoveConditional на customPatterns.isEmpty():
            // при пустом списке возвращается результат стандартного sanitize.
            String result = MessageSanitizer.sanitizeWith(
                    "Authorization: Bearer secret-token", List.of());

            assertThat(result)
                    .as("Пустой список custom-паттернов = только default-маскировка")
                    .contains("Bearer ***")
                    .doesNotContain("secret-token");
        }

        @Test
        void custom_pattern_returns_non_empty_for_non_blank_input() {
            // Защита от мутанта EmptyObjectReturnVals (return ""):
            // непустой вход должен иметь непустой результат.
            String result = MessageSanitizer.sanitizeWith(
                    "plain text", List.of(Pattern.compile("nonexistent")));

            assertThat(result).isNotEmpty().isEqualTo("plain text");
        }
    }
}

package space.br1440.platform.errorhandling.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ErrorResponseUtils")
class ErrorResponseUtilsTest {

    private static final String TEMPLATE_TWO_PLACEHOLDERS =
            "Resource ${resourceType} with id ${resourceId} not found";

    private static final String TEMPLATE_ONE_PLACEHOLDER =
            "Bad request: ${errorDescription}";

    private static final String TEMPLATE_NO_PLACEHOLDERS =
            "Internal server error";

    @Nested
    @DisplayName("formatMessage")
    class FormatMessage {

        @Test
        @DisplayName("substitutes all placeholders when matching keys provided")
        void substitutesPlaceholders() {
            var extras = Map.of("resourceType", "Order", "resourceId", "42");

            String result = ErrorResponseUtils.formatMessage(TEMPLATE_TWO_PLACEHOLDERS, extras);

            assertThat(result)
                    .contains("Order")
                    .contains("42")
                    .doesNotContain("${resourceType}")
                    .doesNotContain("${resourceId}");
        }

        @Test
        @DisplayName("null map returns template as-is")
        void nullMap_returnsTemplate() {
            String result = ErrorResponseUtils.formatMessage(TEMPLATE_NO_PLACEHOLDERS, null);

            assertThat(result).isEqualTo(TEMPLATE_NO_PLACEHOLDERS);
        }

        @Test
        @DisplayName("missing key leaves corresponding placeholder in place")
        void missingKey_leavesPlaceholder() {
            var extras = Map.of("resourceType", "Order");

            String result = ErrorResponseUtils.formatMessage(TEMPLATE_TWO_PLACEHOLDERS, extras);

            assertThat(result)
                    .contains("Order")
                    .contains("${resourceId}");
        }

        @Test
        @DisplayName("null value in map replaces placeholder with empty string")
        void nullValue_replacesWithEmpty() {
            Map<String, String> map = new HashMap<>();
            map.put("errorDescription", null);

            String result = ErrorResponseUtils.formatMessage(TEMPLATE_ONE_PLACEHOLDER, map);

            assertThat(result).doesNotContain("${errorDescription}");
        }

        @Test
        @DisplayName("empty map returns template as-is")
        void emptyMap_returnsTemplate() {
            String result = ErrorResponseUtils.formatMessage(TEMPLATE_NO_PLACEHOLDERS, Map.of());

            assertThat(result).isEqualTo(TEMPLATE_NO_PLACEHOLDERS);
        }

        @Test
        @DisplayName("null template returns empty string")
        void nullTemplate_returnsEmpty() {
            String result = ErrorResponseUtils.formatMessage(null, Map.of());

            assertThat(result).isEmpty();
        }
    }
}

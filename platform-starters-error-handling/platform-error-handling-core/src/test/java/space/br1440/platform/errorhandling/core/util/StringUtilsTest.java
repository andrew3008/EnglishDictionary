package space.br1440.platform.errorhandling.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StringUtils")
class StringUtilsTest {

    @Nested
    @DisplayName("truncate")
    class Truncate {

        @Test
        void returnsOriginalWhenShorterThanMax() {
            // when / then
            assertThat(StringUtils.truncate("abc", 10)).isEqualTo("abc");
        }

        @Test
        void returnsNullWhenInputNull() {
            // when / then
            assertThat(StringUtils.truncate(null, 5)).isNull();
        }

        @Test
        void appendsTruncationMarkerWhenTooLong() {
            // when / then
            assertThat(StringUtils.truncate("abcdefghij", 4)).isEqualTo("abcd [TRUNCATED]");
        }

        @Test
        void rejectsNonPositiveMaxChars() {
            // when / then
            assertThatThrownBy(() -> StringUtils.truncate("x", 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("firstNonBlank")
    class FirstNonBlank {

        @Test
        void picksFirstNonBlankInOrder() {
            // when / then
            assertThat(StringUtils.firstNonBlank("a", "b")).isEqualTo("a");
            assertThat(StringUtils.firstNonBlank("", "b")).isEqualTo("b");
            assertThat(StringUtils.firstNonBlank("  ", "x")).isEqualTo("x");
        }

        @Test
        void unknownWhenAllBlankOrNull() {
            // when / then
            assertThat(StringUtils.firstNonBlank(null, "", "  ")).isEqualTo("Unknown error");
        }
    }

    @Nested
    @DisplayName("orElse")
    class OrElse {

        @Test
        void prefersText() {
            // when / then
            assertThat(StringUtils.orElse("ok", "fallback")).isEqualTo("ok");
        }

        @Test
        void usesFallbackWhenBlank() {
            // when / then
            assertThat(StringUtils.orElse("  ", "fallback")).isEqualTo("fallback");
        }
    }

    @Nested
    @DisplayName("joinOrDefault")
    class JoinOrDefault {

        @Test
        void joinsNonEmpty() {
            // when / then
            assertThat(StringUtils.joinOrDefault(new String[] {"a", "b"}, "|", "d")).isEqualTo("a|b");
        }

        @Test
        void defaultWhenNullOrEmpty() {
            // when / then
            assertThat(StringUtils.joinOrDefault(null, "|", "d")).isEqualTo("d");
        }
    }

}

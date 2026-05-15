package space.br1440.platform.errorhandling.utils;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NullSafeToStringStyle")
class NullSafeToStringStyleTest {

    @Nested
    @DisplayName("DEFAULT preset")
    class DefaultPreset {

        @Test
        @DisplayName("uses short class name, curly braces, and hash identity")
        void shortClassNameAndBraces() {
            Object target = new Object();
            String result = new ToStringBuilder(target, NullSafeToStringStyle.DEFAULT)
                    .append("name", "val")
                    .append("count", 42)
                    .toString();

            assertThat(result)
                    .startsWith("Object@")
                    .endsWith("}")
                    .contains("{name=\"val\"")
                    .contains("count=42");
        }

        @Test
        @DisplayName("null fields rendered as 'null'")
        void nullField_renderedAsNull() {
            String result = new ToStringBuilder(new Object(), NullSafeToStringStyle.DEFAULT)
                    .append("field", (Object) null)
                    .toString();

            assertThat(result).contains("field=null");
        }

        @Test
        @DisplayName("String values wrapped in double quotes")
        void stringValues_wrappedInDoubleQuotes() {
            String result = new ToStringBuilder(new Object(), NullSafeToStringStyle.DEFAULT)
                    .append("text", "hello")
                    .toString();

            assertThat(result).contains("text=\"hello\"");
        }

        @Test
        @DisplayName("Date values wrapped in Date(...)")
        void dateValues_wrappedInDateFormat() {
            Date date = new Date(0);
            String result = new ToStringBuilder(new Object(), NullSafeToStringStyle.DEFAULT)
                    .append("created", date)
                    .toString();

            assertThat(result).contains("created=Date(\"");
        }

        @Test
        @DisplayName("Temporal values wrapped in Date(...)")
        void temporalValues_wrappedInDateFormat() {
            LocalDate date = LocalDate.of(2025, 1, 15);
            String result = new ToStringBuilder(new Object(), NullSafeToStringStyle.DEFAULT)
                    .append("day", date)
                    .toString();

            assertThat(result).contains("day=Date(\"2025-01-15\")");
        }

        @Test
        @DisplayName("Optional.empty rendered as 'Optional.empty'")
        void optionalEmpty_rendered() {
            String result = new ToStringBuilder(new Object(), NullSafeToStringStyle.DEFAULT)
                    .append("opt", Optional.empty())
                    .toString();

            assertThat(result).contains("opt=Optional.empty");
        }

        @Test
        @DisplayName("Optional with value rendered as 'Optional(\"value\")'")
        void optionalPresent_rendered() {
            String result = new ToStringBuilder(new Object(), NullSafeToStringStyle.DEFAULT)
                    .append("opt", Optional.of("data"))
                    .toString();

            assertThat(result).contains("opt=Optional(\"data\")");
        }

        @Test
        @DisplayName("Map values rendered with braces")
        void mapValues_rendered() {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("a", "1");
            map.put("b", "2");

            String result = new ToStringBuilder(new Object(), NullSafeToStringStyle.DEFAULT)
                    .append("meta", map)
                    .toString();

            assertThat(result).contains("{\"a\"=\"1\", \"b\"=\"2\"}");
        }
    }

    @Nested
    @DisplayName("SKIP_NULL preset")
    class SkipNullPreset {

        @Test
        @DisplayName("null fields are omitted entirely")
        void nullFields_omitted() {
            String result = new ToStringBuilder(new Object(), NullSafeToStringStyle.SKIP_NULL)
                    .append("present", "value")
                    .append("absent", (Object) null)
                    .toString();

            assertThat(result)
                    .contains("present=\"value\"")
                    .doesNotContain("absent");
        }

        @Test
        @DisplayName("non-null fields still rendered normally")
        void nonNullFields_renderedNormally() {
            String result = new ToStringBuilder(new Object(), NullSafeToStringStyle.SKIP_NULL)
                    .append("count", 7)
                    .toString();

            assertThat(result).contains("count=7");
        }
    }
}

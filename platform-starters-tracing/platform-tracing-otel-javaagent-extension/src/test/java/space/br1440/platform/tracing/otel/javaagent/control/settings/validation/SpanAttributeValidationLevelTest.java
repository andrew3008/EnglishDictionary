package space.br1440.platform.tracing.otel.javaagent.control.settings.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class SpanAttributeValidationLevelTest {

    @Test
    void blankDefaultsToWarningOnStartupParse() {
        // given/when/then
        assertThat(SpanAttributeValidationLevel.parse(null)).isEqualTo(SpanAttributeValidationLevel.WARNING);
        assertThat(SpanAttributeValidationLevel.parse("")).isEqualTo(SpanAttributeValidationLevel.WARNING);
        assertThat(SpanAttributeValidationLevel.parse("  ")).isEqualTo(SpanAttributeValidationLevel.WARNING);
    }

    @Test
    void recognizedValuesAreExact() {
        // given/when/then
        assertThat(SpanAttributeValidationLevel.parse("WARNING")).isEqualTo(SpanAttributeValidationLevel.WARNING);
        assertThat(SpanAttributeValidationLevel.parse("error")).isEqualTo(SpanAttributeValidationLevel.ERROR);
        assertThat(SpanAttributeValidationLevel.parse("DISABLED")).isEqualTo(SpanAttributeValidationLevel.DISABLED);
    }

    @Test
    void requireRejectsBlank() {
        // given/when/then
        assertThatThrownBy(() -> SpanAttributeValidationLevel.require(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform.tracing.javaagent.span-attributes.validation.level");
        assertThatThrownBy(() -> SpanAttributeValidationLevel.require("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(SpanAttributeValidationLevel.class)
    void requireAcceptsExactEnumNames(SpanAttributeValidationLevel expected) {
        // given
        String value = expected.name();

        // when
        SpanAttributeValidationLevel actual = SpanAttributeValidationLevel.require(value);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"error", "warning", " WARNING", "Error"})
    void requireRejectsNonCanonicalNames(String value) {
        // given
        String propertyName = "platform.tracing.javaagent.span-attributes.validation.level";

        // when
        Throwable failure = catchThrowable(() -> SpanAttributeValidationLevel.require(value));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(propertyName);
    }

    @Test
    void startupParseRemainsCaseAndWhitespaceTolerant() {
        // given
        String value = " warning ";

        // when
        SpanAttributeValidationLevel parsed = SpanAttributeValidationLevel.parse(value);

        // then
        assertThat(parsed).isEqualTo(SpanAttributeValidationLevel.WARNING);
    }
}

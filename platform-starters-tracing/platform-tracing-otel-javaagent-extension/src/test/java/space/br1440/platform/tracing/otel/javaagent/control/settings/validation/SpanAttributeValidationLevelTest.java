package space.br1440.platform.tracing.otel.javaagent.control.settings.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

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
}

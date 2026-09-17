package space.br1440.platform.tracing.otel.javaagent.control.settings.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SpanAttributeValidationLevelTest {

    @Test
    void blankDefaultsToWarn() {
        // given/when/then
        assertThat(SpanAttributeValidationLevel.parse(null)).isEqualTo(SpanAttributeValidationLevel.WARN);
        assertThat(SpanAttributeValidationLevel.parse("")).isEqualTo(SpanAttributeValidationLevel.WARN);
        assertThat(SpanAttributeValidationLevel.parse("  ")).isEqualTo(SpanAttributeValidationLevel.WARN);
    }

    @Test
    void recognizedValuesAreExact() {
        // given/when/then
        assertThat(SpanAttributeValidationLevel.parse("WARN")).isEqualTo(SpanAttributeValidationLevel.WARN);
        assertThat(SpanAttributeValidationLevel.parse("error")).isEqualTo(SpanAttributeValidationLevel.ERROR);
        assertThat(SpanAttributeValidationLevel.parse("DISABLED")).isEqualTo(SpanAttributeValidationLevel.DISABLED);
    }

    @Test
    void unknownValueIncludingRetiredVocabularyIsRejected() {
        // given/when/then
        assertThatThrownBy(() -> SpanAttributeValidationLevel.parse("STRICT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform.tracing.javaagent.span-attributes.validation.level")
                .hasMessageContaining("STRICT");

        assertThatThrownBy(() -> SpanAttributeValidationLevel.parse("LENIENT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LENIENT");

        assertThatThrownBy(() -> SpanAttributeValidationLevel.parse("not-a-level"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Accepted values: WARN, ERROR, DISABLED");
    }
}

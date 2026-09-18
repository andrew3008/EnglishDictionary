package space.br1440.platform.tracing.otel.javaagent.resource;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResourceValidationModeTest {

    @Test
    void blankDefaultsToWarning() {
        // given/when/then
        assertThat(ResourceValidationMode.fromConfig(null)).isEqualTo(ResourceValidationMode.WARNING);
        assertThat(ResourceValidationMode.fromConfig("")).isEqualTo(ResourceValidationMode.WARNING);
        assertThat(ResourceValidationMode.fromConfig("  ")).isEqualTo(ResourceValidationMode.WARNING);
    }

    @Test
    void recognizedValuesAreExact() {
        // given/when/then
        assertThat(ResourceValidationMode.fromConfig("WARNING")).isEqualTo(ResourceValidationMode.WARNING);
        assertThat(ResourceValidationMode.fromConfig("error")).isEqualTo(ResourceValidationMode.ERROR);
    }
}

package space.br1440.platform.tracing.otel.javaagent.scrubbing.loader;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExtensionRuleLoaderValidationModeTest {

    @Test
    void parseRecognizesClosedSet() {
        // given/when/then
        assertThat(ExtensionRuleLoader.ValidationMode.parse(null))
                .isEqualTo(ExtensionRuleLoader.ValidationMode.WARNING);

        assertThat(ExtensionRuleLoader.ValidationMode.parse("warning"))
                .isEqualTo(ExtensionRuleLoader.ValidationMode.WARNING);

        assertThat(ExtensionRuleLoader.ValidationMode.parse("ERROR"))
                .isEqualTo(ExtensionRuleLoader.ValidationMode.ERROR);
    }
}

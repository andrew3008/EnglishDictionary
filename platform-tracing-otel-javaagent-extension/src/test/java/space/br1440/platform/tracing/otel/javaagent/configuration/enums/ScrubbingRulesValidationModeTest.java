package space.br1440.platform.tracing.otel.javaagent.configuration.enums;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults;

import static org.assertj.core.api.Assertions.assertThat;

class ScrubbingRulesValidationModeTest {

    @Test
    void values_match_contract_strings() {
        assertThat(ScrubbingRulesValidationMode.LENIENT.value()).isEqualTo("LENIENT");
        assertThat(ScrubbingRulesValidationMode.STRICT.value()).isEqualTo("STRICT");
    }

    @Test
    void LENIENT_equals_platform_default() {
        assertThat(ScrubbingRulesValidationMode.LENIENT.value())
                .isEqualTo(ExtensionDefaults.DEFAULT_SCRUBBING_VALIDATION_MODE);
    }
}

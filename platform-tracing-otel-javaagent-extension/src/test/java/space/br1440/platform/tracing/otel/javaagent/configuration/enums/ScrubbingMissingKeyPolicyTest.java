package space.br1440.platform.tracing.otel.javaagent.configuration.enums;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults;

import static org.assertj.core.api.Assertions.assertThat;

class ScrubbingMissingKeyPolicyTest {

    @Test
    void values_match_contract_strings() {
        assertThat(ScrubbingMissingKeyPolicy.MASK.value()).isEqualTo("mask");
        assertThat(ScrubbingMissingKeyPolicy.FAIL_FAST.value()).isEqualTo("fail-fast");
    }

    @Test
    void MASK_equals_platform_default() {
        assertThat(ScrubbingMissingKeyPolicy.MASK.value())
                .isEqualTo(ExtensionDefaults.DEFAULT_SCRUBBING_MISSING_KEY_POLICY);
    }
}

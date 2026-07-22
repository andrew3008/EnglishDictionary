package space.br1440.platform.tracing.otel.javaagent.processor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization runtime validation policy updates (PR-5B).
 */
class ValidationRuntimePolicyCharacterizationTest {

    @Test
    void updateValidationPolicy_increments_version_for_lenient_update() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(false);
        long v0 = processor.getPolicyVersion();

        assertThat(processor.updateValidationPolicy(false, false)).isTrue();
        assertThat(processor.isEnabled()).isFalse();
        assertThat(processor.isStrict()).isFalse();
        assertThat(processor.getPolicyVersion()).isEqualTo(v0 + 1);
    }
}

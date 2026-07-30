package space.br1440.platform.tracing.otel.javaagent.processor;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyUpdateOutcome;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization runtime validation policy updates (PR-5B).
 */
class ValidationRuntimePolicyCharacterizationTest {

    @Test
    void updateValidation_increments_version_for_lenient_update() {
        AcceptedPolicyAccess access = AcceptedPolicyAccess.isolatedValidation(true, false, true);
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(access);
        long v0 = processor.getPolicyVersion();

        assertThat(access.updateValidation(false, false))
                .isEqualTo(AcceptedPolicyUpdateOutcome.APPLIED);
        assertThat(processor.isEnabled()).isFalse();
        assertThat(processor.isStrict()).isFalse();
        assertThat(processor.getPolicyVersion()).isEqualTo(v0 + 1);
    }
}
